package com.druk.lmplayground.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

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

    // clip resizes the image (aspect preserved) so its pixel count ≈
    // tokens × ALIGN², where ALIGN = patch_size × pooling for the projector.
    // 42 = 14 × 3 for the Gemma vision tower (the on-device vision model).
    private const val MODEL_ALIGN = 42

    /**
     * Render a copy of [srcPath] downscaled to the resolution the vision model
     * actually received — i.e. what clip produced for [tokens] tokens (aspect
     * preserved). Returns the new file path (a sibling "<name>_mv.jpg"), or null
     * if no downscale was needed or on failure. Used to show the true, lower-res
     * image the model "saw" in the chat. Call off the main thread.
     */
    fun renderModelView(srcPath: String, tokens: Int): String? {
        if (tokens <= 0) return null
        return try {
            val src = File(srcPath)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(srcPath, bounds)
            val w = bounds.outWidth; val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            val targetPixels = tokens.toLong() * MODEL_ALIGN * MODEL_ALIGN
            val scale = sqrt(targetPixels.toDouble() / (w.toLong() * h))
            if (scale >= 1.0) return null // model saw it at (near) full transcode size
            val tw = (w * scale).toInt().coerceAtLeast(1)
            val th = (h * scale).toInt().coerceAtLeast(1)
            val bmp = BitmapFactory.decodeFile(srcPath) ?: return null
            val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
            if (scaled !== bmp) bmp.recycle()
            val out = File(src.parentFile, src.nameWithoutExtension + "_mv.jpg")
            val ok = FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            scaled.recycle()
            if (ok) out.absolutePath else null
        } catch (t: Throwable) {
            null
        }
    }
}
