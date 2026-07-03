package com.druk.lmplayground.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.druk.lmplayground.R
import com.druk.lmplayground.files.AttachmentKind
import java.io.File

private val UserBubbleShape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)

@Composable
fun Message(
    msg: Message,
    isUserMe: Boolean,
    showActions: Boolean = true,
    showStats: Boolean = true,
    canRegenerate: Boolean = false,
    onRegenerate: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onTokenCountClicked: (() -> Unit)? = null
) {
    if (isUserMe) {
        // Tap a sent image to open it full-screen (like the doc/PDF preview).
        var previewImagePath by remember { mutableStateOf<String?>(null) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 60.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Files the user attached to this turn (their text was fed to the model).
            msg.attachments.forEach { att ->
                if (att.kind == AttachmentKind.IMAGE) {
                    // Sent image: a rounded thumbnail above the bubble. The file is
                    // the app-private JPEG copy; if it was cleaned up (deleted chat
                    // restored from edit history etc.) Coil just renders nothing.
                    att.localPath?.let { path ->
                        AsyncImage(
                            model = File(path),
                            contentDescription = stringResource(R.string.attached_image),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .widthIn(max = 220.dp)
                                .heightIn(max = 280.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { previewImagePath = path }
                        )
                    }
                } else {
                    AttachmentChip(
                        filename = att.name,
                        tokenCount = estimateTokens(att.extractedText),
                        truncated = att.truncated,
                        mime = att.mime,
                        previewText = att.extractedText,
                        previewRaw = att.rawText,
                        previewPdfPath = att.localPath,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = UserBubbleShape
            ) {
                val styledMessage = messageFormatter(
                    text = msg.content,
                    primary = true
                )
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimary) {
                    SelectionContainer {
                        Text(
                            text = styledMessage,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
            if (showActions) {
                Spacer(modifier = Modifier.height(2.dp))
                UserMessageActions(content = msg.content, onEdit = onEdit)
            }
        }
        previewImagePath?.let { p ->
            ImagePreviewDialog(path = p, onDismiss = { previewImagePath = null })
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
        ) {
            ChatItemBubble(
                message = msg,
                showActions = showActions,
                showStats = showStats,
                canRegenerate = canRegenerate,
                onRegenerate = onRegenerate,
                onTokenCountClicked = onTokenCountClicked
            )
        }
    }
}

/** Copy + (optional) Edit actions shown beneath a user message bubble. */
@Composable
private fun UserMessageActions(
    content: String,
    onEdit: (() -> Unit)?
) {
    val clipboardManager = LocalClipboardManager.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { clipboardManager.setText(AnnotatedString(content)) },
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(id = R.string.copy),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        }
        if (onEdit != null) {
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(id = R.string.edit),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
