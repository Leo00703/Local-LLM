package com.druk.lmplayground.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.druk.lmplayground.R
import com.druk.lmplayground.conversation.ChatItemBubble
import com.druk.lmplayground.conversation.Message
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Settings row that opens a small test of how smoothly the app draws a streaming reply. It
 * pushes a long markdown answer (headings, lists, code, a table) through the real message
 * bubble at the pace of a fast model and reports the frame times, so a change to the UI (or a
 * device) can be compared by number instead of by eye, without adb. Self-contained like
 * [GpuAccelerationRow]: no ViewModel wiring.
 */
@Composable
fun UiSmoothnessRow() {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Speed,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ui_smoothness_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ui_smoothness_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (open) UiSmoothnessDialog(onDismiss = { open = false })
}

private enum class SmoothPhase { Idle, Running, Done }

@Composable
private fun UiSmoothnessDialog(onDismiss: () -> Unit) {
    var phase by remember { mutableStateOf(SmoothPhase.Idle) }
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    // Bumped to start a run; the effect below is keyed on it.
    var runId by remember { mutableIntStateOf(0) }

    if (phase == SmoothPhase.Running) {
        LaunchedEffect(runId) {
            val frames = ArrayList<Long>()
            // One frame callback after another: the gap between them is the time a frame took
            // to be produced, so a main thread kept busy shows up as a long gap.
            val collector = launch {
                var previous = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    frames.add(now - previous)
                    previous = now
                }
            }
            val full = syntheticReply()
            var position = 0
            while (position < full.length) {
                position = minOf(full.length, position + CHARS_PER_UPDATE)
                text = full.substring(0, position)
                delay(UPDATE_INTERVAL_MS)
            }
            delay(250)
            collector.cancel()
            result = summarize(frames)
            phase = SmoothPhase.Done
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.ui_smoothness_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (phase) {
                        SmoothPhase.Idle -> stringResource(R.string.ui_smoothness_hint)
                        SmoothPhase.Running -> stringResource(R.string.ui_smoothness_running)
                        SmoothPhase.Done -> result
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(
                        enabled = phase != SmoothPhase.Running,
                        onClick = {
                            text = ""
                            result = ""
                            phase = SmoothPhase.Running
                            runId++
                        },
                    ) {
                        Text(stringResource(R.string.ui_smoothness_run))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (text.isNotEmpty()) {
                        // showActions = false is the "still generating" look of the real chat.
                        ChatItemBubble(
                            message = Message(author = "Assistant", content = text),
                            showActions = false,
                        )
                    }
                }
            }
        }
    }
}

/** About 120 tokens a second: a fast small model, the case that stresses the UI most. */
private const val CHARS_PER_UPDATE = 16
private const val UPDATE_INTERVAL_MS = 33L

private fun syntheticReply(): String = buildString {
    repeat(14) { i ->
        append("## Section ${i + 1}\n\n")
        append(
            "Local models run entirely on the phone, so latency depends on the **prompt length**, " +
                "the *quantization* and the memory bandwidth. A longer paragraph keeps the layout " +
                "engine busy: `inline code`, [a link](https://example.com), and some more words so " +
                "that it wraps over several lines on a narrow screen.\n\n"
        )
        append("- first point of section ${i + 1}\n- second point with `code`\n- third point\n\n")
        if (i % 3 == 0) {
            append("```kotlin\nfun main() {\n    println(\"hello ${i + 1}\")\n}\n```\n\n")
        }
        if (i % 4 == 0) {
            append("| Model | Speed |\n|---|---|\n| A | ${10 + i} tok/s |\n| B | ${20 + i} tok/s |\n\n")
        }
    }
}

private fun summarize(framesNs: List<Long>): String {
    if (framesNs.size < 5) return "Too few frames were drawn to measure anything."
    val ms = framesNs.map { it / 1_000_000.0 }.sorted()
    val median = ms[ms.size / 2]
    val p95 = ms[(ms.size * 95 / 100).coerceAtMost(ms.size - 1)]
    val slow = ms.count { it > 25.0 }
    val frozen = ms.count { it > 100.0 }
    return String.format(
        Locale.US,
        "%d frames, median %.1f ms, 95th percentile %.1f ms, %d slow (over 25 ms), %d frozen (over 100 ms)",
        ms.size, median, p95, slow, frozen,
    )
}
