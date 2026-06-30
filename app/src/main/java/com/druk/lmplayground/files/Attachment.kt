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
    /** Text ready; [charCount] drives the token estimate shown on the chip. */
    data class Ready(val text: String, val charCount: Int, val truncated: Boolean) : StagedState
    /** Couldn't read it (unsupported / empty / failure) — shown on the chip, skipped on send. */
    data class Error(val message: String) : StagedState
}
