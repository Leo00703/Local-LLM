package com.druk.lmplayground.files

import android.content.Context
import android.net.Uri
import com.druk.lmplayground.tools.HtmlToMarkdown
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.Reader

/** Outcome of trying to read a picked file's text. */
sealed interface FileExtractionResult {
    data class Success(
        val text: String,
        val charCount: Int,
        val truncated: Boolean,
        val name: String,
        /** Original source for the preview's raw view (raw HTML); null = same as [text]. */
        val rawText: String? = null,
    ) : FileExtractionResult
    /** Opened fine but produced no usable text (e.g. an empty or image-only file). */
    data class Empty(val reason: String, val name: String) : FileExtractionResult
    /** A type we don't extract (yet) — e.g. PDF before the PDF build, or Office docs. */
    data class Unsupported(val mime: String?, val name: String) : FileExtractionResult
    data class Failure(val message: String) : FileExtractionResult
}

/**
 * Extracts plain text from a picked file so it can be injected into the model
 * prompt. Pure Kotlin, off the main thread, never throws (errors map to a
 * [FileExtractionResult]). This build handles plain text (txt/md/code/csv/json/
 * xml/yaml…), HTML (via the existing [HtmlToMarkdown]) and PDF (text, via pdfbox).
 * The output is capped to bound memory; the caller applies a further
 * context-window-aware truncation at send time.
 */
object FileTextExtractor {

    private const val MAX_TEXT_CHARS = 1_000_000

    /** PdfBox needs a one-time resource-loader init before the first document load. */
    @Volatile
    private var pdfBoxReady = false

    suspend fun extract(
        context: Context,
        uri: Uri,
        displayName: String,
        mime: String?,
    ): FileExtractionResult = withContext(Dispatchers.IO) {
        try {
            val effectiveMime = mime ?: context.contentResolver.getType(uri)
            val ext = displayName.substringAfterLast('.', "").lowercase()
            when {
                isPdf(effectiveMime, ext) -> extractPdf(context, uri, displayName)
                isHtml(effectiveMime, ext) -> extractHtml(context, uri, displayName)
                isPlainText(effectiveMime, ext) -> extractPlainText(context, uri, displayName)
                else -> FileExtractionResult.Unsupported(effectiveMime, displayName)
            }
        } catch (t: Throwable) {
            FileExtractionResult.Failure(t.message ?: "read error")
        }
    }

    private fun isPdf(mime: String?, ext: String) =
        mime == "application/pdf" || ext == "pdf"

    private fun isHtml(mime: String?, ext: String) =
        mime == "text/html" || mime == "application/xhtml+xml" || ext == "html" || ext == "htm"

    private fun isPlainText(mime: String?, ext: String): Boolean {
        if (mime != null && (mime.startsWith("text/") || mime == "application/json" || mime == "application/xml")) {
            return true
        }
        return ext in TEXT_EXTENSIONS
    }

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log",
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "c", "cpp", "cc", "h", "hpp",
        "cs", "go", "rs", "rb", "php", "swift", "sh", "bash", "sql", "css", "ini",
        "toml", "gradle", "properties", "conf", "env",
    )

    private fun extractPlainText(context: Context, uri: Uri, name: String): FileExtractionResult {
        val (text, truncated) = context.contentResolver.openInputStream(uri)?.use { input ->
            readCapped(input.bufferedReader(Charsets.UTF_8))
        } ?: return FileExtractionResult.Failure("cannot open file")
        return finalize(stripBom(text), truncated, name)
    }

    private fun ensurePdfBoxInit(context: Context) {
        if (!pdfBoxReady) {
            // Idempotent; the flag just avoids re-running the resource scan each time.
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxReady = true
        }
    }

    private fun extractPdf(context: Context, uri: Uri, name: String): FileExtractionResult {
        ensurePdfBoxInit(context)
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            try {
                PDDocument.load(input).use { doc ->
                    PDFTextStripper().getText(doc)
                }
            } catch (e: InvalidPasswordException) {
                return FileExtractionResult.Failure("password-protected PDF")
            }
        } ?: return FileExtractionResult.Failure("cannot open file")
        // finalize() maps blank text (scanned / image-only PDFs, no OCR) to Empty.
        return finalize(text, alreadyTruncated = false, name = name)
    }

    private fun extractHtml(context: Context, uri: Uri, name: String): FileExtractionResult {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return FileExtractionResult.Failure("cannot open file")
        // Jsoup auto-detects the charset from the document; null lets it decide.
        val doc = Jsoup.parse(bytes.inputStream(), null, "")
        val md = HtmlToMarkdown.convert(doc)
        // Keep the raw HTML source for the preview, decoded with the charset Jsoup
        // detected (not a hardcoded UTF-8) so non-UTF-8 pages don't mojibake.
        val charset = doc.charset() ?: Charsets.UTF_8
        val rawHtml = bytes.toString(charset).let {
            if (it.length > MAX_TEXT_CHARS) it.take(MAX_TEXT_CHARS) else it
        }
        return finalize(md, md.length > MAX_TEXT_CHARS, name, rawText = rawHtml)
    }

    /** Read up to [MAX_TEXT_CHARS] so a huge file can't OOM the read. */
    private fun readCapped(reader: Reader): Pair<String, Boolean> {
        val buf = CharArray(8192)
        val sb = StringBuilder()
        var truncated = false
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            if (sb.length + n > MAX_TEXT_CHARS) {
                sb.append(buf, 0, MAX_TEXT_CHARS - sb.length)
                truncated = true
                break
            }
            sb.append(buf, 0, n)
        }
        return sb.toString() to truncated
    }

    private fun stripBom(s: String) = if (s.startsWith('﻿')) s.substring(1) else s

    private fun finalize(
        text: String,
        alreadyTruncated: Boolean,
        name: String,
        rawText: String? = null,
    ): FileExtractionResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return FileExtractionResult.Empty("no extractable text", name)
        val capped = if (trimmed.length > MAX_TEXT_CHARS) trimmed.take(MAX_TEXT_CHARS) else trimmed
        return FileExtractionResult.Success(
            text = capped,
            charCount = capped.length,
            truncated = alreadyTruncated || capped.length < trimmed.length,
            name = name,
            rawText = rawText,
        )
    }
}
