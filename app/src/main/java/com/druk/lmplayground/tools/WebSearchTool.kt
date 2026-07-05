package com.druk.lmplayground.tools

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class WebSearchTool(private val linkStore: WebLinkStore? = null) : Tool {
    override val name = "web_search"
    override val description = "Search the web and return results with titles, snippets, and a compact reference for each result. Optionally scope by region (e.g. \"it-it\") or recency (day/week/month/year). Pass a result's \"ref\" to web_fetch to read that page."
    override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"The search query"},"max_results":{"type":"integer","description":"Maximum number of results to return (default 5, max 10)"},"region":{"type":"string","description":"Optional region/locale, e.g. \"us-en\", \"it-it\", or \"wt-wt\" for no region"},"recency":{"type":"string","description":"Optional recency filter: day, week, month, or year"}},"required":["query"]}"""

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
            val query = args.getString("query")
            val maxResults = args.optInt("max_results", 5).coerceIn(1, 10)
            // Optional DuckDuckGo scoping: kl = region/locale, df = time filter.
            val region = args.optString("region").trim().lowercase()
            val df = when (args.optString("recency").trim().lowercase()) {
                "day", "d" -> "d"
                "week", "w" -> "w"
                "month", "m" -> "m"
                "year", "y" -> "y"
                else -> ""
            }

            val urlBuilder = "https://html.duckduckgo.com/html/"
                .toHttpUrlOrNull()!!
                .newBuilder()
                .addQueryParameter("q", query)
            if (region.isNotEmpty()) urlBuilder.addQueryParameter("kl", region)
            if (df.isNotEmpty()) urlBuilder.addQueryParameter("df", df)
            val url = urlBuilder.build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return errorJson("Empty response")

            val doc = Jsoup.parse(html)
            val results = JSONArray()

            for (element in doc.select(".result.results_links")) {
                if (results.length() >= maxResults) break
                val titleEl = element.selectFirst(".result__title a") ?: continue
                val snippetEl = element.selectFirst(".result__snippet")
                val title = titleEl.text().trim()
                val href = titleEl.attr("href")
                val snippet = snippetEl?.text()?.trim() ?: ""

                // DuckDuckGo wraps URLs in a redirect — extract the actual URL
                val actualUrl = extractDdgUrl(href)
                if (title.isEmpty() || actualUrl.isEmpty()) continue

                val obj = JSONObject()
                obj.put("title", title)
                // Short host (e.g. "nasdaq.com") — useful to the model as a
                // source label, and lets the chat UI show the source domain
                // even though the full URL is hidden behind a compact ref.
                hostOf(actualUrl).takeIf { it.isNotEmpty() }?.let { obj.put("domain", it) }
                if (linkStore != null) {
                    // Hand the model a compact reference instead of the full
                    // URL to save context tokens; web_fetch resolves it back.
                    obj.put("ref", linkStore.reference(actualUrl))
                } else {
                    obj.put("url", actualUrl)
                }
                obj.put("snippet", snippet)
                results.put(obj)
            }

            if (results.length() == 0) {
                """{"results":[],"query":"$query","message":"No results found"}"""
            } else {
                val wrapper = JSONObject()
                wrapper.put("results", results)
                wrapper.put("query", query)
                wrapper.toString()
            }
        } catch (e: Exception) {
            errorJson(e.message ?: "Search failed")
        }
    }

    /** Bare host without the leading "www.", or "" if the URL can't be parsed. */
    private fun hostOf(url: String): String = try {
        (java.net.URI(url).host ?: "").removePrefix("www.")
    } catch (_: Exception) {
        ""
    }

    private fun extractDdgUrl(href: String): String {
        // DuckDuckGo links look like //duckduckgo.com/l/?uddg=<encoded_url>&...
        if (href.contains("uddg=")) {
            val encoded = href.substringAfter("uddg=").substringBefore("&")
            return java.net.URLDecoder.decode(encoded, "UTF-8")
        }
        // Direct link
        if (href.startsWith("http")) return href
        if (href.startsWith("//")) return "https:$href"
        return href
    }

    private fun errorJson(message: String): String {
        return """{"error":"${message.replace("\"", "'")}"}"""
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
