@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.conversation

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.druk.lmplayground.R

/**
 * A centered preview of an attached file. A Raw ⇄ Formatted toggle (shown for
 * HTML and Markdown) switches between the source text and a rendered view:
 * HTML → an offline WebView (JS + network disabled), Markdown → the app's
 * Markdown renderer. Plain text / code show as monospace source.
 *
 * @param text the model-facing text (Markdown for HTML, the file text otherwise)
 * @param rawText the original source for the raw view (raw HTML); null = use [text]
 */
@Composable
fun FilePreviewDialog(
    filename: String,
    mime: String?,
    text: String,
    rawText: String?,
    onDismiss: () -> Unit,
) {
    val ext = filename.substringAfterLast('.', "").lowercase()
    val isHtml = mime == "text/html" || ext == "html" || ext == "htm"
    val isMd = mime == "text/markdown" || ext == "md" || ext == "markdown"
    val hasToggle = isHtml || isMd
    var showRaw by remember { mutableStateOf(false) }
    val rawSource = rawText ?: text

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = filename,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.close),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (hasToggle) {
                    Spacer(Modifier.size(8.dp))
                    Row {
                        FilterChip(
                            selected = !showRaw,
                            onClick = { showRaw = false },
                            label = { Text(stringResource(R.string.preview_formatted)) },
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = showRaw,
                            onClick = { showRaw = true },
                            label = { Text(stringResource(R.string.preview_raw)) },
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        showRaw || (!isHtml && !isMd) -> SelectionContainer {
                            Text(
                                text = rawSource,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        isHtml -> AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = false
                                    settings.blockNetworkLoads = true
                                    loadDataWithBaseURL(null, rawSource, "text/html", "utf-8", null)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            MarkdownContent(text = text, primary = false)
                        }
                    }
                }
            }
        }
    }
}
