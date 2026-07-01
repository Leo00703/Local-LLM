package com.druk.lmplayground.files

import android.net.Uri

/** What an attachment is, which decides how it reaches the model. */
enum class AttachmentKind { DOCUMENT, IMAGE }

/**
 * A file attached to a sent message. For a DOCUMENT, [extractedText] is the
 * file's text injected into the model prompt (see ConversationViewModel.
 * buildModelPrompt); the user-visible message content is unaffected. IMAGE is
 * reserved for the future vision pipeline (pixels), which leaves [extractedText]
 * blank and routes by [kind] instead.
 */
data class Attachment(
    val name: String,
    val mime: String?,
    val kind: AttachmentKind,
    val extractedText: String,
    val charCount: Int,
    val truncated: Boolean,
    /**
     * Original source text for the preview's "raw" view, when it differs from
     * [extractedText] — i.e. the raw HTML for an HTML file (extractedText is the
     * Markdown the model receives). Null for plain text (raw == extractedText).
     */
    val rawText: String? = null,
    /**
     * Absolute path to an app-private copy of the source file, kept so the file
     * can be re-rendered later (the visual PDF page preview). Only set for PDFs;
     * null otherwise. Cleaned up by the ViewModel's orphan sweep.
     */
    val localPath: String? = null,
)

/**
 * A file the user has picked but not yet sent (a removable chip). Its text is
 * extracted at PICK time (not send) so the chip can show the token cost and the
 * preview before sending. [id] identifies it within the staged list.
 */
data class StagedAttachment(
    val id: Long,
    val uri: Uri,
    val filename: String,
    val mimeType: String?,
    val kind: AttachmentKind = AttachmentKind.DOCUMENT,
    val state: StagedState = StagedState.Extracting,
)

/** Extraction state of a [StagedAttachment]. */
sealed interface StagedState {
    /** Reading the file (a spinner on the chip). */
    data object Extracting : StagedState
    /** Text ready; [charCount] drives the token estimate shown on the chip. [rawText] = original source for HTML. */
    data class Ready(
        val text: String,
        val charCount: Int,
        val truncated: Boolean,
        val rawText: String? = null,
        /** App-private copy of the source (PDF) for the visual preview; null otherwise. */
        val localPath: String? = null,
    ) : StagedState
    /** Couldn't read it (unsupported / empty / failure) — shown on the chip, skipped on send. */
    data class Error(val message: String) : StagedState
}
