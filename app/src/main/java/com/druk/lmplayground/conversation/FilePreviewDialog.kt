@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.conversation

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.druk.lmplayground.R

/** Preview render cap — enough to skim, small enough to lay out instantly. */
private const val PREVIEW_TEXT_CAP = 20_000

/**
 * Injected after the HTML preview loads. Many single-file "sites" hide their
 * sections until scrolled into view (AOS-style: start at opacity:0, revealed by a
 * script). With the network blocked that script may never load — or its scroll
 * observer may not fire inside a dialog WebView — leaving whole sections blank
 * even though their background shows.
 *
 * To reveal them WITHOUT touching intentional design, this only bumps elements
 * that are (near) fully transparent — the tell-tale of reveal-on-scroll content —
 * up to opacity 1, so a partly-transparent hero scrim (e.g. opacity 0.4) is left
 * exactly as it is. Known reveal hooks ([data-aos], .reveal/.fade/… classes) also
 * get their hiding transform/animation neutralised. Wrapped in try/catch.
 */
private const val REVEAL_HIDDEN_CONTENT_JS =
    """(function(){try{
if(document.body){document.body.style.setProperty('opacity','1','important');document.body.style.setProperty('overflow','visible','important');}
if(document.documentElement){document.documentElement.style.setProperty('opacity','1','important');}
var all=document.querySelectorAll('body *');
for(var i=0;i<all.length;i++){if(parseFloat(getComputedStyle(all[i]).opacity)<0.1){all[i].style.setProperty('opacity','1','important');}}
var hooks=document.querySelectorAll('[data-aos],[data-sr],[data-scroll],[data-reveal],[data-animate],[class*="reveal"],[class*="fade"],[class*="animate"],[class*="slide"],[class*="appear"],[class*="scroll"]');
for(var j=0;j<hooks.length;j++){var h=hooks[j];var hs=h.style;if(parseFloat(getComputedStyle(h).opacity)<0.1){hs.setProperty('opacity','1','important');}hs.setProperty('visibility','visible','important');hs.setProperty('transform','none','important');hs.setProperty('transition','none','important');hs.setProperty('animation','none','important');}
window.dispatchEvent(new Event('scroll'));
}catch(e){}})();"""

/**
 * A large centered preview of an attached file. A Raw ⇄ Formatted toggle (shown
 * for HTML and Markdown) switches between the source text and a rendered view:
 * HTML → an offline WebView (network + navigation blocked, so nothing leaves the
 * device), Markdown → the app's Markdown renderer. Plain text / code show as
 * monospace source.
 *
 * The raw/monospace and Markdown views are capped to [PREVIEW_TEXT_CAP] characters
 * so a very large file (e.g. a single-file website's raw HTML, up to ~1M chars)
 * can't freeze the UI thread while Compose lays the text out. The WebView renders
 * the full HTML natively, which stays fast.
 *
 * @param text the model-facing text (Markdown for HTML, the file text otherwise)
 * @param rawText the original source for the raw view (raw HTML); null = use [text]
 */
@Composable
fun FilePreviewDialog(
    filename: String,
    mime: String?,
    text: String,
    rawText: String?,
    pdfPath: String?,
    onDismiss: () -> Unit,
) {
    val ext = filename.substringAfterLast('.', "").lowercase()
    val isHtml = mime == "text/html" || ext == "html" || ext == "htm"
    val isMd = mime == "text/markdown" || ext == "md" || ext == "markdown"
    val isPdf = mime == "application/pdf" || ext == "pdf"
    val isXlsx = mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
        ext == "xlsx" || ext == "xlsm"
    val isCsv = mime == "text/csv" || ext == "csv" || ext == "tsv"
    val isGrid = isXlsx || isCsv
    val gridUsesTab = isXlsx || ext == "tsv"
    // PDFs preview as rendered pages; the text tab appears only when there is text.
    val hasToggle = isHtml || isMd || isGrid || (isPdf && pdfPath != null && text.isNotBlank())
    val formattedLabel = when {
        isPdf -> R.string.preview_pages
        isGrid -> R.string.preview_table
        else -> R.string.preview_formatted
    }
    val rawLabel = if (isPdf) R.string.preview_text else R.string.preview_raw
    var showRaw by remember { mutableStateOf(false) }
    val rawSource = rawText ?: text

    Dialog(
        onDismissRequest = onDismiss,
        // Opt out of the platform's narrow default width so the card can fill most
        // of the screen in both dimensions for a comfortable preview.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = filename,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.close),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (hasToggle) {
                    Spacer(Modifier.size(8.dp))
                    Row {
                        FilterChip(
                            selected = !showRaw,
                            onClick = { showRaw = false },
                            label = { Text(stringResource(formattedLabel)) },
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = showRaw,
                            onClick = { showRaw = true },
                            label = { Text(stringResource(rawLabel)) },
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        isPdf && pdfPath != null && !showRaw -> PdfPagesView(pdfPath)
                        isGrid && !showRaw -> FileGridView(text, gridUsesTab)
                        showRaw || (!isHtml && !isMd && !isPdf) -> RawTextView(rawSource)
                        isHtml -> AndroidView(
                            factory = { ctx -> buildPreviewWebView(ctx, rawSource) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        isPdf -> RawTextView(rawSource)
                        else -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            val (shown, truncated) = capForPreview(text)
                            MarkdownContent(text = shown, primary = false)
                            if (truncated) TruncationNote()
                        }
                    }
                }
            }
        }
    }
}

