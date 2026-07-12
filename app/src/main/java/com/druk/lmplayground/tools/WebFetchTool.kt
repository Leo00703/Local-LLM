package com.druk.lmplayground.tools

import android.content.Context
import android.net.Uri
import com.druk.lmplayground.files.FileExtractionResult
import com.druk.lmplayground.files.FileTextExtractor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.TimeUnit

class WebFetchTool(
    private val linkStore: WebLinkStore? = null,
    private val context: Context? = null,
) : Tool {
    override val name = "web_fetch"
    override val description = "Fetch a web page and return its main content as markdown. Headings, lists, links and code blocks are preserved. Also reads linked PDF and Office documents (PDF/DOCX/XLSX/PPTX/ODT/RTF/EPUB) as text."
    override val parametersSchema = """{"type":"object","properties":{"url":{"type":"string","description":"The URL to fetch, or a reference from web_search results such as \"ddg:3\""},"max_length":{"type":"integer","description":"Maximum content length in characters (default 5000)"}},"required":["url"]}"""

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
            val rawUrl = args.getString("url").trim()
            // A web_search result reference (e.g. "ddg:3") maps back to the real
            // URL we stored at search time. If it looks like a reference but
            // isn't known, tell the model to search first rather than fetching
            // a bogus host.
            val resolved = linkStore?.resolve(rawUrl)
            if (resolved == null && linkStore?.isReference(rawUrl) == true) {
                return errorJson("Unknown search reference '$rawUrl'. Call web_search first, then fetch a returned ref.")
            }
            val target = resolved ?: rawUrl
            val url = if (!target.startsWith("http")) "https://$target" else target
            val maxLength = args.optInt("max_length", 5000).coerceIn(50, 20000)

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val mediaType = response.body?.contentType()
            val contentType = (response.header("Content-Type") ?: mediaType?.toString() ?: "").lowercase()
            // Read the body once as bytes (so binary documents can be decoded), but cap
            // it so a huge or hostile response can't OOM the app.
            val body = response.body ?: return errorJson("Empty response")
            if (body.contentLength() > MAX_FETCH_BYTES) {
                body.close()
                return errorJson("Response too large")
            }
            val bytes = body.byteStream().use { readCapped(it, MAX_FETCH_BYTES.toInt()) }

            val result = JSONObject().put("url", url)

            // PDF / Office documents: extract text with the same pipeline as file
            // attachments, so a link to a document returns readable text.
            if (context != null && looksLikeDocument(contentType, url)) {
                val text = extractDocument(context!!, bytes, contentType, url)
                if (!text.isNullOrBlank()) {
                    val trimmed = if (text.length > maxLength) text.substring(0, maxLength) + "..." else text
                    return result.put("title", "").put("content", trimmed).put("length", trimmed.length).toString()
                }
                // extraction failed / empty -> fall through to raw handling
            }

            val charset = mediaType?.charset() ?: Charsets.UTF_8
            val body = String(bytes, charset)
            if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                // Pass `url` as baseUri so Jsoup resolves relative links to absolute.
                val doc = Jsoup.parse(body, url)
                val markdown = HtmlToMarkdown.convert(doc)
                val truncated = if (markdown.length > maxLength) markdown.substring(0, maxLength) + "..." else markdown
                result.put("title", doc.title())
                result.put("content", truncated)
                result.put("length", truncated.length)
            } else {
                val truncated = if (body.length > maxLength) body.substring(0, maxLength) + "..." else body
                result.put("title", "")
                result.put("content", truncated)
                result.put("length", truncated.length)
            }
            result.toString()
        } catch (e: Exception) {
            errorJson(e.message ?: "Fetch failed")
        }
    }

    private fun looksLikeDocument(contentType: String, url: String): Boolean {
        if (contentType.contains("application/pdf") ||
            contentType.contains("officedocument") ||
            contentType.contains("opendocument") ||
            contentType.contains("application/rtf") || contentType.contains("text/rtf") ||
            contentType.contains("epub")
        ) return true
        val path = url.substringBefore('?').lowercase()
        return DOC_EXTS.any { path.endsWith(it) }
    }

    /** Stage the bytes as a cache file (named with the right extension for type
     *  detection), run the shared extractor, then clean up. Null on failure/empty. */
    private fun extractDocument(context: Context, bytes: ByteArray, contentType: String, url: String): String? {
        val ext = guessExtension(contentType, url)
        val dir = File(context.cacheDir, "webfetch").apply { mkdirs() }
        val file = File(dir, "doc_${System.currentTimeMillis()}.$ext")
        return try {
            file.writeBytes(bytes)
            val result = runBlocking {
                FileTextExtractor.extract(
                    context,
                    Uri.fromFile(file),
                    file.name,
                    contentType.substringBefore(';').trim().ifEmpty { null },
                )
            }
            (result as? FileExtractionResult.Success)?.text
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun guessExtension(contentType: String, url: String): String = when {
        contentType.contains("application/pdf") -> "pdf"
        contentType.contains("wordprocessingml") -> "docx"
        contentType.contains("spreadsheetml") -> "xlsx"
        contentType.contains("presentationml") -> "pptx"
        contentType.contains("opendocument.text") -> "odt"
        contentType.contains("application/rtf") || contentType.contains("text/rtf") -> "rtf"
        contentType.contains("epub") -> "epub"
        else -> url.substringBefore('?').substringAfterLast('.', "bin").lowercase()
            .takeIf { it.length in 1..5 } ?: "bin"
    }

    // JSONObject escapes quotes/backslashes/newlines/control chars in the message.
    private fun errorJson(message: String): String =
        JSONObject().put("error", message).toString()

    /** Read at most [cap] bytes from [input] into memory; bounds OOM on hostile responses. */
    private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var remaining = cap
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size, remaining))
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    companion object {
        private val DOC_EXTS = listOf(".pdf", ".docx", ".xlsx", ".pptx", ".odt", ".rtf", ".epub")
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        // Cap on the web_fetch response bytes held in memory (docs decode from these).
        private const val MAX_FETCH_BYTES = 16L * 1024 * 1024
    }
}
