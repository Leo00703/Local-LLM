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

/** A file the user has picked but not yet sent (shown as a removable chip). */
data class StagedAttachment(
    val uri: Uri,
    val filename: String,
    val mimeType: String?,
    val kind: AttachmentKind = AttachmentKind.DOCUMENT,
)
