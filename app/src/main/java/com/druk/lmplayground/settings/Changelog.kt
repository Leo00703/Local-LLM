package com.druk.lmplayground.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.druk.lmplayground.R

/**
 * How to add a release note (keep the changelog looking maintained):
 *  1. Prepend a [ChangelogEntry] to [CHANGELOG] (newest first).
 *  2. Give it a short [ChangelogEntry.title], the theme of the release.
 *  3. List each user-facing change as a [Change] tagged with the right
 *     [ChangeType] (New feature / Improvement / Fix / Design). The tag drives
 *     the emoji shown to the user, so entries stay consistent build over build.
 *  Notes are user-facing: short, plain English, no internal jargon.
 */
private enum class ChangeType(val emoji: String) {
    NEW("✨"),        // brand-new capability
    IMPROVED("⚡"),   // existing feature made better / faster / more accurate
    FIX("🐛"),        // bug fix
    DESIGN("🎨"),     // visual / UX polish
}

private data class Change(val type: ChangeType, val text: String)

private data class ChangelogEntry(
    val version: String,
    val title: String,
    val changes: List<Change>,
)

// Newest first.
private val CHANGELOG = listOf(
    ChangelogEntry("1.9.55", "Location tool, tidier attach menu", listOf(
        Change(ChangeType.NEW, "New Location tool (turn it on in Settings, then Tools): the model can get your device's approximate location (city level) when it asks, for example \"what city am I in?\". It asks for the location permission when you enable it, only shares an approximate position, and stays off by default."),
        Change(ChangeType.DESIGN, "The attach menu is now a compact box on the left, sitting just above the input bar, instead of stretching the full screen width."),
    )),
    ChangelogEntry("1.9.54", "Attach menu hugs the bar, smarter web tools", listOf(
        Change(ChangeType.DESIGN, "The attach menu (Document, Image, Take photo) now opens right above the input bar instead of floating up into the chat."),
        Change(ChangeType.IMPROVED, "Web search can now be scoped by region and recency (day, week, month or year). And Fetch web page now reads linked PDF and Office documents (PDF, DOCX, XLSX, PPTX, ODT, RTF, EPUB) as text, not just web pages."),
    )),
    ChangelogEntry("1.9.53", "Tidier tools list, plus a Wikipedia tool", listOf(
        Change(ChangeType.NEW, "New Wikipedia tool (turn it on in Settings, then Tools): the model can look up a topic and get a concise summary with the article title and link, more reliable than a general web search for well-known facts."),
        Change(ChangeType.IMPROVED, "In the model's Tools tab, every tool now shows a proper name, description and icon; the tools added in the last update previously showed only their raw internal name there. Both tool screens now share the same presentation."),
    )),
    ChangelogEntry("1.9.52", "New tools: calculator, unit converter, date & time, device info", listOf(
        Change(ChangeType.NEW, "Four new tools the model can use (turn them on in Settings, then Tools): a Calculator for exact math so there are no more arithmetic slips, a Unit converter (length, mass, temperature and more), Current date & time (live, so time-sensitive answers are correct instead of using a frozen date), and Device info (your phone's model, RAM, storage and battery, so the model can reason about what runs on your device). Each is off by default; Device info only reads device specs, no location or personal data."),
        Change(ChangeType.IMPROVED, "The attach menu (Document, Image, Take photo) now opens closer to the input bar."),
    )),
    ChangelogEntry("1.9.51", "Bigger context by default, provider logos on your models", listOf(
        Change(ChangeType.IMPROVED, "On-device models now open with a much larger context window by default, sized to safely fit your phone's memory (up to the model's trained limit). Long documents you attach are far less likely to be cut off. You can still change the context size in the model settings, and if something is still too big to fit, the app now tells you it will be trimmed."),
        Change(ChangeType.IMPROVED, "Your sideloaded (custom) models now show the right provider logo when the name is recognizable (for example a Gemma model shows the Google logo) instead of the generic placeholder."),
    )),
    ChangelogEntry("1.9.50", "Take a photo to attach", listOf(
        Change(ChangeType.NEW, "The attach menu on a vision model now has a \"Take photo\" option that opens the camera, so you can snap a picture and send it straight to the model without saving it to the gallery first."),
    )),
    ChangelogEntry("1.9.49", "Regenerate keeps the old answers", listOf(
        Change(ChangeType.NEW, "Regenerating a reply no longer throws the old one away. Each answer is kept as a variant, and back and forward arrows next to the Regenerate button (with a \"2/2\" counter) let you page between them. The answer you leave showing is the one the chat continues from. Variants are kept for the current session; they are not saved across an app restart yet."),
    )),
    ChangelogEntry("1.9.48", "Send images to remote servers", listOf(
        Change(ChangeType.NEW, "You can now attach an image to a chat with a remote server model, just like with on-device models. The image is sent to the server together with your message. The attach-image button appears when the server reports a vision-capable model (and always for LM Studio, which doesn't advertise it, so you can try). If the model can't see images, the server returns an error."),
    )),
    ChangelogEntry("1.9.47", "GPU works with any KV setting", listOf(
        Change(ChangeType.FIX, "With GPU acceleration on, creating a chat failed (\"Failed to create session\") whenever the KV cache was set to Q8_0 or Q4_0, which is the default. The GPU's OpenCL backend can't run a quantized KV cache, so the app now keeps the KV cache at full precision (F16) on the GPU automatically. KV quantization still works on the CPU."),
    )),
    ChangelogEntry("1.9.46", "Reach the phone's OpenCL driver", listOf(
        Change(ChangeType.FIX, "With GPU acceleration on, the Compute backend showed \"no OpenCL device\" because the app couldn't reach the phone's own GPU driver. The app now declares access to the device OpenCL library and uses it directly, so an Adreno GPU can be detected. Still opt-in; if your device does not expose OpenCL, it falls back to the CPU as before."),
    )),
    ChangelogEntry("1.9.45", "Cleaner wording", listOf(
        Change(ChangeType.IMPROVED, "Polished the wording across settings, menus and these release notes for a cleaner, more natural style."),
    )),
    ChangelogEntry("1.9.44", "Corrected the GPU setting label", listOf(
        Change(ChangeType.FIX, "The GPU acceleration setting still said \"Vulkan\". It now correctly says \"OpenCL\", the GPU backend the app actually switched to."),
    )),
    ChangelogEntry("1.9.43", "See which backend a model runs on", listOf(
        Change(ChangeType.NEW, "The model settings sheet (tune icon → Parameters) now shows a \"Compute backend\" line for local models: \"GPU (OpenCL): <your GPU> (N/N layers)\" when the GPU is actually in use, or \"CPU\" otherwise. This lets you verify the experimental GPU toggle really took effect. If it says CPU while the toggle is on, the GPU couldn't be used and it safely ran on the CPU."),
    )),
    ChangelogEntry("1.9.42", "GPU backend switched to OpenCL", listOf(
        Change(ChangeType.IMPROVED, "The experimental GPU path now uses OpenCL (Qualcomm's Adreno-native GPU API) instead of Vulkan. Vulkan crashed on Adreno GPUs; OpenCL is the path Google's own on-device AI and llama.cpp both use on Qualcomm chips, so it has a real chance of running the model on the GPU reliably. Still opt-in (Settings → Advanced → GPU acceleration); if it doesn't help or misbehaves on your device, leave it off. Everything runs on the CPU by default."),
    )),
    ChangelogEntry("1.9.41", "GPU acceleration is now an opt-in toggle", listOf(
        Change(ChangeType.FIX, "Vision on the GPU crashed the inference engine on some devices (e.g. Adreno). GPU use is now OFF by default, so vision and text both run reliably on the CPU again, with no more crashes out of the box."),
        Change(ChangeType.NEW, "New \"GPU acceleration (experimental)\" switch in Settings → Advanced. Turn it on to offload the whole model (LLM and image encoding) to the GPU (Vulkan). Faster prompt processing and image encoding when your GPU supports it; if you get wrong output or a crash, turn it back off. Applies on the next model load."),
    )),
    ChangelogEntry("1.9.40", "GPU-accelerated image vision", listOf(
        Change(ChangeType.NEW, "Image processing for vision models now runs on your phone's GPU (Vulkan) instead of the CPU, noticeably faster image encoding, especially for larger/higher-detail images. Text generation still runs on the CPU (that's by design, the GPU isn't faster there on mobile)."),
        Change(ChangeType.IMPROVED, "Safety net: if a device's GPU driver can't handle the vision encoder, the app automatically falls back to CPU vision: a known-bad GPU is skipped up front, and a GPU that crashes once is disabled for good afterwards, so vision keeps working."),
    )),
    ChangelogEntry("1.9.39", "GPU groundwork (Vulkan)", listOf(
        Change(ChangeType.IMPROVED, "Under the hood: the Vulkan GPU backend is now compiled into the app, groundwork for upcoming GPU-accelerated image processing. No behaviour change yet: text generation and image input still run on the CPU exactly as before; this build just verifies the GPU support builds and installs cleanly on your device."),
    )),
    ChangelogEntry("1.9.38", "KV-cache quantization", listOf(
        Change(ChangeType.NEW, "New \"KV cache\" picker in a local model's settings (Parameters tab): FP16, Q8_0 (standard), or Q4_0. Q8_0 is now the default: it roughly halves the memory the conversation cache uses, at near-zero quality loss, and can speed up longer chats."),
        Change(ChangeType.IMPROVED, "Quantized cache automatically enables Flash Attention, and safely falls back to full-precision FP16 if your device can't run that combination, so a model always loads."),
    )),
    ChangelogEntry("1.9.37", "True model-view + wider vision detection", listOf(
        Change(ChangeType.NEW, "A sent image now displays at the exact resolution the model actually received it, so you see the real image the model \"saw\". Move the Image detail slider and watch it change."),
        Change(ChangeType.IMPROVED, "Any downloaded model paired with a matching projector (mmproj) file in your models folder is now recognized as a vision model (not just sideloaded ones), so its image badge shows in the picker."),
    )),
    ChangelogEntry("1.9.36", "Image token count + detail slider", listOf(
        Change(ChangeType.NEW, "Sent images now show how many tokens they used (🖼 N), so you can see how much of the context a photo consumes."),
        Change(ChangeType.NEW, "New \"Image detail\" slider in a vision model's settings: trade image resolution/detail against tokens and speed. Higher = sharper, lower = faster & lighter on context."),
    )),
    ChangelogEntry("1.9.35", "Vision projector loads (audio encoder skipped)", listOf(
        Change(ChangeType.FIX, "Fixed the crash when loading a vision model whose projector also bundles an audio encoder (e.g. Gemma 3n / Gemma 4 E2B): the unused audio part is now skipped, so image input can finally load."),
    )),
    ChangelogEntry("1.9.34", "Vision load diagnostics", listOf(
        Change(ChangeType.IMPROVED, "If a vision model's image projector fails to load, the app now shows a copyable diagnostic (memory + the native crash report) so projector-compatibility issues on sideloaded models can be pinned down precisely."),
    )),
    ChangelogEntry("1.9.32", "Image preview + clearer vision errors", listOf(
        Change(ChangeType.NEW, "Tap an attached or sent image to view it full-screen: pinch to zoom, tap to close, just like the document and PDF previews."),
        Change(ChangeType.IMPROVED, "When a vision model can't load its image projector, the message now says exactly why (e.g. an incompatible mmproj) instead of a generic error."),
    )),
    ChangelogEntry("1.9.31", "Send images to vision models", listOf(
        Change(ChangeType.NEW, "Attach a photo from your gallery and ask about it. The attach button now offers Document / Image for vision models (those with an mmproj projector in your models folder)."),
        Change(ChangeType.NEW, "Sent images show as a thumbnail in the chat and are kept with the conversation."),
        Change(ChangeType.IMPROVED, "The vision projector loads only at your first image, so text chats stay as fast as ever; the model line shows \"Loading vision…\" while it loads."),
        Change(ChangeType.IMPROVED, "Photos are downscaled on-device before reaching the model. HEIC from the camera works, and portrait shots stay upright."),
    )),
    ChangelogEntry("1.9.30", "Vision projector no longer auto-loads", listOf(
        Change(ChangeType.FIX, "Paired vision models no longer load their image projector at startup. This was causing some sideloaded models to hang when generating. Image support will load the projector only when an image is attached."),
    )),
    ChangelogEntry("1.9.29", "Vision loading hardened", listOf(
        Change(ChangeType.FIX, "An incompatible or oversized vision projector can no longer crash the app or block loading other models. A failed projector now just means the model loads as text."),
        Change(ChangeType.FIX, "Tightened model↔projector pairing so an unrelated model is never tagged as vision by mistake."),
    )),
    ChangelogEntry("1.9.28", "Vision models recognised", listOf(
        Change(ChangeType.NEW, "Drop a vision model together with its mmproj projector in your models folder and it's now detected automatically, shown with an image badge in the model list."),
        Change(ChangeType.NEW, "Loading such a model attaches its projector on-device. Attaching and sending images arrives in the next update."),
    )),
    ChangelogEntry("1.9.27", "Vision groundwork II", listOf(
        Change(ChangeType.NEW, "Wired up on-device image tokenization and generation (vision). The feature keeps coming together; UI arrives soon."),
    )),
    ChangelogEntry("1.9.26", "Vision groundwork", listOf(
        Change(ChangeType.NEW, "Laid the on-device foundation for image input (vision). Image understanding arrives over the next few updates."),
    )),
    ChangelogEntry("1.9.25", "Unified, tabbed model settings", listOf(
        Change(ChangeType.NEW, "Manage your saved system prompts straight from the model settings: pick, edit, delete or create one, without leaving the chat."),
        Change(ChangeType.IMPROVED, "Remote server models now share the same tabbed settings (Prompt · Tools · Parameters) as local models, including system prompts."),
        Change(ChangeType.DESIGN, "The settings tabs now blend into the sheet instead of sitting on a dark bar."),
        Change(ChangeType.DESIGN, "Redesigned this changelog, grouped by change type."),
    )),
    ChangelogEntry("1.9.24", "Preview & settings polish", listOf(
        Change(ChangeType.DESIGN, "Removed the fiddly text-preview zoom (PDF and HTML zoom are unchanged)."),
        Change(ChangeType.FIX, "Restored a missing divider in Settings › Tools."),
    )),
    ChangelogEntry("1.9.23", "Tabbed model settings", listOf(
        Change(ChangeType.NEW, "The model settings sheet is now split into Prompt · Tools · Parameters tabs."),
    )),
    ChangelogEntry("1.9.22", "Smarter chats & more file types", listOf(
        Change(ChangeType.NEW, "Auto-name new chats from the first reply. Optional, in Settings › Tools."),
        Change(ChangeType.NEW, "Preview spreadsheets (Excel/CSV) as a table, with a Table ⇄ Raw toggle."),
        Change(ChangeType.NEW, "Attach RTF, OpenDocument (ODT/ODS/ODP) and EPUB files."),
        Change(ChangeType.IMPROVED, "More accurate token estimates for non-Latin (CJK) text."),
        Change(ChangeType.IMPROVED, "Drag a zoomed PDF with a single finger."),
    )),
    ChangelogEntry("1.9.21", "PDF zoom reaches every edge", listOf(
        Change(ChangeType.FIX, "Two-finger pan on a zoomed PDF now reaches every edge, including single-page documents."),
    )),
    ChangelogEntry("1.9.20", "Office documents", listOf(
        Change(ChangeType.NEW, "Attach Word (.docx), Excel (.xlsx) and PowerPoint (.pptx) files. Their text is extracted and sent to the model."),
    )),
    ChangelogEntry("1.9.19", "Pinch to zoom PDFs", listOf(
        Change(ChangeType.NEW, "Pinch to zoom PDF pages in the preview."),
    )),
    ChangelogEntry("1.9.18", "Real PDF pages", listOf(
        Change(ChangeType.NEW, "Preview PDFs as real pages, not just text, with a Pages ⇄ Text toggle."),
        Change(ChangeType.IMPROVED, "Scanned or image-only PDFs now preview too. Pages show even without extractable text."),
    )),
    ChangelogEntry("1.9.17", "Attach PDFs", listOf(
        Change(ChangeType.NEW, "Attach PDF files. Their text is extracted and sent to the model."),
    )),
    ChangelogEntry("1.9.16", "Complete HTML previews", listOf(
        Change(ChangeType.FIX, "HTML preview now shows the whole page, including sections that reveal on scroll."),
    )),
    ChangelogEntry("1.9.15", "Roomier, sturdier previews", listOf(
        Change(ChangeType.DESIGN, "The file-preview card is much larger for easier reading."),
        Change(ChangeType.FIX, "HTML files preview correctly, and the Raw view no longer freezes the app."),
    )),
    ChangelogEntry("1.9.14", "Per-chat context & file previews", listOf(
        Change(ChangeType.IMPROVED, "The context-usage ring now reflects each chat as you switch between them."),
        Change(ChangeType.NEW, "Tap an attached file to preview it, with a Raw ⇄ Formatted toggle (a rendered page for HTML)."),
    )),
    ChangelogEntry("1.9.13", "Multi-file attachments", listOf(
        Change(ChangeType.NEW, "Attach several files at once; long-press a file chip to see its full name and token cost."),
        Change(ChangeType.DESIGN, "A roomier message box."),
    )),
    ChangelogEntry("1.9.12", "Attach text & HTML", listOf(
        Change(ChangeType.NEW, "Attach text and HTML files. Their contents are extracted and sent along with your message."),
    )),
    ChangelogEntry("1.9.11", "Clearer settings & reasoning", listOf(
        Change(ChangeType.DESIGN, "Settings reorganised into clearer sections."),
        Change(ChangeType.IMPROVED, "The live reasoning tail stays aligned and can be expanded while the model writes."),
    )),
    ChangelogEntry("1.9.10", "Advanced settings", listOf(
        Change(ChangeType.NEW, "New Advanced settings screen."),
        Change(ChangeType.FIX, "Stability and model-picker fixes."),
    )),
    ChangelogEntry("1.9.9", "In-app changelog & date", listOf(
        Change(ChangeType.NEW, "In-app changelog: tap the version number a few times to open this list."),
        Change(ChangeType.NEW, "Optional current date in the system prompt (toggle in Settings)."),
    )),
    ChangelogEntry("1.9.8", "Themed logo & reasoning peek", listOf(
        Change(ChangeType.DESIGN, "The welcome-screen logo now follows your chosen accent colour."),
        Change(ChangeType.IMPROVED, "A shimmer plus a live peek of the model's reasoning while it works."),
        Change(ChangeType.FIX, "Custom GGUF models with junk metadata now show a clean name from the filename."),
        Change(ChangeType.DESIGN, "Rose and red theme colours are now clearly distinct."),
    )),
    ChangelogEntry("1.9.7", "Colour-picker polish", listOf(
        Change(ChangeType.DESIGN, "Aligned swatch grid, a gradient System swatch, and added Red and Grey."),
    )),
    ChangelogEntry("1.9.6", "Accent colours", listOf(
        Change(ChangeType.NEW, "Choose the app's accent colour: System (Material You) plus several presets."),
    )),
    ChangelogEntry("1.9.5", "Richer model info", listOf(
        Change(ChangeType.NEW, "More provider logos and richer Ollama model details (capability badges)."),
        Change(ChangeType.DESIGN, "A favicon cluster and a live reasoning label in the process card."),
    )),
    ChangelogEntry("1.9.4", "Live process card", listOf(
        Change(ChangeType.IMPROVED, "A live process card during generation, with tappable web sources."),
        Change(ChangeType.NEW, "Added Ernie (Baidu) and Ornith provider logos."),
    )),
    ChangelogEntry("1.9.3", "Tidy agent turns", listOf(
        Change(ChangeType.DESIGN, "Multi-step agent turns collapse into one tidy, inspectable card."),
    )),
    ChangelogEntry("1.9.2", "Reasoning display fix", listOf(
        Change(ChangeType.FIX, "Reasoning text no longer shows dark code boxes."),
    )),
    ChangelogEntry("1.9.1", "Remote tools", listOf(
        Change(ChangeType.NEW, "Web search, web fetch and JavaScript tools now work over remote servers."),
    )),
    ChangelogEntry("1.9.0", "Rich Markdown & a fresh UI", listOf(
        Change(ChangeType.NEW, "Rich Markdown answers: code cards, tables and math."),
        Change(ChangeType.DESIGN, "Provider logos and model details in the top bar; a frosted UI throughout."),
    )),
)

/** The version-tap easter egg: a scrollable, categorised list of every release. */
@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    AlertDialog(
        onDismissRequest = onDismiss,
        // Break out of the platform's narrow default width so the notes have
        // room to breathe.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .padding(vertical = 24.dp),
        title = { Text(stringResource(R.string.changelog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                CHANGELOG.forEachIndexed { i, entry ->
                    if (i > 0) {
                        Spacer(Modifier.height(20.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(20.dp))
                    }
                    EntryHeader(entry = entry, isLatest = i == 0)
                    Spacer(Modifier.height(10.dp))
                    entry.changes.forEach { change ->
                        ChangeRow(change)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun EntryHeader(entry: ChangelogEntry, isLatest: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Text(
                text = "v${entry.version}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        if (isLatest) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Text(
                    text = "Latest",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = entry.title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ChangeRow(change: Change) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = change.type.emoji,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(26.dp),
        )
        Text(
            text = change.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
