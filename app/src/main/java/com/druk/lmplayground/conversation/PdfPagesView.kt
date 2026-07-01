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
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import kotlinx.coroutines.Dispatchers
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
 * Scrollable visual preview of a PDF: each page is rendered to a bitmap on demand
 * (lazily, one at a time) and shown fit-to-width. Reads from the app-private copy
 * at [path]; if the file is gone or unreadable it shows a short message.
 */
@Composable
fun PdfPagesView(path: String, modifier: Modifier = Modifier) {
    val pageCount by produceState(0, path) {
        value = withContext(Dispatchers.IO) { pdfMutex.withLock { pdfPageCount(path) } }
    }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val widthPx = constraints.maxWidth.coerceIn(1, MAX_PAGE_WIDTH_PX)
        when {
            pageCount > 0 -> {
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
                                    if (event.changes.size >= 2) {
                                        scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                        offset = if (scale > 1f) {
                                            // Pan horizontally only, clamped to the page edges;
                                            // vertical stays with the list scroll so nothing is lost.
                                            val maxX = size.width * (scale - 1f) / 2f
                                            Offset((offset.x + event.calculatePan().x).coerceIn(-maxX, maxX), 0f)
                                        } else {
                                            Offset.Zero
                                        }
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
                    items(pageCount) { index -> PdfPageImage(path, index, widthPx) }
                }
            }
            pageCount < 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.pdf_preview_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun PdfPageImage(path: String, index: Int, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(null, path, index, widthPx) {
        value = withContext(Dispatchers.IO) { pdfMutex.withLock { renderPdfPage(path, index, widthPx) } }
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

// PdfRenderer/Page only implement AutoCloseable from API 35, so close() explicitly
// (it exists since API 21) rather than relying on Kotlin's use{} extension.

/** Page count, or -1 if the file can't be opened as a PDF. Caller holds [pdfMutex]. */
private fun pdfPageCount(path: String): Int {
    val renderer = openRenderer(path) ?: return -1
    return try {
        renderer.pageCount
    } catch (t: Throwable) {
        -1
    } finally {
        renderer.close()
    }
}

/** Render one page fit to [widthPx], or null on any failure. Caller holds [pdfMutex]. */
private fun renderPdfPage(path: String, index: Int, widthPx: Int): Bitmap? {
    val renderer = openRenderer(path) ?: return null
    try {
        if (index >= renderer.pageCount) return null
        val page = renderer.openPage(index)
        try {
            val scale = widthPx.toFloat() / page.width
            val heightPx = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    } catch (t: Throwable) {
        return null
    } finally {
        renderer.close()
    }
}

private fun openRenderer(path: String): PdfRenderer? {
    val file = File(path)
    if (!file.exists()) return null
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    return try {
        PdfRenderer(pfd)
    } catch (t: Throwable) {
        pfd.close()
        null
    }
}
