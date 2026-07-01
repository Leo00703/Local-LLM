package com.druk.lmplayground.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

private data class ChangelogEntry(val version: String, val changes: List<String>)

// Newest first. User-facing release notes (kept short, English).
private val CHANGELOG = listOf(
    ChangelogEntry("1.9.18", listOf(
        "Preview PDFs as real pages, not just text — with a Pages ⇄ Text toggle.",
        "Scanned or image-only PDFs now preview too (pages shown even without text).",
    )),
    ChangelogEntry("1.9.17", listOf(
        "Attach PDF files — their text is extracted and sent to the model.",
    )),
    ChangelogEntry("1.9.16", listOf(
        "HTML preview now shows the whole page, including sections that reveal on scroll.",
    )),
    ChangelogEntry("1.9.15", listOf(
        "File preview card is now much larger for easier reading.",
        "HTML files preview correctly, and switching to the Raw view no longer freezes the app.",
    )),
    ChangelogEntry("1.9.14", listOf(
        "The context-usage ring now reflects each chat when you switch between them.",
        "Tap an attached file to preview it, with a Raw ⇄ Formatted toggle (rendered page for HTML).",
    )),
    ChangelogEntry("1.9.13", listOf(
        "Attach several files at once; long-press a file chip to see its full name and token cost.",
        "Roomier message box.",
    )),
    ChangelogEntry("1.9.12", listOf(
        "Attach text and HTML files — their contents are extracted and sent along with your message.",
    )),
    ChangelogEntry("1.9.11", listOf(
        "Settings reorganised into clearer sections.",
        "The live reasoning tail now stays aligned and can be expanded while the model writes.",
    )),
    ChangelogEntry("1.9.10", listOf(
        "New Advanced settings screen, plus stability and picker fixes.",
    )),
    ChangelogEntry("1.9.9", listOf(
        "In-app changelog — tap the version number a few times to open this list.",
        "Optional current date in the system prompt (toggle in Settings).",
    )),
    ChangelogEntry("1.9.8", listOf(
        "App logo on the welcome screen now follows your chosen accent colour.",
        "A shimmer plus a live peek of the model's reasoning while it works.",
        "Custom GGUF models with junk metadata now show a clean name from the filename.",
        "Rose and red theme colours are now clearly distinct.",
    )),
    ChangelogEntry("1.9.7", listOf(
        "Theme colour picker polish: aligned grid, gradient System swatch, added Red and Grey.",
    )),
    ChangelogEntry("1.9.6", listOf(
        "Choose the app's accent colour: System (Material You) plus several presets.",
    )),
    ChangelogEntry("1.9.5", listOf(
        "More provider logos and richer Ollama model details (capability badges).",
        "Favicon cluster and a live reasoning label in the process card.",
    )),
    ChangelogEntry("1.9.4", listOf(
        "Live process card during generation, with tappable web sources.",
        "Added Ernie (Baidu) and Ornith provider logos.",
    )),
    ChangelogEntry("1.9.3", listOf(
        "Multi-step agent turns collapse into one tidy, inspectable card.",
    )),
    ChangelogEntry("1.9.2", listOf(
        "Fixed reasoning text showing dark code boxes.",
    )),
    ChangelogEntry("1.9.1", listOf(
        "Web search, web fetch and JavaScript tools now work over remote servers.",
    )),
    ChangelogEntry("1.9.0", listOf(
        "Rich Markdown answers: code cards, tables and math.",
        "Provider logos and model details in the top bar; frosted UI throughout.",
    )),
)

/** The version-tap easter egg: a scrollable list of every release and its changes. */
@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.changelog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                CHANGELOG.forEachIndexed { i, entry ->
                    if (i > 0) Spacer(Modifier.height(16.dp))
                    Text(
                        text = "v${entry.version}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    entry.changes.forEach { change ->
                        Text(
                            text = "•  $change",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
