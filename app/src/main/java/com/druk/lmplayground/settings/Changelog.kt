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
    ChangelogEntry("1.9.95", "Fix Gemma 4 streaming", listOf(
        Change(ChangeType.FIX, "Gemma 4 (LiteRT) replies now stream token by token as they are generated, instead of appearing all at once at the end. The real cause: a token-count read that ran during decoding was blocking on the engine, holding every token back until generation finished. That read now happens only before and after decoding, so tokens flow through live."),
    )),
    ChangelogEntry("1.9.94", "Live streaming for Gemma 4", listOf(
        Change(ChangeType.IMPROVED, "Gemma 4 (LiteRT) replies now stream token by token as they are produced, instead of appearing all at once when generation finishes. The per-token callback runs on a dedicated looper thread so each token is delivered live."),
    )),
    ChangelogEntry("1.9.93", "Restore Gemma 4 generation", listOf(
        Change(ChangeType.FIX, "Restored Gemma 4 (LiteRT) generation. A streaming change in the previous build stopped it from producing any output. The decode now runs on a background thread with an unlimited buffer, so the reply drains progressively rather than blocking the display."),
    )),
    ChangelogEntry("1.9.92", "Real token-by-token streaming for Gemma 4", listOf(
        Change(ChangeType.FIX, "Gemma 4 (LiteRT) replies now truly stream token by token as they are generated. The earlier fix stopped the reply from being cut off, but the text still appeared all at once because the streaming API delivered it in one burst at the end. This switches to LiteRT's per-token callback, run on a dedicated thread, so the answer types out live."),
    )),
    ChangelogEntry("1.9.91", "Fix Gemma 4 streaming and truncation", listOf(
        Change(ChangeType.FIX, "Gemma 4 (LiteRT) replies now stream token by token again, and no longer get cut off partway through. The engine's output was being buffered and dropped when the on-screen display could not keep up; it now drains each token as it is produced."),
        Change(ChangeType.IMPROVED, "The KV cache precision selector is hidden for Gemma 4 (LiteRT) models, since the LiteRT engine keeps its KV cache at a fixed precision and does not support quantizing it."),
    )),
    ChangelogEntry("1.9.90", "Adjustable Gemma 4 context (up to 32K)", listOf(
        Change(ChangeType.IMPROVED, "The context window for Gemma 4 (LiteRT) models is now adjustable up to 32K tokens with the context slider in the generation settings, and your context, temperature, and sampling choices are remembered per model. A larger context uses more memory (LiteRT keeps its KV cache at full precision), so the slider lets you pick the balance. Changing the context reloads the model."),
    )),
    ChangelogEntry("1.9.89", "Gemma 4 chat: context, speed readout, MTP toggle", listOf(
        Change(ChangeType.IMPROVED, "Gemma 4 (LiteRT) chat context raised from 4096 to 8192 tokens, so conversations can run longer before older messages drop off."),
        Change(ChangeType.FIX, "The tokens-per-second shown under a Gemma 4 reply is now correct. It was undercounting when multi-token prediction was on, because it counted streamed chunks instead of real tokens."),
        Change(ChangeType.NEW, "Added a Speculative Decoding (MTP) toggle in the generation settings for Gemma 4 models. You can turn multi-token prediction on or off on both CPU and GPU. Changing it reloads the model."),
    )),
    ChangelogEntry("1.9.88", "Chat with Gemma 4 (LiteRT)", listOf(
        Change(ChangeType.NEW, "Gemma 4 models (E2B and E4B) now show up in the model picker and run in the real chat on the new LiteRT engine, with multi-token prediction on the GPU for much faster replies. Selecting a Gemma 4 model automatically unloads the llama.cpp model. Tools, thinking, and image input on LiteRT are coming next."),
    )),
    ChangelogEntry("1.9.87", "LiteRT dev test: E4B model + decode chart", listOf(
        Change(ChangeType.IMPROVED, "The developer LiteRT test now benchmarks every Gemma 4 model found on the device (E2B and E4B) across CPU and GPU with speculative decoding off and on, and shows the results as a bar chart of decode tokens per second, in the same style as the normal benchmarks. Each bar also checks that the output matches its base config."),
    )),
    ChangelogEntry("1.9.86", "LiteRT: real tokens/sec in the dev test", listOf(
        Change(ChangeType.IMPROVED, "The developer LiteRT test now also reports real decode tokens per second, measured with LiteRT's built-in benchmark (the same one Google's Edge Gallery uses), next to characters per second. Makes the numbers directly recognizable and comparable to Edge Gallery."),
    )),
    ChangelogEntry("1.9.85", "LiteRT: full-run timing to compare with Edge Gallery (dev)", listOf(
        Change(ChangeType.IMPROVED, "The developer LiteRT test now times the full response the same way Google's Edge Gallery app does (total seconds for the identical output), so we can compare our speed to Google's directly, not just the multi-token-prediction ratio. It also reports time to first token and caps generation at 4096 tokens to match."),
    )),
    ChangelogEntry("1.9.84", "LiteRT MTP: correct decode measurement (dev)", listOf(
        Change(ChangeType.FIX, "The developer LiteRT test was counting streamed messages instead of tokens, which badly undercounted multi-token prediction (it emits several tokens per message, so it looked slower than it really is). It now measures by characters of the identical output, so the MTP versus normal comparison is finally apples to apples, and it also checks that MTP produces the exact same text as normal decoding."),
    )),
    ChangelogEntry("1.9.83", "LiteRT MTP: GPU sampler link fix (dev)", listOf(
        Change(ChangeType.FIX, "Second attempt at the GPU sampler fix. The previous build loaded the runtime into the shared linker scope, but Android's library isolation still kept the GPU sampler from finding the runtime's functions, so it stayed on slow CPU sampling. This build links the sampler directly against the runtime library instead, which should finally let multi-token prediction run its sampling on the GPU and speed up decoding."),
    )),
    ChangelogEntry("1.9.82", "LiteRT MTP: GPU sampler symbol fix (dev)", listOf(
        Change(ChangeType.FIX, "Follow-up to the last build: bundling the GPU sampler was not enough on its own, because the library could not find the runtime symbols it needs, so the app quietly fell back to slow CPU sampling. This build loads the LiteRT runtime into the shared linker scope first, so the GPU sampler can resolve its symbols and stay on the GPU during multi-token prediction. If it works, MTP decode should finally beat normal decoding on this device."),
    )),
    ChangelogEntry("1.9.81", "LiteRT GPU sampler for MTP (dev)", listOf(
        Change(ChangeType.FIX, "The LiteRT test showed multi-token prediction running slower than normal decoding, because a GPU sampling library was missing from the runtime, so every step fell back to slow CPU sampling and copied data back and forth. This build bundles that library so sampling stays on the GPU, which should let the MTP speedup finally show up on this device."),
    )),
    ChangelogEntry("1.9.80", "LiteRT speedup measurement (dev)", listOf(
        Change(ChangeType.IMPROVED, "The developer LiteRT test now measures decode speed on both CPU and GPU, with multi-token prediction off and on, so we can see the real MTP speedup on this device (the whole reason for the new engine)."),
    )),
    ChangelogEntry("1.9.79", "LiteRT proof-of-life (dev)", listOf(
        Change(ChangeType.NEW, "Added a temporary developer test button in the benchmark screen that loads a Gemma 4 model through the new LiteRT engine and streams a few tokens, to confirm the second engine actually runs on this device before wiring it into the app properly."),
    )),
    ChangelogEntry("1.9.78", "LiteRT engine groundwork", listOf(
        Change(ChangeType.NEW, "First step toward a second on-device engine: added the LiteRT-LM runtime (Google AI Edge) to the app. It will run Gemma 4 models with hardware-accelerated multi-token prediction, the real decode speedup our llama.cpp experiment topped out at break-even on. No user-facing change yet; this build just integrates the runtime."),
    )),
    ChangelogEntry("1.9.77", "MTP larger draft test", listOf(
        Change(ChangeType.IMPROVED, "Experimental MTP now drafts 3 tokens per step instead of 2, to check whether a larger batch pays off (especially on GPU, where speculative decoding can amortize weight loading). A diagnostic step toward finding whether MTP can ever beat normal decoding on this hardware."),
    )),
    ChangelogEntry("1.9.76", "MTP on GPU (experimental)", listOf(
        Change(ChangeType.NEW, "Experimental MTP speculative decoding now also runs on the GPU, not just CPU. On CPU it turned out to slow decoding down slightly (the verify overhead outweighs the batching), but the GPU is where speculative decoding can actually pay off, so it is now worth measuring there. It stays a safe no-op if the GPU backend cannot run the verify."),
    )),
    ChangelogEntry("1.9.75", "Accurate MTP decode measurement", listOf(
        Change(ChangeType.FIX, "The benchmark now measures decode speed from the engine's real streamed-token count and timing instead of counting UI stream updates. With MTP on, accepted tokens arrive in bursts that the UI stream merges, which made the decode number read as ~0 even though generation completed. Applies to normal decoding too."),
    )),
    ChangelogEntry("1.9.74", "MTP draft size tuning", listOf(
        Change(ChangeType.FIX, "Another step on experimental MTP: reduced the speculative draft size so the verify step fits within what Qwen 3.5's recurrent layers support (larger drafts were failing the decode). This build helps pin down the working ceiling."),
    )),
    ChangelogEntry("1.9.73", "MTP verify step fix", listOf(
        Change(ChangeType.FIX, "Experimental MTP speculative decoding now gets through its verify step on Qwen 3.5 (the hybrid model's recurrent state needed more rollback room for the multi-token verify pass, which was failing the decode). Speculation runs on CPU for now; the GPU path keeps normal decoding."),
    )),
    ChangelogEntry("1.9.72", "MTP crash fix (now runs end to end)", listOf(
        Change(ChangeType.FIX, "Fixed a crash that stopped experimental MTP speculative decoding from actually running in the benchmark (the MTP head was handed a batch without token positions). It now runs end to end on Qwen 3.5 models with an MTP head, and the result badge shows the draft acceptance percentage."),
    )),
    ChangelogEntry("1.9.71", "MTP now engages on Qwen 3.5", listOf(
        Change(ChangeType.FIX, "MTP speculative decoding was silently skipped on Qwen 3.5 (its hybrid layers need per-token rollback snapshots to trim rejected drafts, which the target context was not set up for). It now engages, and the benchmark result shows the draft acceptance percentage next to the hardware (for example \"CPU . MTP 75%\")."),
    )),
    ChangelogEntry("1.9.70", "MTP speculative decoding (experimental)", listOf(
        Change(ChangeType.NEW, "Experimental self-MTP speculative decoding now actually runs in the benchmark on Qwen 3.5 models that have an MTP head: the model drafts several tokens ahead and verifies them in a single pass, which can speed up decoding when the drafts are accepted. Greedy only for now (benchmark), and the output stays identical to normal decoding. The result shows the draft acceptance rate so you can see whether it is helping."),
    )),
    ChangelogEntry("1.9.69", "MTP status in the benchmark", listOf(
        Change(ChangeType.IMPROVED, "The benchmark now shows the experimental MTP outcome right on the result: \"CPU · MTP\" when the model's MTP head was built, or \"no MTP\" when the model does not support it. No more digging through logs to confirm a Qwen 3.5 model is MTP-capable, before the real speedup arrives in a later update."),
    )),
    ChangelogEntry("1.9.68", "Experimental MTP groundwork", listOf(
        Change(ChangeType.NEW, "First step toward MTP speculative decoding on Qwen 3.5 models: an experimental toggle in the benchmark. This step only detects whether a model has an MTP head (check the logs); the actual decode speedup lands in a later update, and it does nothing on other models."),
    )),
    ChangelogEntry("1.9.67", "Benchmark comparison", listOf(
        Change(ChangeType.NEW, "New Compare toggle on the benchmark screen: see every model side by side, one bar per model and accelerator for decode speed, prefill speed and time-to-first-token, sorted best first and colored, so you can tell at a glance which model is fastest on this device. Uses each model's latest result per CPU/GPU; tap a bar for the full detail."),
    )),
    ChangelogEntry("1.9.66", "Benchmark loading indicator", listOf(
        Change(ChangeType.FIX, "Starting a benchmark now immediately shows a loading indicator while the model loads into memory, instead of leaving the Run button up (which looked like nothing had happened, so it got tapped again)."),
    )),
    ChangelogEntry("1.9.65", "Benchmark detail cards", listOf(
        Change(ChangeType.IMPROVED, "Tap a saved benchmark to open a detail card with colored score bars for prefill, decode and time-to-first-token, so you can see at a glance whether a model ran well. Each result also records how long it took."),
        Change(ChangeType.IMPROVED, "The benchmark history shows a short CPU / GPU badge (the full accelerator name is in the detail card)."),
        Change(ChangeType.FIX, "The model settings tabs spread evenly across the full width again."),
    )),
    ChangelogEntry("1.9.64", "Dedicated benchmark screen", listOf(
        Change(ChangeType.NEW, "Benchmark moved to its own screen in Settings: pick any downloaded model, pick the accelerator (CPU, GPU, or both in sequence), and run a benchmark that blocks until it finishes and keeps a per-model history. The model you had loaded in chat is restored afterward."),
    )),
    ChangelogEntry("1.9.63", "Benchmark fixes", listOf(
        Change(ChangeType.FIX, "The benchmark now reports real prefill and decode speeds. The on-device engine doesn't expose those timings, so they are computed from wall-clock timing plus the token counts (before, both showed 0)."),
        Change(ChangeType.FIX, "The model settings tabs no longer wrap onto two lines with the Benchmark tab present."),
    )),
    ChangelogEntry("1.9.62", "Tidier model picker + names", listOf(
        Change(ChangeType.IMPROVED, "Downloaded models in the picker are now grouped by provider and sorted alphabetically within each group, the same way remote server models already were."),
        Change(ChangeType.IMPROVED, "For a model loaded from a remote server, its full name now shows at the top of the parameters sheet, so you can read it even when the top bar truncates a long name."),
    )),
    ChangelogEntry("1.9.61", "Benchmark your models", listOf(
        Change(ChangeType.NEW, "New Benchmark tab in a model's settings sheet: measure the loaded model's prefill and decode speed (tokens per second) plus time-to-first-token over a few runs, and keep a per-model history. Set the prefill tokens, decode tokens and number of runs. On-device models only; comparison across models and charts are coming next."),
    )),
    ChangelogEntry("1.9.60", "Models list refreshes on its own", listOf(
        Change(ChangeType.FIX, "Models copied into the models folder by hand now show up without re-picking the folder: the Models screen rescans every time you open it, there is a Refresh button in its top bar, and re-selecting the same folder rescans instead of doing nothing."),
    )),
    ChangelogEntry("1.9.59", "Memory you can manage", listOf(
        Change(ChangeType.NEW, "New Memory screen in Settings: view, add, edit and delete the notes the model remembers across chats, all in one place. The model can still save and update them on its own with the memory tool."),
        Change(ChangeType.NEW, "Saved memories can now be added to every chat automatically, so the model recalls them without being asked. This is opt-in: turn on Use memory in the Memory screen (off by default)."),
        Change(ChangeType.FIX, "In a multi-step turn, the last reasoning step now appears in the process card as soon as the answer starts, instead of staying hidden until the whole response finished generating."),
    )),
    ChangelogEntry("1.9.58", "Context ring counts active tools", listOf(
        Change(ChangeType.FIX, "The context-usage ring now includes the tokens taken by the enabled tools' definitions, which the model always receives at the start of the prompt. With several tools on this can be a lot, so the ring now shows the real remaining space from the start instead of ignoring the tool overhead until the first reply."),
    )),
    ChangelogEntry("1.9.57", "Fix crash on startup", listOf(
        Change(ChangeType.FIX, "The previous update could crash the app on startup, because the new Memory feature created its database table with a slightly wrong schema. Fixed so the app opens normally again; your chats and settings are untouched."),
    )),
    ChangelogEntry("1.9.56", "Memory tool, clearer tool steps", listOf(
        Change(ChangeType.NEW, "New Memory tool (turn it on in Settings, then Tools): the model can save short notes and recall them in later chats, for example \"remember that…\". Notes are stored only on your device."),
        Change(ChangeType.IMPROVED, "In the reasoning card, each tool step now shows the tool's real name and its own icon (calculator, location pin, and so on) instead of a generic wrench and the internal name."),
    )),
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
