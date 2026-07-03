package com.druk.lmplayground.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Decodes a picked gallery image and re-encodes it as a bounded JPEG for the
 * native vision pipeline. The transcode is mandatory, not an optimization:
 * stb_image (which mtmd uses to decode the staged bytes) can't read HEIC —
 * the Xiaomi camera default — and a full-resolution photo would both OOM the
 * native decode and overflow the ~1 MB binder transaction that carries the
 * bytes to the :llama process. The quality ladder retries smaller until the
 * encoded image fits [MAX_BYTES]. ImageDecoder applies EXIF rotation itself,
 * so portrait photos reach the model upright.
 */
object ImageTranscoder {

    /** Hard ceiling for the encoded bytes (binder budget headroom). */
    const val MAX_BYTES = 600 * 1024

    /** (longest edge px, JPEG quality) attempts, best first. */
    private val LADDER = listOf(1024 to 85, 896 to 75, 768 to 60, 512 to 50)

    fun transcode(context: Context, uri: Uri): ByteArray? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            for ((maxEdge, quality) in LADDER) {
                val bitmap = decodeScaled(source, maxEdge) ?: return null
                val out = ByteArrayOutputStream()
                val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bitmap.recycle()
                if (!ok) return null
                val bytes = out.toByteArray()
                if (bytes.size <= MAX_BYTES) return bytes
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    private fun decodeScaled(source: ImageDecoder.Source, maxEdge: Int): Bitmap? {
        return try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Software allocation: Bitmap.compress rejects HARDWARE bitmaps.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val w = info.size.width
                val h = info.size.height
                val longest = maxOf(w, h)
                if (longest > maxEdge) {
                    val scale = maxEdge.toFloat() / longest
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } catch (t: Throwable) {
            null
        }
    }
}
