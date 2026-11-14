package com.brick.history.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One line of a chat: what you asked ("you"), what Brick said ("brick" / "unit") or a readout ("box"). [category]
 * is Brick's category for the line (WEATHER, SEARCH...).
 */
data class Entry(val time: Long, val speaker: String, val text: String, val image: String?, val category: String?)

/**
 * A saved chat: requests about one topic. [title] is the latest request, [label] the kind of request it started
 * with ("Weather"), [topic] its main subject words. [trashed] is when it went to the trash (null while it's listed).
 */
data class Chat(
    val id: String,
    val title: String,
    val started: Long,
    val updated: Long,
    val restored: Long,
    val trashed: Long?,
    val requests: Int,
    val label: String,
    val topic: List<String>,
) {
    val lastUsed: Long get() = maxOf(updated, restored)
}

/** A new request from Brick: its type ("Weather"), category label, subject ("tokyo") and whether it's a reply. */
data class RequestInfo(val kind: String, val label: String?, val subject: String?, val follow: Boolean)

/** The History settings. */
data class Settings(
    val saving: Boolean = true,
    val age: Rules.AgeRule = Rules.AgeRule.NEVER,
    /** 0 = unlimited. */
    val maxCount: Int = 0,
    val followBrick: Boolean = true,
    val palette: String = "Brick",
)

/**
 * Chats on the phone (SQLite). Each request Brick sends is filed in the chat about the same topic ([Topics]); its
 * answers follow it by the request's id ("turn"). Every change bumps [version] so open screens reload. After each
 * change the rules run ([cleanup]): old or extra chats move to the trash and chats trashed 30 days ago are deleted.
 */
object Store {
    private lateinit var db: SQLiteDatabase
    private lateinit var prefs: SharedPreferences
    val isReady get() = ::db.isInitialized

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    private const val CHAT_COLS = "id, title, started, updated, restored, trashed, requests, label, words"

    @Synchronized
    fun init(context: Context) {
        if (isReady) return
        prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
        _settings.value = Settings(
            saving = prefs.getBoolean("saving", true),
            age = Rules.AgeRule.of(prefs.getString("age", null)),
            maxCount = prefs.getInt("maxCount", 0),
            followBrick = prefs.getBoolean("followBrick", true),
            palette = prefs.getString("palette", "Brick") ?: "Brick",
        )
        db = Helper(context.applicationContext).writableDatabase
    }

    fun update(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        _settings.value = s
        prefs.edit()
            .putBoolean("saving", s.saving)
            .putString("age", s.age.name)
            .putInt("maxCount", s.maxCount)
            .putBoolean("followBrick", s.followBrick)
            .putString("palette", s.palette)
            .apply()
        Palettes.recompute()
        // The rules may have changed: run them in the background.
        Thread { cleanup() }.apply { isDaemon = true }.start()
    }

    private fun changed() {
        _version.value++
    }

    // ------------------------------------------------------------ writing

