package com.brick.assistant.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A saved place: "Home" → "12 Main St, Springfield". [name] is what you say ("take me home"). */
data class SavedPlace(val id: String, val name: String, val address: String)

/**
 * Your saved places (as many as you like), kept in places.json in Brick's files. Asking for directions or a map
 * to a saved name ("take me home", "directions to work", "show the gym on the map") goes to its address.
 * Exported and imported as JSON: {"brickPlaces": 1, "places": [{"name": "...", "address": "..."}]}.
 */
object Places {
    private val _all = MutableStateFlow<List<SavedPlace>>(emptyList())
    val all: StateFlow<List<SavedPlace>> = _all
    private var file: File? = null

    fun init(context: Context) {
        val f = File(context.filesDir, "places.json")
        file = f
        _all.value = try { if (f.exists()) parse(f.readText()) else emptyList() } catch (_: Exception) { emptyList() }
    }

    /** "home", "my home", "Home." all find the place named "Home". */
    fun key(name: String): String = name.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
        .replace(Regex("^(?:my|the|our)\\s+"), "")
        .replace(Regex("\\s+"), " ").trim()

    /** The saved place called [spoken], if any ("my home" finds "Home"; "mom's house" finds "Mom's house"). */
    fun find(spoken: String): SavedPlace? {
        val k = key(spoken)
        if (k.isEmpty()) return null
        val list = _all.value
        return list.firstOrNull { key(it.name) == k }
            // "the gym" / "gym" when it's saved as "my gym"; "moms house" for "Mom's house".
            ?: list.firstOrNull { key(it.name).replace("'", "") == k.replace("'", "") }
    }

    /** Adds a place, or updates the one with that [id] (or that name). Returns an error to show, or null. */
    fun save(name: String, address: String, id: String? = null): String? {
        val n = name.trim()
        val a = address.trim()
        if (n.isEmpty()) return "Give the place a name."
        if (a.isEmpty()) return "Enter an address."
        val list = _all.value.toMutableList()
        val clash = list.firstOrNull { key(it.name) == key(n) && it.id != id }
        if (clash != null && id != null) return "You already have a place called \"${clash.name}\"."
        val i = list.indexOfFirst { it.id == (id ?: clash?.id) }
        val place = SavedPlace(if (i >= 0) list[i].id else UUID.randomUUID().toString(), n, a)
        if (i >= 0) list[i] = place else list += place
        set(list)
        return null
    }

    fun delete(id: String) = set(_all.value.filterNot { it.id == id })

    fun exportJson(): String = JSONObject()
        .put("brickPlaces", 1)
        .put("places", JSONArray().apply { _all.value.forEach { put(JSONObject().put("name", it.name).put("address", it.address)) } })
        .toString(2)

    /** Places in an exported file (or null when it isn't one). */
    fun read(json: String): List<SavedPlace>? = try { parse(json) } catch (_: Exception) { null }

    /** Adds the places from a file; one with the same name as a saved place replaces its address. */
    fun import(places: List<SavedPlace>): Int {
        var n = 0
        places.forEach { if (save(it.name, it.address) == null) n++ }
        return n
    }

    private fun parse(json: String): List<SavedPlace> {
        val t = json.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).getJSONArray("places")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val name = o.optString("name").trim()
            val address = o.optString("address").trim()
            if (name.isEmpty() || address.isEmpty()) null else SavedPlace(o.optString("id").ifBlank { UUID.randomUUID().toString() }, name, address)
        }
    }

    private fun set(list: List<SavedPlace>) {
        _all.value = list
        val f = file ?: return
        val json = JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("address", it.address)) } }
        try { f.writeText(JSONObject().put("brickPlaces", 1).put("places", json).toString()) } catch (_: Exception) {}
    }

    /** Tests: use these places without a file. */
    internal fun setForTest(list: List<SavedPlace>) { file = null; _all.value = list }
}
