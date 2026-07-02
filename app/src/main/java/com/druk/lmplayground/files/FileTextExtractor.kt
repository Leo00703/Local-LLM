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
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.Reader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

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
    /** A type we don't extract — e.g. legacy binary .doc/.xls/.ppt, or archives. */
    data class Unsupported(val mime: String?, val name: String) : FileExtractionResult
    data class Failure(val message: String) : FileExtractionResult
}

/**
 * Extracts plain text from a picked file so it can be injected into the model
 * prompt. Pure Kotlin, off the main thread, never throws (errors map to a
 * [FileExtractionResult]). This build handles plain text (txt/md/code/csv/json/
 * xml/yaml…), HTML (via the existing [HtmlToMarkdown]), PDF (text, via pdfbox)
 * and Office Open XML (DOCX/XLSX/PPTX, unzipped and parsed with jsoup).
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
                isDocx(effectiveMime, ext) -> extractDocx(context, uri, displayName)
                isXlsx(effectiveMime, ext) -> extractXlsx(context, uri, displayName)
                isPptx(effectiveMime, ext) -> extractPptx(context, uri, displayName)
                isOdt(effectiveMime, ext) -> extractOdt(context, uri, displayName)
                isEpub(effectiveMime, ext) -> extractEpub(context, uri, displayName)
                isRtf(effectiveMime, ext) -> extractRtf(context, uri, displayName)
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

    private fun isDocx(mime: String?, ext: String) =
        mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            mime == "application/vnd.ms-word.document.macroEnabled.12" ||
            ext == "docx" || ext == "docm"

    private fun isXlsx(mime: String?, ext: String) =
        mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
            mime == "application/vnd.ms-excel.sheet.macroEnabled.12" ||
            ext == "xlsx" || ext == "xlsm"

    private fun isPptx(mime: String?, ext: String) =
        mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ||
            mime == "application/vnd.ms-powerpoint.presentation.macroEnabled.12" ||
            ext == "pptx" || ext == "pptm"

    private fun isOdt(mime: String?, ext: String) =
        mime == "application/vnd.oasis.opendocument.text" ||
            mime == "application/vnd.oasis.opendocument.spreadsheet" ||
            mime == "application/vnd.oasis.opendocument.presentation" ||
            ext == "odt" || ext == "ods" || ext == "odp"

    private fun isEpub(mime: String?, ext: String) =
        mime == "application/epub+zip" || ext == "epub"

    // NB: must be tested before isPlainText — text/rtf matches its text/ prefix.
    private fun isRtf(mime: String?, ext: String) =
        mime == "application/rtf" || mime == "text/rtf" || ext == "rtf"

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

    /** Read just the named entries out of an OOXML (zip) container in one streaming pass. */
    private fun readZipEntries(
        context: Context,
        uri: Uri,
        keep: (String) -> Boolean,
    ): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var e: ZipEntry? = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory && keep(e.name)) out[e.name] = zip.readBytes()
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        }
        return out
    }

    // OOXML tags are namespaced (w:t, a:t); the XML parser preserves them (the HTML
    // parser would lower-case and mangle them). wholeText() keeps xml:space runs.
    private fun parseOoxml(bytes: ByteArray) =
        Jsoup.parse(bytes.inputStream(), "UTF-8", "", Parser.xmlParser())

    /** Natural-sort key for slideN.xml / sheetN.xml (zip entry order is undefined). */
    private fun naturalIndex(name: String): Int =
        Regex("(\\d+)\\.xml$").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private fun extractDocx(context: Context, uri: Uri, name: String): FileExtractionResult {
        val bytes = readZipEntries(context, uri) { it == "word/document.xml" }["word/document.xml"]
            ?: return FileExtractionResult.Failure("unreadable or protected Office file")
        val sb = StringBuilder()
        for (p in parseOoxml(bytes).getElementsByTag("w:p")) {
            // Skip paragraphs nested in another (text boxes / shapes): the outer
            // paragraph's walk below already includes their text once.
            if (p.parents().any { it.tagName() == "w:p" }) continue
            for (node in p.getAllElements()) {
                when (node.tagName()) {
                    "w:t" -> sb.append(node.wholeText())
                    "w:tab" -> sb.append('\t')
                    "w:br", "w:cr" -> sb.append('\n')
                }
            }
            sb.append('\n')
            if (sb.length > MAX_TEXT_CHARS) break
        }
        return finalize(sb.toString(), sb.length > MAX_TEXT_CHARS, name)
    }

    private fun extractPptx(context: Context, uri: Uri, name: String): FileExtractionResult {
        val slides = readZipEntries(context, uri) {
            it.startsWith("ppt/slides/slide") && it.endsWith(".xml")
        }
        if (slides.isEmpty()) return FileExtractionResult.Failure("unreadable or protected Office file")
        val sb = StringBuilder()
        for ((_, bytes) in slides.entries.sortedBy { naturalIndex(it.key) }) {
            for (para in parseOoxml(bytes).getElementsByTag("a:p")) {
                for (t in para.getElementsByTag("a:t")) sb.append(t.wholeText())
                sb.append('\n')
            }
            sb.append('\n')
            if (sb.length > MAX_TEXT_CHARS) break
        }
        return finalize(sb.toString(), sb.length > MAX_TEXT_CHARS, name)
    }

    private fun extractXlsx(context: Context, uri: Uri, name: String): FileExtractionResult {
        val entries = readZipEntries(context, uri) {
            it == "xl/sharedStrings.xml" ||
                (it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml"))
        }
        val sheets = entries.filterKeys { it.startsWith("xl/worksheets/sheet") }
        if (sheets.isEmpty()) return FileExtractionResult.Failure("unreadable or protected Office file")
        // Shared strings are a 0-based table cells reference by index.
        val shared = entries["xl/sharedStrings.xml"]?.let { bytes ->
            parseOoxml(bytes).getElementsByTag("si").map { si ->
                si.getElementsByTag("t").joinToString("") { it.wholeText() }
            }
        } ?: emptyList()
        val sb = StringBuilder()
        var sheetNo = 0
        for ((_, bytes) in sheets.entries.sortedBy { naturalIndex(it.key) }) {
            sheetNo++
            if (sheetNo > 1) sb.append('\n')
            sb.append("Sheet ").append(sheetNo).append('\n')
            for (row in parseOoxml(bytes).getElementsByTag("row")) {
                // Excel omits empty cells, so place each cell at the column its
                // r="A1" reference encodes and pad the gaps, else columns misalign.
                val byCol = HashMap<Int, String>()
                var maxCol = -1
                for (c in row.getElementsByTag("c")) {
                    val col = c.attr("r").takeIf { it.isNotEmpty() }
                        ?.let { columnIndex(it) } ?: (maxCol + 1)
                    if (col >= 0) {
                        byCol[col] = cellText(c, shared)
                        if (col > maxCol) maxCol = col
                    }
                }
                sb.append((0..maxCol).joinToString("\t") { byCol[it].orEmpty() })
                sb.append('\n')
                if (sb.length > MAX_TEXT_CHARS) break
            }
            if (sb.length > MAX_TEXT_CHARS) break
        }
        return finalize(sb.toString(), sb.length > MAX_TEXT_CHARS, name)
    }

    /** 0-based column index from a cell reference like "C2" → 2, "AA1" → 26. */
    private fun columnIndex(ref: String): Int {
        var idx = 0
        for (ch in ref) {
            val u = ch.uppercaseChar()
            if (u !in 'A'..'Z') break
            idx = idx * 26 + (u - 'A' + 1)
        }
        return idx - 1
    }

    /** A cell's display text: shared-string lookup, inline string, or the literal value. */
    private fun cellText(c: Element, shared: List<String>): String = when (c.attr("t")) {
        "s" -> {
            val i = c.getElementsByTag("v").firstOrNull()?.wholeText()?.trim()?.toIntOrNull()
            if (i != null) shared.getOrNull(i).orEmpty() else ""
        }
        "inlineStr" -> c.getElementsByTag("t").joinToString("") { it.wholeText() }
        else -> c.getElementsByTag("v").firstOrNull()?.wholeText()?.trim().orEmpty()
    }

    private fun extractOdt(context: Context, uri: Uri, name: String): FileExtractionResult {
        val bytes = readZipEntries(context, uri) { it == "content.xml" }["content.xml"]
            ?: return FileExtractionResult.Failure("unreadable or protected OpenDocument file")
        val doc = parseOoxml(bytes)
        val sb = StringBuilder()
        // Paragraphs + headings in document order; skip blocks nested in another
        // (avoid double-counting, like DOCX's nested-w:p guard). wholeText() already
        // includes the text:span runs.
        for (block in doc.getAllElements()) {
            val tag = block.tagName()
            if (tag != "text:p" && tag != "text:h") continue
            if (block.parents().any { it.tagName() == "text:p" || it.tagName() == "text:h" }) continue
            sb.append(block.wholeText()).append('\n')
            if (sb.length > MAX_TEXT_CHARS) break
        }
        return finalize(sb.toString(), sb.length > MAX_TEXT_CHARS, name)
    }

    private fun extractEpub(context: Context, uri: Uri, name: String): FileExtractionResult {
        val entries = readZipEntries(context, uri) {
            it == "META-INF/container.xml" || it.endsWith(".opf") ||
                it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm")
        }
        val container = entries["META-INF/container.xml"]
            ?: return FileExtractionResult.Failure("unreadable EPUB (no container.xml)")
        val opfPath = parseOoxml(container).getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
            ?: return FileExtractionResult.Failure("unreadable EPUB (no OPF)")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = entries[opfPath]?.let { parseOoxml(it) }
            ?: return FileExtractionResult.Failure("unreadable EPUB (missing OPF)")
        val hrefById = opf.getElementsByTag("item").associate { it.attr("id") to it.attr("href") }
        val order = opf.getElementsByTag("itemref").map { it.attr("idref") }
        val sb = StringBuilder()
        for (idref in order) {
            val href = hrefById[idref] ?: continue
            val key = (if (opfDir.isEmpty()) href else "$opfDir/$href").substringBefore('#')
            val chapter = entries[key] ?: entries[href] ?: continue
            val md = HtmlToMarkdown.convert(Jsoup.parse(chapter.inputStream(), null, ""))
            if (md.isNotBlank()) sb.append(md).append("\n\n")
            if (sb.length > MAX_TEXT_CHARS) break
        }
        return finalize(sb.toString(), sb.length > MAX_TEXT_CHARS, name)
    }

    private fun extractRtf(context: Context, uri: Uri, name: String): FileExtractionResult {
        // Read as ISO-8859-1 so \'hh byte escapes survive (UTF-8 would corrupt them).
        val raw = context.contentResolver.openInputStream(uri)?.use {
            readCapped(it.bufferedReader(Charsets.ISO_8859_1))
        }?.first ?: return FileExtractionResult.Failure("cannot open file")
        return finalize(stripRtf(raw), alreadyTruncated = false, name = name)
    }

    /** Minimal RTF → text: drop control words, metadata groups, and escapes. */
    private fun stripRtf(rtf: String): String {
        val out = StringBuilder(rtf.length / 2)
        val skipDest = setOf(
            "fonttbl", "colortbl", "stylesheet", "info", "pict", "object",
            "themedata", "colorschememapping", "datastore", "generator",
            "listtable", "listoverridetable", "rsidtbl", "*",
        )
        var i = 0
        var depth = 0
        var skipToDepth = -1
        while (i < rtf.length) {
            val ch = rtf[i]
            when {
                ch == '{' -> { depth++; i++ }
                ch == '}' -> {
                    if (skipToDepth >= 0 && depth <= skipToDepth) skipToDepth = -1
                    depth--; i++
                }
                ch == '\\' && i + 1 < rtf.length -> {
                    val next = rtf[i + 1]
                    when {
                        next == '\'' && i + 3 < rtf.length -> {
                            val code = rtf.substring(i + 2, i + 4).toIntOrNull(16)
                            if (skipToDepth < 0 && code != null) out.append(cp1252(code))
                            i += 4
                        }
                        next == 'u' && i + 2 < rtf.length && (rtf[i + 2].isDigit() || rtf[i + 2] == '-') -> {
                            var j = i + 2
                            if (rtf[j] == '-') j++
                            while (j < rtf.length && rtf[j].isDigit()) j++
                            val n = rtf.substring(i + 2, j).toIntOrNull()
                            if (skipToDepth < 0 && n != null) out.append(n.toChar())
                            if (j < rtf.length && rtf[j] != '\\' && rtf[j] != '{' && rtf[j] != '}') j++
                            i = j
                        }
                        next == '\\' || next == '{' || next == '}' -> {
                            if (skipToDepth < 0) out.append(next); i += 2
                        }
                        next.isLetter() || next == '*' -> {
                            var j = i + 1
                            if (rtf[j] == '*') j++
                            val wordStart = j
                            while (j < rtf.length && rtf[j].isLetter()) j++
                            val word = rtf.substring(wordStart, j)
                            if (j < rtf.length && (rtf[j] == '-' || rtf[j].isDigit())) {
                                if (rtf[j] == '-') j++
                                while (j < rtf.length && rtf[j].isDigit()) j++
                            }
                            if (j < rtf.length && rtf[j] == ' ') j++
                            if (skipToDepth < 0) when (word) {
                                "par", "line", "sect", "page" -> out.append('\n')
                                "tab" -> out.append('\t')
                            }
                            if (word in skipDest && skipToDepth < 0) skipToDepth = depth
                            i = j
                        }
                        else -> i += 2
                    }
                }
                ch == '\r' || ch == '\n' -> i++
                else -> { if (skipToDepth < 0) out.append(ch); i++ }
            }
        }
        return out.toString()
    }

    /** Map the common Windows-1252 high bytes (smart quotes/dashes); else Latin-1. */
    private fun cp1252(code: Int): Char = when (code) {
        0x82 -> '‚'; 0x84 -> '„'; 0x85 -> '…'
        0x91 -> '‘'; 0x92 -> '’'; 0x93 -> '“'; 0x94 -> '”'
        0x95 -> '•'; 0x96 -> '–'; 0x97 -> '—'
        else -> code.toChar()
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
