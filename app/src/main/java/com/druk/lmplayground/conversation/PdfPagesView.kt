package com.druk.lmplayground.conversation

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// PdfRenderer isn't thread-safe and only one page may be open at a time; a single
// mutex serialises every renderer access across all pages of all previews.
private val pdfMutex = Mutex()

/** Base render width. Higher gives crisper pinch-zoom; capped to spare the LLM's memory. */
private const val MAX_PAGE_WIDTH_PX = 1440

/**
 * Rendered pages kept per preview. A page is ~10 MB at the base width, so only the last few
 * are held: enough that scrolling back a little does not render again.
 */
private const val MAX_CACHED_PAGES = 4

/** Clamp a pan offset to the zoomed page's edges; zero when not zoomed. */
private fun clampPan(raw: Offset, scale: Float, size: IntSize): Offset {
    if (scale <= 1f) return Offset.Zero
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
}

/**
 * One open [PdfRenderer] for the lifetime of a preview, plus a few rendered pages. It used
 * to be opened (which parses the whole file) and closed again for every single page, so a
 * long PDF paid that on each page and scrolling back re-rendered everything.
 *
 * Not thread-safe by itself: every call is made while holding [pdfMutex].
 */
private class PdfDoc private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    val pageCount: Int = renderer.pageCount

    // Access-ordered: the eldest entry is the page used least recently.
    private val pages = object : LinkedHashMap<Int, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>): Boolean =
            size > MAX_CACHED_PAGES
    }

    /** Page [index] fit to [widthPx], from the cache when it is there; null on any failure. */
    fun render(index: Int, widthPx: Int): Bitmap? {
        pages[index]?.let { if (it.width == widthPx) return it }
        if (index < 0 || index >= pageCount) return null
        return try {
            val page = renderer.openPage(index)
            try {
                val scale = widthPx.toFloat() / page.width
                val heightPx = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pages[index] = bmp
                bmp
            } finally {
                // PdfRenderer/Page only implement AutoCloseable from API 35, so close()
                // explicitly (it exists since API 21) rather than relying on use{}.
                page.close()
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun close() {
        pages.clear()
        try { renderer.close() } catch (_: Throwable) {}
        try { pfd.close() } catch (_: Throwable) {}
    }

    companion object {
        /** Opens [path], or null when it is missing or not a readable PDF. */
        fun open(path: String): PdfDoc? {
            val file = File(path)
            if (!file.exists()) return null
            val pfd = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (t: Throwable) {
                return null
            }
            return try {
                PdfDoc(pfd, PdfRenderer(pfd))
            } catch (t: Throwable) {
                try { pfd.close() } catch (_: Throwable) {}
                null
            }
        }
    }
}

private sealed interface PdfLoad {
    object Loading : PdfLoad
    object Failed : PdfLoad
    class Ready(val doc: PdfDoc) : PdfLoad
}

/**
 * Scrollable visual preview of a PDF: each page is rendered to a bitmap on demand
 * (lazily, one at a time) and shown fit-to-width. Reads from the app-private copy
 * at [path]; if the file is gone or unreadable it shows a short message.
 */
@Composable
fun PdfPagesView(path: String, modifier: Modifier = Modifier) {
    val load by produceState<PdfLoad>(PdfLoad.Loading, path) {
        val doc = withContext(Dispatchers.IO) { pdfMutex.withLock { PdfDoc.open(path) } }
        value = if (doc != null) PdfLoad.Ready(doc) else PdfLoad.Failed
        awaitDispose {
            // Closing waits for any render still in flight (same mutex) and is file I/O,
            // so it is not done on the composition thread.
            if (doc != null) {
                CoroutineScope(Dispatchers.IO).launch { pdfMutex.withLock { doc.close() } }
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val widthPx = constraints.maxWidth.coerceIn(1, MAX_PAGE_WIDTH_PX)
        when (val state = load) {
            is PdfLoad.Ready -> {
                // Pinch (2+ fingers) zooms/pans the pages; a single finger still
                // scrolls the list. Reset the pan once back to 1x.
                var scale by remember(path) { mutableStateOf(1f) }
                var offset by remember(path) { mutableStateOf(Offset.Zero) }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var pressed = true
                                while (pressed) {
                                    val event = awaitPointerEvent()
                                    val twoFingers = event.changes.size >= 2
                                    if (twoFingers) {
                                        scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                    }
                                    // Pinch zooms; while zoomed a SINGLE finger pans (and we consume
                                    // it so the list doesn't scroll). At 1x a single finger is left
                                    // alone so the LazyColumn scrolls between pages.
                                    if (twoFingers || scale > 1f) {
                                        offset = clampPan(offset + event.calculatePan(), scale, size)
                                        event.changes.forEach { it.consume() }
                                    }
                                    pressed = event.changes.any { it.pressed }
                                }
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                ) {
                    items(count = state.doc.pageCount, key = { it }) { index ->
                        PdfPageImage(state.doc, index, widthPx)
                    }
                }
            }
            PdfLoad.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.pdf_preview_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PdfLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun PdfPageImage(doc: PdfDoc, index: Int, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(null, doc, index, widthPx) {
        value = withContext(Dispatchers.IO) { pdfMutex.withLock { doc.render(index, widthPx) } }
    }
    val b = bitmap
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().height(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}