/** Scrollable, selectable monospace source, capped to keep the UI thread responsive. */
@Composable
private fun RawTextView(source: String) {
    val (shown, truncated) = capForPreview(source)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SelectionContainer {
            Text(
                text = shown,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (truncated) TruncationNote()
    }
}

/** A parsed row of the grid preview. */
private data class GridRow(val cells: List<String>, val isSection: Boolean, val isHeader: Boolean)

private const val GRID_MAX_ROWS = 400

/**
 * Renders CSV/TSV/XLSX extracted text as a scrollable table. XLSX text carries
 * "Sheet N" section lines + tab-separated rows; CSV/TSV are delimiter-separated.
 * The first data row of each section is styled as a header. Bounded to
 * [GRID_MAX_ROWS] rows to keep layout responsive.
 */
@Composable
private fun FileGridView(text: String, useTab: Boolean) {
    val rows = remember(text, useTab) { parseGrid(text, useTab) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState()),
    ) {
        for (row in rows) {
            if (row.isSection) {
                Text(
                    text = row.cells.firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            } else {
                Row {
                    for (cell in row.cells) {
                        Text(
                            text = cell,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (row.isHeader) FontWeight.Bold else FontWeight.Normal,
                            ),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .widthIn(min = 56.dp, max = 220.dp)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        if (rows.size >= GRID_MAX_ROWS) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.preview_grid_truncated, GRID_MAX_ROWS.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun parseGrid(text: String, useTab: Boolean): List<GridRow> {
    val out = ArrayList<GridRow>()
    var seenDataInSection = false
    for (line in text.split('\n')) {
        if (out.size >= GRID_MAX_ROWS) break
        if (line.isBlank()) continue
        if (Regex("^Sheet \\d+$").matches(line.trim())) {
            out.add(GridRow(listOf(line.trim()), isSection = true, isHeader = false))
            seenDataInSection = false
            continue
        }
        val cells = if (useTab) line.split('\t') else parseCsvLine(line)
        out.add(GridRow(cells, isSection = false, isHeader = !seenDataInSection))
        seenDataInSection = true
    }
    return out
}

/** Split one CSV line, honouring "quoted, fields" and "" escapes. */
private fun parseCsvLine(line: String): List<String> {
    val cells = ArrayList<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i += 2 }
            c == '"' -> { inQuotes = !inQuotes; i++ }
            c == ',' && !inQuotes -> { cells.add(sb.toString()); sb.clear(); i++ }
            else -> { sb.append(c); i++ }
        }
    }
    cells.add(sb.toString())
    return cells
}

@Composable
private fun TruncationNote() {
    Spacer(Modifier.size(8.dp))
    Text(
        text = stringResource(R.string.preview_truncated_note, "%,d".format(PREVIEW_TEXT_CAP)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Cap at [PREVIEW_TEXT_CAP] chars; returns the (possibly shortened) text and whether it was cut. */
private fun capForPreview(s: String): Pair<String, Boolean> =
    if (s.length > PREVIEW_TEXT_CAP) s.take(PREVIEW_TEXT_CAP) to true else s to false

/**
 * An offline WebView for the HTML preview. JavaScript is enabled so real pages
 * lay out and render (otherwise JS-driven sites show up half-empty), but it is
 * sandboxed: no network, no file/content access, and all navigation is blocked,
 * so nothing the page contains can reach out or leave the device.
 * [WebSettings.useWideViewPort] + [WebSettings.loadWithOverviewMode] fit a
 * desktop-width page to the card instead of clipping it.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun buildPreviewWebView(ctx: Context, html: String): WebView =
    WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.blockNetworkLoads = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            // Keep the preview inert: never follow links or navigate away.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = true

            // Reveal scroll-hidden sections so the whole page previews (see above).
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(REVEAL_HIDDEN_CONTENT_JS, null)
            }
        }
        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
