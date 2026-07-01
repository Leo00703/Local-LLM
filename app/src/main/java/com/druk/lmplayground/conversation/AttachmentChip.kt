@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

/** "1234" -> "1.2k" for a compact token count; small numbers stay as-is. */
private fun formatTokens(n: Int): String =
    if (n >= 1000) "%.1fk".format(n / 1000f) else n.toString()

/**
 * A chip for an attached file: filename + a token-cost subtitle (or a spinner
 * while extracting, or an error). Long-press shows a tooltip above it with the
 * full filename and token count (the chip itself ellipsizes). Used as the
 * removable staged chip above the composer ([onRemove] set) and read-only on a
 * sent user message.
 */
@Composable
fun AttachmentChip(
    filename: String,
    modifier: Modifier = Modifier,
    tokenCount: Int? = null,
    extracting: Boolean = false,
    truncated: Boolean = false,
    errorText: String? = null,
    mime: String? = null,
    previewText: String? = null,
    previewRaw: String? = null,
    previewPdfPath: String? = null,
    onRemove: (() -> Unit)? = null,
) {
    var showPreview by remember { mutableStateOf(false) }
    val canPreview = (previewText != null || previewPdfPath != null) && !extracting && errorText == null
    // An image-only PDF (pages but no extractable text) shows a label, not a token count.
    val imageOnly = previewPdfPath != null && previewText.isNullOrBlank()
    val tokenText = tokenCount?.let { formatTokens(it) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(
                    buildString {
                        append(filename)
                        if (tokenText != null) {
                            append('\n')
                            append(stringResource(R.string.attachment_token_count, tokenText))
                        }
                    }
                )
            }
        },
        state = rememberTooltipState(isPersistent = false),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = modifier.then(
                if (canPreview) Modifier.clickable { showPreview = true } else Modifier
            ),
        ) {
            Row(
                modifier = Modifier.padding(
                    start = 10.dp,
                    end = if (onRemove != null) 4.dp else 10.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    extracting -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    errorText != null -> Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    else -> Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.widthIn(max = 220.dp)) {
                    Text(
                        text = filename,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = when {
                        extracting -> stringResource(R.string.attachment_reading)
                        errorText != null -> errorText
                        imageOnly -> stringResource(R.string.attachment_image_only)
                        tokenText != null -> {
                            val tokens = stringResource(R.string.attachment_token_count, tokenText)
                            if (truncated) {
                                tokens + " · " + stringResource(R.string.attachment_truncated_label)
                            } else {
                                tokens
                            }
                        }
                        else -> null
                    }
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (errorText != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (onRemove != null) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.remove_attachment),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
    if (showPreview) {
        FilePreviewDialog(
            filename = filename,
            mime = mime,
            text = previewText ?: "",
            rawText = previewRaw,
            pdfPath = previewPdfPath,
            onDismiss = { showPreview = false },
        )
    }
}
