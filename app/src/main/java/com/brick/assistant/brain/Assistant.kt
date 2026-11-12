package com.brick.assistant.brain

import com.brick.assistant.data.Category
import com.brick.assistant.data.ResponseStore
import com.brick.assistant.voice.VoiceEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class Speaker { USER, DAVID, ROBOT, SYSTEM }

/** [panel]: a terminal readout ([ActionResult.display]), drawn as-is in monospace and never spoken. */
data class LogEntry(
    val speaker: Speaker,
    val text: String,
    val category: Category? = null,
    val panel: Boolean = false,
    /** The whole readout for a [panel] entry: its picture and live controls too. */
    val readout: Readout? = null,
)

/**
 * One assistant turn:
 *   1. parse the request and pick its category
 *   2. David speaks a random line from that category's user-made pool
 *   3. the robot voice speaks the standard result line ("Setting timer for ...")
 *   4. any app hand-off (dialer, browser, music app) happens last
 * Data fetching and voice rendering run in parallel with playback to keep it snappy.
 */
class Assistant(
    private val responses: ResponseStore,
    private val voice: VoiceEngine,
    private val actions: Actions,
) {
    /** A question waiting for the user's yes or no (for example "Send this text?"), with when it was asked. */
    @Volatile private var pending: Pair<Confirmation, Long>? = null

    /** True right after Brick asked a yes-or-no question; the UI keeps listening for the answer. */
    val awaitingConfirmation: Boolean
        get() = pending?.let { System.currentTimeMillis() - it.second < CONFIRM_WINDOW_MS } == true

    private val yes = Regex("^(?:yes|yeah|yep|yup|yea|sure|ok|okay|send it|send|do it|go ahead|confirm|please do|please|absolutely|correct|that's right|another|another one|next|next one|show me another|show me|one more|definitely|of course)\\b.*", RegexOption.IGNORE_CASE)
    private val no = Regex("^(?:no|nope|nah|cancel|don't|do not|stop|never mind|nevermind|wait|hold on|that's it|that's all|that's good|i'm good|i'm done|this one|that one|all good)\\b.*", RegexOption.IGNORE_CASE)

    private val next = Regex("^(?:next|another|another one|skip|show me another|the next one|next one|one more|a different one)\\b.*", RegexOption.IGNORE_CASE)

    /** Plays the bottom animation in an app's colors (set by the app; respects the setting). */
    @Volatile var onBrand: ((com.brick.assistant.overlay.Brand) -> Unit)? = null

    /**
     * Asked once per request: true = answer in text only (no Brick or unit audio), e.g. outdoors. Every line still
     * goes to the log and the cards.
     */
    @Volatile var textOnly: suspend () -> Boolean = { false }

    /** The card's mute button: Brick's voice and/or the unit's stay silent (lines still show as text). */
    @Volatile var muteBrick = false
        private set
    @Volatile var muteUnit = false
        private set
    /** Who is talking right now, so muting them can cut the line short. */
    @Volatile private var speaking: Speaker? = null

    /** Mutes or unmutes each voice; a muted voice that's talking stops at once. */
    fun setMuted(brick: Boolean, unit: Boolean) {
        muteBrick = brick
        muteUnit = unit
        val s = speaking
        if (s == Speaker.DAVID && brick || s == Speaker.ROBOT && unit) voice.stopNow()
    }

    private fun muted(who: Speaker) = if (who == Speaker.DAVID) muteBrick else muteUnit

    /** Plays [file] as [who], unless that voice is muted. */
    private suspend fun playAs(who: Speaker, file: java.io.File) {
        if (muted(who)) return
        speaking = who
        try { voice.play(file) } finally { speaking = null }
    }

    /** The last request was answered in text only (the floating card stays up longer so you can read it). */
    @Volatile var lastTextOnly = false
        private set

    /** Set while the Android Auto screen is open: starts car navigation to a "geo:" URI. */
    @Volatile var carNavigator: ((String) -> Unit)? = null

    /** Until when the floating card should come back over the app Brick just opened, to hear the answer. */
    @Volatile var returnToCardUntil = 0L
        private set

    /** Waiting for dictation (a note): the next thing you say is the content, not a request. */
    @Volatile private var pendingDictation: Pair<suspend (String) -> ActionResult, Long>? = null

    val awaitingDictation: Boolean
        get() = pendingDictation?.let { System.currentTimeMillis() - it.second < CONFIRM_WINDOW_MS } == true

    /** [topic]: a one-purpose request from Battery ("Spotify: say a song"); what you said is read for that topic. */
    suspend fun handle(input: String, topic: Topic? = null, onEvent: (LogEntry) -> Unit) {
        returnToCardUntil = 0L
        // Dictation in progress: what you said is the note itself.
        val d = pendingDictation?.takeIf { System.currentTimeMillis() - it.second < CONFIRM_WINDOW_MS }
        pendingDictation = null
        if (d != null) { robotOnly(d.first(input.trim()), onEvent); return }
        // Answer to a pending yes-or-no question?
        val p = pending?.takeIf { System.currentTimeMillis() - it.second < CONFIRM_WINDOW_MS }
        pending = null
        if (p != null) {
            val answer = IntentParser.clean(input)
            val onNext = p.first.onNext
            // A question with its own answers ("Which one?"): a name or "all" goes to it first.
            p.first.onAnswer?.let { f -> f(answer)?.let { robotOnly(it, onEvent); return } }
            when {
                p.first.retry != null && yes.matches(answer) -> { handle(p.first.retry!!, onEvent = onEvent); return }
                onNext != null && next.matches(answer) -> { robotOnly(onNext(), onEvent); return }
                yes.matches(answer) -> { robotOnly(p.first.onYes(), onEvent); return }
                no.matches(answer) -> { robotOnly(ActionResult(p.first.noLine), onEvent); return }
                // Anything else: treat it as a new request (the question is dropped).
            }
        }

        val req = topic?.request(input) ?: IntentParser.parse(input)
        // Not understood, but close to a command ("unable lpf"): ask whether that's what you meant.
        if (req is Request.Search && !req.explicit) {
            IntentParser.suggest(input)?.let { fixed ->
                robotOnly(ActionResult("Did you mean \"${fixed.replace(Patterns.get("\\b(lpf|hpf|eq)\\b")) { it.value.uppercase() }}\"?",confirm = Confirmation(noLine = "Okay.", onYes = { ActionResult("Okay.") }, retry = fixed)), onEvent)
                return
            }
        }
        val cat = req.category
        // Spotify, YouTube or Google Maps get their colored edge animation (the action may name the app later).
        val early = actions.brandFor(req)
        // Most specific response rule with lines wins (e.g. Driver › Reverb › Turn on), then the type's pool.
        val davidLine = if (cat == null) null else responses.pick(actions.responseKeys(req))

        val quiet = isQuiet()
        val result = coroutineScope {
            val davidAudio = async { if (quiet || muteBrick) null else davidLine?.let { voice.renderDavid(it) } }
            val resultD = async { actions.prepare(req) }
            val robotAudio = async { if (quiet) null else resultD.await().let { r -> if (muted(speakerOf(r.voice))) null else renderLine(r) } }

            if (!quiet) voice.beginSpeaking()
            try {
                if (davidLine != null) {
                    onEvent(LogEntry(Speaker.DAVID, davidLine, cat))
                    davidAudio.await()?.let { playAs(Speaker.DAVID, it.file) }
                }
                val r = resultD.await()
                // Nothing to open (e.g. skipping a Spotify song): play it with the answer.
                val brand = early ?: actions.brand
                if (brand != null && r.after == null) onBrand?.invoke(brand)
                // The readout goes first so the spoken line stays the last entry (the card shows the last line).
                r.shown()?.let { onEvent(LogEntry(Speaker.SYSTEM, it.text, cat, panel = true, readout = it)) }
                if (r.voice != LineVoice.NONE) onEvent(LogEntry(speakerOf(r.voice), r.robotLine, cat))
                robotAudio.await()?.let { playAs(speakerOf(r.voice), it) }
                r
            } finally {
                if (!quiet) voice.endSpeaking()
            }
        }
        result.confirm?.let { pending = it to System.currentTimeMillis() }
        result.dictate?.let { pendingDictation = it to System.currentTimeMillis() }
        markReturn(result)
        // Opening the app: the animation plays over it as it opens.
        val brand = early ?: actions.brand
        handOff(result, brand?.takeIf { result.after != null })
    }

    /** Speaks only the robot's line (answers to a confirmation), then runs its hand-off. */
    /** The result line in the right voice (the unit, Brick, or none). */
    private suspend fun renderLine(r: ActionResult): java.io.File? = when (r.voice) {
        LineVoice.UNIT -> voice.renderRobot(r.robotLine)
        LineVoice.BRICK -> voice.renderDavid(r.robotLine)?.file
        LineVoice.NONE -> null
    }

    private fun speakerOf(v: LineVoice) = if (v == LineVoice.BRICK) Speaker.DAVID else Speaker.ROBOT

    /** Text only this time? (A failing check never silences Brick.) */
    private suspend fun isQuiet(): Boolean =
        (try { textOnly() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; false }).also { lastTextOnly = it || muteBrick && muteUnit }

    private suspend fun robotOnly(r: ActionResult, onEvent: (LogEntry) -> Unit) {
        r.shown()?.let { onEvent(LogEntry(Speaker.SYSTEM, it.text, panel = true, readout = it)) }
        if (r.voice != LineVoice.NONE) onEvent(LogEntry(speakerOf(r.voice), r.robotLine))
        if (!isQuiet() && r.voice != LineVoice.NONE && !muted(speakerOf(r.voice))) {
            voice.beginSpeaking()
            try {
                renderLine(r)?.let { playAs(speakerOf(r.voice), it) }
            } finally {
                voice.endSpeaking()
            }
        }
        r.confirm?.let { pending = it to System.currentTimeMillis() }
        r.dictate?.let { pendingDictation = it to System.currentTimeMillis() }
        markReturn(r)
        handOff(r)
    }

    /**
     * Set by the floating card while it shows over the lock screen: runs a hand-off to another app only
     * after the phone is unlocked.
     */
    @Volatile var handOffGate: ((run: () -> Unit) -> Unit)? = null

    private fun handOff(r: ActionResult, brand: com.brick.assistant.overlay.Brand? = null) {
        val nav = r.navigateTo
        val car = carNavigator
        if (nav != null && car != null) { car(nav); return }
        val open = r.after ?: return
        val after: () -> Unit = if (brand == null) open else ({ open(); onBrand?.invoke(brand); Unit })
        val gate = handOffGate
        if (gate != null) gate(after) else after()
    }

    private fun markReturn(r: ActionResult) {
        returnToCardUntil = if (r.returnToCard && r.confirm != null) System.currentTimeMillis() + 5_000 else 0L
    }

    companion object {
        private const val CONFIRM_WINDOW_MS = 60_000L
    }

    /** Used by the Responses / Settings screens to audition voices. */
    suspend fun preview(davidLine: String?, robotLine: String?) {
        voice.beginSpeaking()
        try {
            if (davidLine != null) voice.speakDavid(davidLine)
            if (robotLine != null) voice.speakRobot(robotLine)
        } finally {
            voice.endSpeaking()
        }
    }
}
