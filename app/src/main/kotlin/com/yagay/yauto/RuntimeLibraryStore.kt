package com.yagay.yauto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class LibraryEntry(val id: String, val kind: String, val name: String, val body: String)

/** Local reusable scripts/commands/application sets. No executable code runs on save. */
internal object RuntimeLibraryStore {
    private const val FILE = "yauto_shortx_library"
    private const val KEY = "entries"
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
    val kinds = listOf("shell", "javascript", "mvel", "beanshell", "apps", "gesture")

    fun list(context: Context): List<LibraryEntry> = runCatching {
        val json = JSONArray(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "[]"))
        buildList {
            for (i in 0 until minOf(json.length(), 50)) {
                val item = json.optJSONObject(i) ?: continue
                val kind = item.optString("kind")
                if (kind !in kinds) continue
                add(LibraryEntry(
                    item.optString("id").take(80),
                    kind,
                    item.optString("name").take(80),
                    item.optString("body").take(20_000),
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun valid(entry: LibraryEntry): Boolean {
        if (entry.kind !in kinds || entry.name.isBlank() || entry.name.length > 80 ||
            entry.body.isBlank() || entry.body.length > 20_000) return false
        if (entry.kind == "apps") {
            val packages = entry.body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            if (packages.isEmpty() || packages.size > 200 ||
                packages.any { !packagePattern.matches(it) }) return false
        }
        if (entry.kind == "gesture") {
            val points = entry.body.lineSequence().filter(String::isNotBlank).toList()
            if (points.size !in 2..4096 || points.any { point ->
                val pair = point.split(",")
                pair.size != 2 || pair.any { text -> text.toIntOrNull()?.let { it in 0..20000 } != true }
            }) return false
        }
        return true
    }

    fun save(context: Context, entry: LibraryEntry): Boolean {
        if (!valid(entry)) return false
        val current = list(context).toMutableList()
        val i = current.indexOfFirst { it.id == entry.id }
        if (i >= 0) current[i] = entry else {
            if (current.size >= 50) return false
            current += entry.copy(id = UUID.randomUUID().toString())
        }
        return persist(context, current)
    }

    fun exportJson(context: Context): String {
        val entries = JSONArray()
        list(context).forEach { e ->
            entries.put(JSONObject().put("id", e.id).put("kind", e.kind)
                .put("name", e.name).put("body", e.body))
        }
        return JSONObject()
            .put("format", "YAuto.Library.v1")
            .put("entries", entries)
            .toString(2)
    }

    /** Import validates *all* entries before persisting any of them. */
    fun validateImportJson(text: String): List<LibraryEntry> {
        require(text.length <= 1_000_000)
        val document = JSONObject(text)
        require(document.optString("format") == "YAuto.Library.v1")
        val source = document.getJSONArray("entries")
        require(source.length() <= 50)
        val incoming = buildList {
            for (i in 0 until source.length()) {
                val item = source.getJSONObject(i)
                val entry = LibraryEntry(
                    "", item.getString("kind"), item.getString("name"), item.getString("body"),
                )
                require(valid(entry))
                add(entry)
            }
        }
        return incoming
    }

    fun importJson(context: Context, text: String): Int {
        val incoming = validateImportJson(text)
        val current = list(context).toMutableList()
        for (e in incoming) {
            val i = current.indexOfFirst { it.kind == e.kind && it.name == e.name }
            if (i >= 0) current[i] = e.copy(id = current[i].id)
            else current += e.copy(id = UUID.randomUUID().toString())
        }
        require(current.size <= 50)
        check(persist(context, current))
        return incoming.size
    }

    fun delete(context: Context, id: String): Boolean =
        persist(context, list(context).filterNot { it.id == id })

    private fun persist(context: Context, entries: List<LibraryEntry>): Boolean {
        val data = JSONArray()
        entries.forEach { e ->
            data.put(JSONObject().put("id", e.id).put("kind", e.kind)
                .put("name", e.name).put("body", e.body))
        }
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, data.toString()).commit()
    }
}
