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
    /**
     * Other addresses of the same machine (its Wi-Fi and its Tailscale address, say), tried together
     * with [url] when the model picker opens. See [ServerIdentity].
     */
    val alternates: List<String> = emptyList(),
    /** What identifies the machine behind the server ([ServerIdentity.fingerprint]); "" if unknown. */
    val fingerprint: String = "",
) {
    /** What lists show: the user's name, else the address. */
    val label: String get() = name.ifBlank { SavedServers.hostOf(url) }

    /** [url] and the [alternates]: every address this server answers at. */
    val addresses: List<String> get() = listOf(url) + alternates
}

/**
 * A saved server that answered when the model picker opened, with the models it offers.
 * [activeUrl] is the address that answered (the preferred one when several did).
 */
data class RemoteServerSection(
    val server: SavedServer,
    val models: List<String>,
    val activeUrl: String = server.url,
)

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
        fingerprint: String = "",
    ): List<SavedServer> {
        val clash = list.firstOrNull { sameEndpoint(it.url, url) && it.id != editingId }
        val id = editingId ?: clash?.id ?: newId
        val base = if (editingId != null && clash != null) remove(list, clash.id) else list
        // Editing keeps what the form does not show: the other addresses, and the fingerprint when
        // the new one could not be read (an unreachable server).
        val old = base.firstOrNull { it.id == id }
        return upsert(
            base,
            SavedServer(
                id, name.trim(), url, apiKey?.trim()?.ifEmpty { null }, type,
                alternates = old?.alternates.orEmpty().filterNot { sameEndpoint(it, url) },
                fingerprint = fingerprint.ifEmpty { old?.fingerprint.orEmpty() },
            )
        )
    }

    /**
     * The saved server that [fingerprint] says is the same machine as one already in the list, when
     * it is reached at a different address; null when there is none (or no fingerprint).
     */
    fun findSameServer(list: List<SavedServer>, fingerprint: String, url: String, exceptId: String? = null): SavedServer? {
        if (fingerprint.isEmpty()) return null
        return list.firstOrNull {
            it.id != exceptId && it.fingerprint == fingerprint && it.addresses.none { a -> sameEndpoint(a, url) }
        }
    }

    /** Add [url] as another address of the server [id] (and its [fingerprint] if it had none). */
    fun addAddress(list: List<SavedServer>, id: String, url: String, fingerprint: String = ""): List<SavedServer> =
        list.map {
            if (it.id != id) it
            else it.copy(
                alternates = (it.alternates + url).distinctBy { a -> a.trim().trimEnd('/').lowercase() }
                    .filterNot { a -> sameEndpoint(a, it.url) },
                fingerprint = it.fingerprint.ifEmpty { fingerprint },
            )
        }

    /** Fold [absorbId] into [keepId]: it becomes one more address of it. */
    fun merge(list: List<SavedServer>, keepId: String, absorbId: String): List<SavedServer> {
        val absorbed = list.firstOrNull { it.id == absorbId } ?: return list
        if (keepId == absorbId || list.none { it.id == keepId }) return list
        var out = list
        for (address in absorbed.addresses) out = addAddress(out, keepId, address, absorbed.fingerprint)
        return remove(out, absorbId)
    }

    /**
     * Groups of saved servers that are certainly one machine: they share a *strong* fingerprint
     * (see [ServerIdentity.fingerprint]). Weak matches are only asked about when a server is saved.
     */
    fun duplicateGroups(list: List<SavedServer>): List<List<SavedServer>> =
        list.filter { ServerIdentity.isStrong(it.fingerprint) }
            .groupBy { it.fingerprint }
            .values
            .filter { it.size > 1 }

    /** Merge every [duplicateGroups] group into its first server. */
    fun mergeDuplicates(list: List<SavedServer>): List<SavedServer> =
        duplicateGroups(list).fold(list) { acc, group ->
            group.drop(1).fold(acc) { inner, dup -> merge(inner, group.first().id, dup.id) }
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
                    .put("alternates", JSONArray(it.alternates))
                    .put("fingerprint", it.fingerprint)
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
                val alternates = o.optJSONArray("alternates")?.let { a ->
                    (0 until a.length()).mapNotNull { j -> a.optString(j).ifBlank { null } }
                }.orEmpty()
                add(
                    SavedServer(
                        id, o.optString("name"), url, o.optString("apiKey").ifBlank { null }, o.optString("type"),
                        alternates = alternates,
                        fingerprint = o.optString("fingerprint"),
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
