package com.brick.assistant.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists David's response pools: one per [Category] (stored under the category name) plus one per
 * [ResponseRule] (stored under "rule:<key>").
 */
class ResponseStore(context: Context) {
    private val prefs = context.getSharedPreferences("response_pools", Context.MODE_PRIVATE)
    private val _pools = MutableStateFlow(load())
    /** Every pool by store key (category name or "rule:<key>"). */
    val pools: StateFlow<Map<String, List<String>>> = _pools
    private val lastPicked = mutableMapOf<String, String>()

    private fun defaultsFor(key: String): List<String> =
        Category.entries.firstOrNull { it.name == key }?.defaults
            ?: ResponseRules.all.firstOrNull { it.storeKey == key }?.defaults
            ?: emptyList()

    private fun allKeys(): List<String> = Category.entries.map { it.name } + ResponseRules.all.map { it.storeKey }

    private fun load(): Map<String, List<String>> {
        // Seed defaults for every pool that has never been saved (also pools added in an update).
        val e = prefs.edit()
        allKeys().filter { !prefs.contains(it) }.forEach { e.putString(it, JSONArray(defaultsFor(it)).toString()) }
        e.putBoolean("seeded", true).apply()
        return allKeys().associateWith { parse(prefs.getString(it, null)) }
    }

    private fun parse(raw: String?): List<String> {
        if (raw == null) return emptyList()
        return try {
            val a = JSONArray(raw)
            List(a.length()) { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(key: String, list: List<String>) {
        prefs.edit().putString(key, JSONArray(list).toString()).apply()
        _pools.value = _pools.value + (key to list)
    }

    fun get(key: String): List<String> = _pools.value[key].orEmpty()
    fun get(c: Category): List<String> = get(c.name)

    fun add(key: String, text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) save(key, get(key) + t)
    }

    fun update(key: String, index: Int, text: String) {
        val t = text.trim()
        val list = get(key).toMutableList()
        if (index !in list.indices) return
        if (t.isEmpty()) list.removeAt(index) else list[index] = t
        save(key, list)
    }

    fun remove(key: String, index: Int) {
        val list = get(key).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            save(key, list)
        }
    }

    fun resetToDefaults(key: String) = save(key, defaultsFor(key))

    /** Random line from the first non-empty pool in [keys], avoiding an immediate repeat. */
    fun pick(keys: List<String>): String? {
        val key = keys.firstOrNull { get(it).isNotEmpty() } ?: return null
        val list = get(key)
        val choices = if (list.size > 1) list.filter { it != lastPicked[key] } else list
        return choices.random().also { lastPicked[key] = it }
    }

    fun pick(c: Category): String? = pick(listOf(c.name))

    // ---------------------------------------------------------------- export / import

    /** Every pool (request types and rules) as a JSON file Brick can import again. */
    fun exportJson(): String {
        val pools = JSONObject()
        allKeys().forEach { pools.put(it, JSONArray(get(it))) }
        return JSONObject()
            .put("format", "brick-response-pools")
            .put("version", 1)
            .put("exported", java.time.OffsetDateTime.now().toString())
            .put("pools", pools)
            .toString(2)
    }

    class ImportResult(val pools: Int, val lines: Int, val skipped: Int)

    /**
     * Replaces each pool that appears in [json] with the file's lines. Pools not in the file stay as they
     * are; unknown pool names are skipped. Throws IllegalArgumentException for a file that isn't a pool export.
     */
    fun importJson(json: String): ImportResult {
        val root = try { JSONObject(json) } catch (e: Exception) { throw IllegalArgumentException("That file isn't valid JSON.") }
        val pools = root.optJSONObject("pools")
            ?: throw IllegalArgumentException("That file doesn't contain Brick response pools.")
        val known = allKeys().toSet()
        var n = 0
        var lines = 0
        var skipped = 0
        val e = prefs.edit()
        var next = _pools.value
        for (key in pools.keys()) {
            if (key !in known) { skipped++; continue }
            val arr = pools.optJSONArray(key) ?: continue
            val list = List(arr.length()) { arr.optString(it).trim() }.filter { it.isNotEmpty() }
            e.putString(key, JSONArray(list).toString())
            next = next + (key to list)
            n++
            lines += list.size
        }
        e.apply()
        _pools.value = next
        return ImportResult(n, lines, skipped)
    }
}
