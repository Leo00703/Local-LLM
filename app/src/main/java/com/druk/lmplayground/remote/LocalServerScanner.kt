package com.druk.lmplayground.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * A reachable OpenAI-compatible server found on the local network.
 *
 * @param url   base URL, e.g. `http://192.168.1.42:1234` (no trailing slash)
 * @param label short human label, e.g. `192.168.1.42:1234`
 * @param modelCount number of models the server advertised via `/v1/models`
 * @param firstModel id of the first advertised model, or null
 * @param serverType detected server software: "LM Studio", "Ollama", or "OpenAI"
 */
data class FoundServer(
    val url: String,
    val label: String,
    val modelCount: Int,
    val firstModel: String?,
    val serverType: String,
)

/**
 * Discovers OpenAI-compatible inference servers on the phone's WiFi subnet by
 * actively probing the well-known ports (LM Studio 1234, Ollama 11434) with a
 * short-timeout `GET /v1/models`. mDNS/NSD is not used because neither LM
 * Studio nor Ollama advertise themselves over Bonjour by default.
 *
 * Pure Kotlin + OkHttp; safe to call from a coroutine. Returns the servers
 * that answered, sorted by address.
 */
class LocalServerScanner {

    private val client = OkHttpClient.Builder()
        .connectTimeout(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Probe every host on the device's /24 on the known ports. Returns the
     * reachable OpenAI-compatible servers, or an empty list when none answer
     * (or the device isn't on a private IPv4 network).
     */
    suspend fun scan(): List<FoundServer> = withContext(Dispatchers.IO) {
        val base = subnetBase() ?: return@withContext emptyList()
        val limit = Semaphore(MAX_CONCURRENT)
        coroutineScope {
            val probes = buildList {
                for (host in 1..254) {
                    val ip = "$base.$host"
                    for (port in KNOWN_PORTS) {
                        add(async { limit.withPermit { probe(ip, port) } })
                    }
                }
            }
            probes.awaitAll().filterNotNull().sortedBy { it.label }
        }
    }

    private fun probe(ip: String, port: Int): FoundServer? {
        val url = "http://$ip:$port"
        return try {
            val request = Request.Builder().url("$url/v1/models").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val data = JSONObject(body).optJSONArray("data") ?: return null
                val first = if (data.length() > 0) {
                    data.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
                } else null
                FoundServer(
                    url = url,
                    label = "$ip:$port",
                    modelCount = data.length(),
                    firstModel = first,
                    serverType = detectType(ip, port),
                )
            }
        } catch (_: Exception) {
            // Connection refused / timeout / non-JSON — not a server here.
            null
        }
    }

    /**
     * Best-effort identification of the server software, only run for hosts
     * that already answered /v1/models (so just a handful). Probes the
     * vendor-specific endpoints, falling back to the well-known port.
     */
    private fun detectType(ip: String, port: Int): String {
        val host = "http://$ip:$port"
        return when {
            getOk("$host/api/tags") -> "Ollama"                       // Ollama native
            getOk("$host/api/v0/models") || getOk("$host/api/v1/models") -> "LM Studio"
            port == 11434 -> "Ollama"
            port == 1234 -> "LM Studio"
            else -> "OpenAI"
        }
    }

    private fun getOk(url: String): Boolean = try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    /** First 3 octets of the device's site-local IPv4, e.g. "192.168.1", or null. */
    private fun subnetBase(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
                ?.substringBeforeLast('.')
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine local subnet", e)
            null
        }
    }

    private companion object {
        const val TAG = "LocalServerScanner"
        const val SCAN_TIMEOUT_MS = 600L
        const val MAX_CONCURRENT = 64
        val KNOWN_PORTS = intArrayOf(1234, 11434)
    }
}
