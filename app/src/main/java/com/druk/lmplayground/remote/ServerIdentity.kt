package com.druk.lmplayground.remote

import org.json.JSONObject

/**
 * Telling servers apart by what they are, not by the address they were typed with. The same PC is
 * reachable at its Wi-Fi address and at its Tailscale address; both used to be saved as two
 * servers. Pure functions over JSON and URLs so they can be unit-tested (see ServerIdentityTest).
 */
object ServerIdentity {

    private const val STRONG = "S:"
    private const val WEAK = "W:"

    /**
     * A fingerprint of the machine behind a server, from what it reports; "" when there is nothing
     * to go on.
     *
     * Strong: a llama.cpp-style `/props` that names the model file (`model_path`, a path on that
     * machine's disk) together with the model alias. Strata and a single-model llama-server do.
     * Weak: only the list of model ids (and the build, when `/props` has one), e.g. LM Studio, Ollama,
     * llama.cpp in router mode. Two different machines can offer the same models, so a weak match
     * is only ever a question to the user, never merged silently.
     */
    fun fingerprint(props: JSONObject?, modelIds: List<String>): String {
        if (props != null && ServerContexts.isLlamaProps(props)) {
            val path = props.optString("model_path").trim()
            if (path.isNotEmpty() && !path.equals("none", ignoreCase = true)) {
                return STRONG + path + "|" + props.optString("model_alias")
            }
        }
        val ids = modelIds.filter { it.isNotBlank() }.distinct().sorted()
        if (ids.isEmpty()) return ""
        val build = props?.optString("build_info").orEmpty()
        return WEAK + build + "|" + ids.joinToString("|")
    }

    /** True for a fingerprint that identifies one machine, so it is safe to suggest merging in bulk. */
    fun isStrong(fingerprint: String): Boolean = fingerprint.startsWith(STRONG)

    /** 0 = the local network, 1 = Tailscale, 2 = anything else (a public name, an unknown address). */
    fun addressRank(url: String): Int {
        val host = hostOnly(url).lowercase()
        if (host.endsWith(".ts.net")) return 1
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return when {
                a == 100 && b in 64..127 -> 1                          // 100.64.0.0/10: Tailscale / CGNAT
                a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) -> 0
                a == 169 && b == 254 -> 0
                else -> 2
            }
        }
        return if (host.endsWith(".local") || !host.contains('.')) 0 else 2
    }

    /**
     * The addresses of one server in the order to try them: the local network first (shorter path,
     * works without the VPN), then Tailscale, then the rest, each group keeping the saved order.
     */
    fun orderAddresses(urls: List<String>): List<String> = urls.sortedBy { addressRank(it) }

    /** "192.168.1.12:8080 · Wi-Fi" style label for an address; the kind is left out for "other". */
    fun describeAddress(url: String, wifi: String, tailscale: String): String {
        val host = url.substringAfter("://").ifEmpty { url }
        return when (addressRank(url)) {
            0 -> "$host · $wifi"
            1 -> "$host · $tailscale"
            else -> host
        }
    }

    private fun hostOnly(url: String): String {
        val withoutScheme = url.substringAfter("://").ifEmpty { url }
        return withoutScheme.substringBefore('/').substringBefore(':')
    }
}