    /**
     * Adds one line from Brick. A request ([info] set) is filed by topic; an answer goes to its request's chat
     * ([turn]). A line for a chat in the trash brings that chat back (you're still using it).
     */
    @Synchronized
    fun add(turn: String, e: Entry, info: RequestInfo?) {
        if (!_settings.value.saving) return
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val chatId: String
            var kind: String? = null
            var key: Set<String> = emptySet()
            if (e.speaker == "you") {
                kind = info?.kind ?: "Request"
                // A reply keeps its subject out of the topic ("yes" isn't what the chat is about).
                // Brick names the subject (or there is none: "what's 5 plus 5" is just math); an older Brick sends only text.
                val subject = if (info == null || kind == "Request") e.text else info.subject
                key = if (info?.follow == true) emptySet() else Topics.key(kind, Topics.words(subject))
                chatId = Topics.pick(kind, key, info?.follow == true, candidates(), now) ?: newChat(e, info)
            } else {
                chatId = chatOfTurn(turn) ?: return // its request wasn't saved (saving was off, or "Don't save")
            }
            db.insert("entries", null, ContentValues().apply {
                put("chat", chatId)
                put("turn", turn)
                put("time", e.time)
                put("speaker", e.speaker)
                put("text", e.text)
                put("image", e.image)
                put("category", e.category)
                if (kind != null) put("kind", kind)
                put("words", key.joinToString(" "))
            })
            if (e.speaker == "you") addTopic(chatId, kind!!, key, e)
            db.execSQL(
                "UPDATE chats SET updated = MAX(updated, ?), restored = CASE WHEN trashed IS NULL THEN restored ELSE ? END, " +
                    "trashed = NULL WHERE id = ?",
                arrayOf<Any>(e.time, now, chatId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        cleanup()
    }

    private fun newChat(e: Entry, info: RequestInfo?): String {
        val id = java.util.UUID.randomUUID().toString()
        db.insert("chats", null, ContentValues().apply {
            put("id", id)
            put("title", e.text.take(200))
            put("started", e.time)
            put("updated", e.time)
            put("restored", 0L)
            put("requests", 0)
            put("label", info?.label ?: "")
            put("kinds", "")
            put("words", "")
        })
        return id
    }

    /** The chat gets one more request: it's the new title, and its kind and subject join the chat's topic. */
    private fun addTopic(chatId: String, kind: String, key: Set<String>, e: Entry) {
        val c = db.rawQuery("SELECT kinds, words FROM chats WHERE id = ?", arrayOf(chatId))
        val (kinds, words) = if (c.moveToFirst()) c.getString(0).orEmpty() to c.getString(1).orEmpty() else "" to ""
        c.close()
        val k = (kinds.split(' ').filter { it.isNotEmpty() } + kind).distinct().joinToString(" ")
        // Newest words last; the oldest drop off past the cap.
        val w = (words.split(' ').filter { it.isNotEmpty() } + key).takeLast(Topics.MAX_WORDS).joinToString(" ")
        db.execSQL(
            "UPDATE chats SET kinds = ?, words = ?, title = ?, requests = requests + 1 WHERE id = ?",
            arrayOf<Any>(k, w, e.text.take(200), chatId),
        )
    }

    private fun chatOfTurn(turn: String): String? {
        val c = db.rawQuery("SELECT chat FROM entries WHERE turn = ? LIMIT 1", arrayOf(turn))
        val id = if (c.moveToFirst()) c.getString(0) else null
        c.close()
        return id
    }

    private fun candidates(): List<Topics.Candidate> {
        val c = db.rawQuery("SELECT id, kinds, words, MAX(updated, restored) FROM chats WHERE trashed IS NULL", null)
        val out = ArrayList<Topics.Candidate>()
        while (c.moveToNext()) {
            out += Topics.Candidate(
                c.getString(0),
                c.getString(1).orEmpty().split(' ').filter { it.isNotEmpty() }.toSet(),
                c.getString(2).orEmpty().split(' ').filter { it.isNotEmpty() }.toSet(),
                c.getLong(3),
            )
        }
        c.close()
        return out
    }

    /** Takes one request and Brick's answers to it out ("Don't save" in Brick). An emptied chat is deleted. */
    @Synchronized
    fun forget(turn: String) {
        val chatId = chatOfTurn(turn) ?: return
        db.beginTransaction()
        try {
            db.delete("entries", "turn = ?", arrayOf(turn))
            rebuild(chatId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    /** Works a chat's title, count, time and topic out again from what's left in it. */
    private fun rebuild(chatId: String) {
        val c = db.rawQuery("SELECT time, speaker, text, kind, words FROM entries WHERE chat = ? ORDER BY time, rowid", arrayOf(chatId))
        var requests = 0
        var last = 0L
        var title: String? = null
        val kinds = LinkedHashSet<String>()
        val words = ArrayList<String>()
        var any = false
        while (c.moveToNext()) {
            any = true
            last = maxOf(last, c.getLong(0))
            if (c.getString(1) == "you") {
                requests++
                title = c.getString(2)
                c.getString(3)?.let { kinds += it }
                c.getString(4).orEmpty().split(' ').filter { it.isNotEmpty() }.forEach { words += it }
            }
        }
        c.close()
        if (!any) {
            db.delete("chats", "id = ?", arrayOf(chatId))
            return
        }
        db.execSQL(
            "UPDATE chats SET requests = ?, updated = ?, title = ?, kinds = ?, words = ? WHERE id = ?",
            arrayOf<Any>(requests, last, (title ?: "").take(200), kinds.joinToString(" "), words.takeLast(Topics.MAX_WORDS).joinToString(" "), chatId),
        )
    }

    @Synchronized
    fun trash(ids: Collection<String>, now: Long = System.currentTimeMillis()) {
        if (ids.isEmpty()) return
        db.beginTransaction()
        try {
            ids.forEach { db.execSQL("UPDATE chats SET trashed = ? WHERE id = ? AND trashed IS NULL", arrayOf<Any>(now, it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    /**
     * Brings chats back from the trash. Their age restarts now, so the age rule won't send them straight back.
     * Returns how many other chats the count rule moved to the trash to make room.
     */
    @Synchronized
    fun restore(ids: Collection<String>): Int {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            ids.forEach { db.execSQL("UPDATE chats SET trashed = NULL, restored = ? WHERE id = ?", arrayOf<Any>(now, it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val moved = cleanup()
        changed()
        return moved
    }

    /** Deletes chats for good (from the trash). */
    @Synchronized
    fun deleteForever(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.beginTransaction()
        try {
            ids.forEach {
                db.delete("entries", "chat = ?", arrayOf(it))
                db.delete("chats", "id = ?", arrayOf(it))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    fun emptyTrash() = deleteForever(chats(trashed = true).map { it.id })

    /**
     * Runs the rules: moves old / extra chats to the trash and deletes chats trashed over 30 days ago.
     * Returns how many chats moved to the trash.
     */
    @Synchronized
    fun cleanup(now: Long = System.currentTimeMillis()): Int {
        if (!isReady) return 0
        val s = _settings.value
        val active = chats(trashed = false)
        val out = Rules.toTrash(active.map { Rules.Item(it.id, it.lastUsed) }, now, s.age, s.maxCount)
        if (out.isNotEmpty()) trash(out, now)
        val purge = Rules.toPurge(chats(trashed = true).associate { it.id to it.trashed!! }, now)
        if (purge.isNotEmpty()) deleteForever(purge)
        changed()
        return out.size
    }

    /** How many chats [s] would move to the trash right now (to warn before tightening a rule). */
    fun preview(s: Settings, now: Long = System.currentTimeMillis()): Int =
        Rules.toTrash(chats(trashed = false).map { Rules.Item(it.id, it.lastUsed) }, now, s.age, s.maxCount).size

    // ------------------------------------------------------------ reading

    private fun Cursor.chat() = Chat(
        getString(0), getString(1), getLong(2), getLong(3), getLong(4),
        if (isNull(5)) null else getLong(5), getInt(6), getString(7).orEmpty(),
        topicOf(getString(8).orEmpty()),
    )

    /** A chat's main subject: its most asked-about words (up to 3), in the order they first came up. */
    fun topicOf(words: String): List<String> {
        val list = words.split(' ').filter { it.isNotEmpty() && !it.startsWith("~") }
        val counts = list.groupingBy { it }.eachCount()
        val first = list.distinct()
        val top = first.sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy { first.indexOf(it) }).take(3).toSet()
        return first.filter { it in top }
    }

    /** Chats in the list (or in the trash), most recently used first; [query] keeps chats with a line matching it. */
    @Synchronized
    fun chats(trashed: Boolean, query: String = ""): List<Chat> {
        val where = StringBuilder(if (trashed) "trashed IS NOT NULL" else "trashed IS NULL")
        val args = ArrayList<String>()
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Every word must appear somewhere in the chat (loose phrasing: "pizza near" finds "pizza places near me").
        for (w in words) {
            where.append(" AND (title LIKE ? ESCAPE '\\' OR label LIKE ? ESCAPE '\\' OR id IN (SELECT chat FROM entries WHERE text LIKE ? ESCAPE '\\'))")
            val like = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            args += like; args += like; args += like
        }
        val order = if (trashed) "trashed DESC" else "MAX(updated, restored) DESC"
        val c = db.rawQuery("SELECT $CHAT_COLS FROM chats WHERE $where ORDER BY $order", args.toTypedArray())
        val out = ArrayList<Chat>()
        while (c.moveToNext()) out += c.chat()
        c.close()
        return out
    }

    @Synchronized
    fun chat(id: String): Chat? {
        val c = db.rawQuery("SELECT $CHAT_COLS FROM chats WHERE id = ?", arrayOf(id))
        val out = if (c.moveToFirst()) c.chat() else null
        c.close()
        return out
    }

    @Synchronized
    fun entries(chatId: String): List<Entry> {
        val c = db.rawQuery("SELECT time, speaker, text, image, category FROM entries WHERE chat = ? ORDER BY time, rowid", arrayOf(chatId))
        val out = ArrayList<Entry>()
        while (c.moveToNext()) out += Entry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
        c.close()
        return out
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "history.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE chats (id TEXT PRIMARY KEY, title TEXT NOT NULL, started INTEGER NOT NULL, " +
                    "updated INTEGER NOT NULL, restored INTEGER NOT NULL DEFAULT 0, trashed INTEGER, requests INTEGER NOT NULL DEFAULT 0, " +
                    "label TEXT NOT NULL DEFAULT '', kinds TEXT NOT NULL DEFAULT '', words TEXT NOT NULL DEFAULT '')",
            )
            db.execSQL(
                "CREATE TABLE entries (chat TEXT NOT NULL, time INTEGER NOT NULL, speaker TEXT NOT NULL, " +
                    "text TEXT NOT NULL, image TEXT, category TEXT, turn TEXT, kind TEXT, words TEXT)",
            )
            db.execSQL("CREATE INDEX entries_chat ON entries(chat)")
            db.execSQL("CREATE INDEX entries_turn ON entries(turn)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // Version 1 kept a chat per card: those chats stay as they were, and new requests are filed by topic.
                db.execSQL("ALTER TABLE chats ADD COLUMN label TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chats ADD COLUMN kinds TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chats ADD COLUMN words TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entries ADD COLUMN turn TEXT")
                db.execSQL("ALTER TABLE entries ADD COLUMN kind TEXT")
                db.execSQL("ALTER TABLE entries ADD COLUMN words TEXT")
                db.execSQL("CREATE INDEX entries_turn ON entries(turn)")
            }
        }
    }
}
