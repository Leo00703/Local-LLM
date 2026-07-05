package com.druk.lmplayground.tools

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Focused encyclopedic lookup: searches Wikipedia and returns the best matching
 * article's intro summary, title and URL in a single API call
 * (generator=search + extracts). More reliable than a general web search for
 * well-known topics. Network, no key, no permission.
 */
class WikipediaTool : Tool {
    override val name = "wikipedia"
    override val description = "Look up a topic on Wikipedia and return a concise summary (the article intro), its title and URL. Prefer this over a general web search for well-known factual or encyclopedic topics."
    override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"The topic to look up, e.g. \"Adreno GPU\""},"lang":{"type":"string","description":"Wikipedia language code (default \"en\")"}},"required":["query"]}"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun cancelInFlight() {
        client.dispatcher.cancelAll()
    }

    override fun execute(arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val query = args.getString("query").trim()
            if (query.isEmpty()) return errorJson("Empty query")
            val lang = args.optString("lang", "en").trim().lowercase()
                .filter { it.isLetter() || it == '-' }.ifEmpty { "en" }

            val base = "https://$lang.wikipedia.org/w/api.php".toHttpUrlOrNull()
                ?: return errorJson("Invalid language '$lang'")
            val url = base.newBuilder()
                .addQueryParameter("format", "json")
                .addQueryParameter("action", "query")
                .addQueryParameter("generator", "search")
                .addQueryParameter("gsrsearch", query)
                .addQueryParameter("gsrlimit", "1")
                .addQueryParameter("prop", "extracts|info")
                .addQueryParameter("exintro", "1")
                .addQueryParameter("explaintext", "1")
                .addQueryParameter("inprop", "url")
                .addQueryParameter("redirects", "1")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return errorJson("Empty response")

            val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages")
                ?: return notFoundJson(query)
            val firstKey = pages.keys().asSequence().firstOrNull() ?: return notFoundJson(query)
            val page = pages.optJSONObject(firstKey) ?: return notFoundJson(query)

            var extract = page.optString("extract").trim()
            if (extract.isEmpty()) return notFoundJson(query)
            if (extract.length > MAX_CHARS) extract = extract.substring(0, MAX_CHARS).trimEnd() + "..."

            JSONObject()
                .put("title", page.optString("title"))
                .put("summary", extract)
                .put("url", page.optString("fullurl"))
                .toString()
        } catch (e: Exception) {
            errorJson(e.message ?: "Wikipedia lookup failed")
        }
    }

    private fun notFoundJson(query: String) =
        """{"result":null,"query":"${query.replace("\"", "'")}","message":"No Wikipedia article found"}"""

    private fun errorJson(message: String) = """{"error":"${message.replace("\"", "'")}"}"""

    companion object {
        private const val MAX_CHARS = 1500
        private const val USER_AGENT = "LocalLLM-Android/1.0 (github.com/Leo00703/Local-LLM)"
    }
}
