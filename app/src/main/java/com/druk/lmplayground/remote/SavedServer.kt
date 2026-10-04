package com.druk.lmplayground.remote

import org.json.JSONArray
import org.json.JSONObject

/** A remote server the user saved: where it is and how to talk to it. */
data class SavedServer(
    val id: String,
    /** The user's own label; blank means "show the address". */
    val name: String,
    /** Normalized base URL, no trailing slash (see [SavedServers.normalizeUrl]). */
    val url: String,
    val apiKey: String? = null,
    /** Detected server software: "LM Studio", "Ollama", "llama.cpp", "OpenAI", or "" if unknown. */
    val type: String = "",
) {
    /** What lists show: the user's name, else the address. */
    val label: String get() = name.ifBlank { SavedServers.hostOf(url) }
}

/** A saved server that answered when the model picker opened, with the models it offers. */
data class RemoteServerSection(val server: SavedServer, val models: List<String>)

/**
 * The rules for the list of saved servers, kept free of Android so they can be unit-tested
 * (the JSON helpers need org.json, which the unit tests get from a test dependency).
 */
object SavedServers {

    /**
     * What the user typed -> the base URL the client wants: "192.168.1.5:8080/" becomes
     * "http://192.168.1.5:8080" (a missing scheme is added, since OkHttp rejects a URL
     * without one), and a trailing "/v1" is dropped because the client appends it itself.
     */
    fun normalizeUrl(input: String): String {
        var u = input.trim()
        if (u.isEmpty()) return ""
        if (!u.contains("://")) u = "http://$u"
        u = u.trimEnd('/')
        if (u.endsWith("/v1", ignoreCase = true)) u = u.dropLast(3).trimEnd('/')
        return u
    }

    /** "http://192.168.1.5:8080" -> "192.168.1.5:8080". */
    fun hostOf(url: String): String = url.substringAfter("://").ifEmpty { url }

    fun sameEndpoint(a: String, b: String): Boolean =
        a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)

    fun findByUrl(list: List<SavedServer>, url: String): SavedServer? =
        list.firstOrNull { sameEndpoint(it.url, url) }

    /** Add [server], or replace the entry with the same id (keeping its place in the list). */
    fun upsert(list: List<SavedServer>, server: SavedServer): List<SavedServer> =
        if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it }
        else list + server

    fun remove(list: List<SavedServer>, id: String): List<SavedServer> = list.filterNot { it.id == id }

    /**
     * Save the form: edits the server being edited ([editingId]), or adds a new one under
     * [newId]. One address is never saved twice: saving an address that is already in the list
     * updates that entry instead, and editing a server onto another's address replaces the other.
     */
    fun save(
        list: List<SavedServer>,
        editingId: String?,
        newId: String,
        name: String,
        url: String,
        apiKey: String?,
        type: String,
    ): List<SavedServer> {
        val clash = list.firstOrNull { sameEndpoint(it.url, url) && it.id != editingId }
        val id = editingId ?: clash?.id ?: newId
        val base = if (editingId != null && clash != null) remove(list, clash.id) else list
        return upsert(base, SavedServer(id, name.trim(), url, apiKey?.trim()?.ifEmpty { null }, type))
    }

    /** The single server from before several could be saved, if one was set. */
    fun fromLegacy(id: String, name: String?, url: String?, apiKey: String?, type: String?): SavedServer? {
        val normalized = normalizeUrl(url.orEmpty())
        if (normalized.isEmpty()) return null
        return SavedServer(id, name.orEmpty().trim(), normalized, apiKey?.trim()?.ifEmpty { null }, type.orEmpty())
    }

    fun toJson(list: List<SavedServer>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("url", it.url)
                    .put("apiKey", it.apiKey.orEmpty())
                    .put("type", it.type)
            )
        }
        return arr.toString()
    }

    /** Tolerant on purpose: a damaged entry is skipped rather than losing the whole list. */
    fun fromJson(json: String): List<SavedServer> = try {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val url = o.optString("url")
                if (id.isBlank() || url.isBlank()) continue
                add(SavedServer(id, o.optString("name"), url, o.optString("apiKey").ifBlank { null }, o.optString("type")))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
