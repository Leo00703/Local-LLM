package com.druk.lmplayground.models

import android.net.Uri
import androidx.annotation.DrawableRes
import com.druk.lmplayground.storage.StoragePreferences
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Static model definition - does not contain download status.
 * Chat template parameters (prefix, suffix, stop sequences) are read
 * from the GGUF file's embedded Jinja template at load time.
 */
data class ModelInfo(
    val name: String,
    val filename: String,
    val remoteUri: Uri? = null,
    val releaseDate: LocalDate? = null,
    val description: String,
    @param:DrawableRes val logoRes: Int = 0,
    val supportedLanguages: List<String> = emptyList(),
    // Capability hints shown as badges in the model list. These are best-effort
    // static values for the catalog; the authoritative source is the GGUF's
    // chat template, read only after the model loads. Once a model has been
    // loaded, its detected capabilities are cached and override these via
    // [resolveCapabilities].
    val supportsTools: Boolean = false,
    val supportsThinking: Boolean = false,
    // The bare filename of a paired multimodal projector (mmproj) sitting next
    // to this model in the storage folder, if one was found. When set, the model
    // can accept images once loaded (the projector is attached at load time).
    val mmprojFilename: String? = null,
) {
    val isCustom: Boolean get() = remoteUri == null
    val isVision: Boolean get() = mmprojFilename != null
}

fun ModelInfo.supportsLanguage(lang: String): Boolean =
    supportedLanguages.isEmpty() || lang in supportedLanguages

/**
 * Overlay this model's static capability flags with the real capabilities
 * detected from its chat template the first time it was loaded (cached in
 * [StoragePreferences]). Returns the model unchanged if it has never been
 * loaded. This lets badges self-correct and lets custom/imported models gain
 * badges after their first run.
 */
fun ModelInfo.resolveCapabilities(prefs: StoragePreferences): ModelInfo {
    val detected = prefs.getDetectedCaps(filename) ?: return this
    return copy(supportsTools = detected.first, supportsThinking = detected.second)
}

data class ModelWithStatus(
    val model: ModelInfo,
    val isDownloaded: Boolean,
)

private val RELEASE_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

fun ModelInfo.releaseDateLabel(): String = releaseDate?.format(RELEASE_DATE_FORMATTER) ?: ""
