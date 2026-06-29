package com.druk.lmplayground.conversation

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.druk.lmplayground.R

@Composable
fun ChatItemBubble(
    message: Message,
    showActions: Boolean = true,
    showStats: Boolean = true,
    canRegenerate: Boolean = false,
    onRegenerate: (() -> Unit)? = null,
    onTokenCountClicked: (() -> Unit)? = null
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val hasToolCalls = !message.toolCalls.isNullOrEmpty()
    val isWaitingForResponse = !showActions
        && message.content.isEmpty()
        && !hasToolCalls
    val isGenerating = !showActions

    Column {
        if (isWaitingForResponse) {
            ThinkingCardLive(message.id)
        } else {
            val split = remember(message.content) { splitThinking(message.content) }
            if (hasToolCalls) {
                // Unified process card for BOTH live and finalized multi-step
                // turns: it populates progressively while generating (search /
                // reasoning / fetch steps + a live "thinking" tail) and stays
                // collapsible after. The response renders below it.
                AgentProcessCard(
                    message = message,
                    finalThinking = split.thinkingContent,
                    isGenerating = isGenerating,
                    answerStarted = split.responseContent.isNotEmpty()
                )
                if (split.responseContent.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            } else {
            // No tool calls: render reasoning inline (live peek while a <think>
            // block streams, full card once it closes). The branch below guarded
            // by hasToolCalls is unreachable here and kept only for clarity.
            if (hasToolCalls) {
                val thinkingText = stringResource(R.string.thinking)
                val inputLabel = stringResource(R.string.tool_call_input)
                val outputLabel = stringResource(R.string.tool_call_output)
                for (toolCall in message.toolCalls!!) {
                    // This round's thinking, rendered immediately before its
                    // tool call so multi-step turns read in chronological order.
                    if (toolCall.precedingThinking.isNotEmpty()) {
                        val pre = remember(toolCall.precedingThinking) {
                            splitThinking(toolCall.precedingThinking)
                        }
                        if (pre.thinkingContent.isNotEmpty()) {
                            CollapsibleSection(
                                label = buildString {
                                    append("$thinkingText \u00B7 ${formatDuration(toolCall.precedingThinkingDurationSeconds)}")
                                    if (toolCall.precedingThinkingTokens > 0) {
                                        append(" \u00B7 ${toolCall.precedingThinkingTokens} tokens")
                                    }
                                },
                                content = pre.thinkingContent,
                                icon = Icons.Outlined.AutoAwesome,
                                markdown = true
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                    val durationSec = (toolCall.durationMs / 1000).toInt().coerceAtLeast(
                        if (toolCall.durationMs > 0) 1 else 0
                    )
                    CollapsibleSection(
                        label = "${toolDisplayName(toolCall.name)} \u00B7 ${formatDuration(durationSec)}",
                        content = buildString {
                            if (toolCall.arguments.isNotBlank()) {
                                append(inputLabel).append('\n')
                                append(prettyJson(toolCall.arguments))
                                append("\n\n")
                            }
                            append(outputLabel).append('\n')
                            append(prettyJson(toolCall.result))
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // ③ If still generating after tool calls, show the live thinking card
            if (isGenerating && hasToolCalls && message.content.isEmpty()) {
                ThinkingCardLive(message.id)
            }

            val hasThinking = split.thinkingContent.isNotEmpty()
            // While the model is still inside an unclosed <think> block, show the
            // animated live card (no duration/tokens/chevron yet); once </think>
            // arrives it becomes the full collapsible card below.
            val thinkingStreaming = isGenerating &&
                message.content.contains("<think>") &&
                !message.content.contains("</think>")

            if (thinkingStreaming && hasThinking) {
                // Live reasoning: a collapsible card with a running token count.
                // Collapsed by default, showing the live peek window (the last few
                // lines streaming in); tap to expand the full reasoning. Becomes the
                // collapsed card below once </think> arrives.
                val phrase = thinkingPhrase(message.id)
                CollapsibleSection(
                    label = buildString {
                        append(phrase)
                        if (message.thinkingTokens > 0) {
                            append(" \u00B7 ${message.thinkingTokens} tokens")
                        }
                    },
                    content = split.thinkingContent,
                    icon = Icons.Outlined.AutoAwesome,
                    initiallyExpanded = false,
                    markdown = true,
                    peekWhenCollapsed = true
                )
            } else if (thinkingStreaming) {
                // <think> opened but no content yet \u2014 animated header.
                ThinkingCardLive(message.id)
            } else if (hasThinking) {
                val thinkingText = stringResource(R.string.thinking)
                CollapsibleSection(
                    label = buildString {
                        append("$thinkingText \u00B7 ${formatDuration(message.thinkingDurationSeconds)}")
                        if (message.thinkingTokens > 0) {
                            append(" \u00B7 ${message.thinkingTokens} tokens")
                        }
                    },
                    content = split.thinkingContent,
                    icon = Icons.Outlined.AutoAwesome,
                    markdown = true
                )
                if (split.responseContent.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
            }

            if (split.responseContent.isNotEmpty()) {
                MarkdownContent(
                    text = split.responseContent,
                    primary = false
                )
            }
        }

        message.image?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Image(
                painter = painterResource(it),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(160.dp),
                contentDescription = stringResource(id = R.string.attached_image)
            )
        }

        if (showActions) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val shareLabel = stringResource(id = R.string.share)
                IconButton(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, stripThinkTags(message.content))
                        }
                        context.startActivity(Intent.createChooser(sendIntent, shareLabel))
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = shareLabel,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(stripThinkTags(message.content)))
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(id = R.string.copy),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                if (canRegenerate && onRegenerate != null) {
                    IconButton(
                        onClick = onRegenerate,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(id = R.string.regenerate),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                if (showStats && message.responseTokens + message.thinkingTokens > 0) {
                    Text(
                        text = formatResponseStats(message),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .then(
                                if (onTokenCountClicked != null) {
                                    Modifier.clickable(onClick = onTokenCountClicked)
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            }
        }
    }
}

/**
 * Reusable collapsible section with arrow icon, label, and expandable content.
 * Used for both thinking blocks and tool call results.
 */
@Composable
private fun CollapsibleSection(
    label: String,
    content: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    initiallyExpanded: Boolean = false,
    markdown: Boolean = false,
    peekWhenCollapsed: Boolean = false
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) {
                        stringResource(R.string.collapse_thinking)
                    } else {
                        stringResource(R.string.expand_thinking)
                    },
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
            AnimatedVisibility(visible = expanded) {
                SelectionContainer {
                    val contentStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val contentModifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    if (markdown) {
                        // Render the (markdown) thinking text formatted: *italic*,
                        // **bold**, lists, inline code, etc.
                        Text(
                            text = messageFormatter(text = content, primary = false, flatCode = true),
                            style = contentStyle,
                            modifier = contentModifier
                        )
                    } else {
                        Text(
                            text = content,
                            style = contentStyle,
                            modifier = contentModifier
                        )
                    }
                }
            }
            if (!expanded && peekWhenCollapsed && content.isNotEmpty()) {
                ThinkingPeek(content = content, onClick = { expanded = true })
            }
        }
    }
}

/**
 * Live "peek" of the streaming thinking shown when the card is collapsed: the
 * last few lines, auto-pinned to the newest text. Like LM Studio, the top lines
 * don't blur — they DISSOLVE into the card via a transparency gradient as they
 * scroll out: the oldest line at the top fades away fully, the next only
 * slightly, and the newest line stays fully sharp.
 */
@Composable
private fun ThinkingPeek(content: String, onClick: () -> Unit) {
    val scroll = rememberScrollState()
    // Keep the newest thinking pinned to the bottom as it streams in.
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    // ~3 lines tall, like LM Studio's peek.
    val peekHeight = 50.dp
    val peekBottomPad = 10.dp
    val contentWindow = peekHeight - peekBottomPad
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(peekHeight)
            .padding(start = 12.dp, end = 12.dp, bottom = peekBottomPad)
            .clipToBounds()
            .clickable(onClick = onClick)
    ) {
        // Sharp text, newest pinned to the bottom line. Short early-streaming
        // content sits on the bottom line via the bottom-anchored min-height box,
        // instead of floating up into the dissolving top band.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = contentWindow),
                contentAlignment = Alignment.BottomStart
            ) {
                Text(
                    text = messageFormatter(text = content, primary = false, flatCode = true),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
        // No blur — the top lines simply dissolve into the card colour via a
        // transparency gradient (LM Studio style): opaque card at the very top,
        // fully transparent by ~45% down, so the top line fades out, the second
        // only slightly, and everything below stays fully sharp.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to container,
                        0.45f to container.copy(alpha = 0f)
                    )
                )
        )
    }
}

private fun formatResponseStats(message: Message): String {
    val totalTokens = message.responseTokens + message.thinkingTokens
    return buildString {
        // Order: tokens \u00B7 time \u00B7 tok/s \u00B7 TTFT
        append("$totalTokens tokens")
        // Decode window (first token -> end), excluding TTFT + prompt eval, so
        // tok/s reflects real generation speed. Falls back to wall-clock for
        // older persisted messages without a decode value.
        val duration = if (message.responseDecodeSeconds > 0f) {
            message.responseDecodeSeconds
        } else {
            message.responseDurationSeconds
        }
        if (duration > 0f) {
            append(" \u00B7 ${formatSeconds(duration)}")
            append(" \u00B7 ${"%.1f".format(totalTokens / duration)} tok/s")
        }
        if (message.ttftMs > 0) {
            append(" \u00B7 ${formatTtft(message.ttftMs)} TTFT")
        }
    }
}

/** Wall-clock seconds: one decimal under 10s, else m/s via [formatDuration]. */
private fun formatSeconds(seconds: Float): String {
    return if (seconds < 10f) "%.1fs".format(seconds) else formatDuration(seconds.toInt())
}

/** Time-to-first-token: milliseconds under 1s, else seconds with one decimal. */
private fun formatTtft(ttftMs: Int): String {
    return if (ttftMs < 1000) "${ttftMs}ms" else "%.1fs".format(ttftMs / 1000f)
}

private fun formatDuration(seconds: Int): String {
    return if (seconds < 60) {
        "${seconds}s"
    } else {
        val m = seconds / 60
        val s = seconds % 60
        "${m}m ${s}s"
    }
}

/** Friendly display name for a tool (falls back to the raw name for unknowns). */
@Composable
private fun toolDisplayName(name: String): String = when (name) {
    "run_javascript" -> stringResource(R.string.tool_run_javascript_title)
    "web_search" -> stringResource(R.string.tool_web_search_title)
    "web_fetch" -> stringResource(R.string.tool_web_fetch_title)
    else -> name
}

/** Pretty-print a JSON object/array for the tool input/output view; raw on failure. */
private fun prettyJson(raw: String): String {
    val trimmed = raw.trim()
    return try {
        when {
            trimmed.startsWith("{") -> org.json.JSONObject(trimmed).toString(2)
            trimmed.startsWith("[") -> org.json.JSONArray(trimmed).toString(2)
            else -> raw
        }
    } catch (_: Exception) {
        raw
    }
}

/**
 * Live "the model is reasoning" card shown while waiting for the first token and
 * while a `<think>` block is still streaming. It uses the same rounded card as
 * the finished thinking section so the transition is seamless, but shows only an
 * animated, randomly-chosen phrase — no duration, token count, or expand chevron,
 * since there's nothing final to show yet. Once `</think>` arrives the caller
 * swaps in the full [CollapsibleSection].
 */
@Composable
private fun ThinkingCardLive(messageId: Long) {
    val phrase = thinkingPhrase(messageId)
    val transition = rememberInfiniteTransition(label = "thinkingLive")
    val dotCount by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dots"
    )
    val dots = ".".repeat(dotCount.toInt().coerceIn(0, 3))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$phrase$dots",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                fontStyle = FontStyle.Italic
            )
        }
    }
}

/** Localized "reasoning" phrase, picked deterministically per message (varied per reply). */
@Composable
private fun thinkingPhrase(messageId: Long): String {
    val phrases = stringArrayResource(R.array.thinking_phrases)
    return if (phrases.isEmpty()) {
        ""
    } else {
        phrases[kotlin.random.Random(messageId).nextInt(phrases.size)]
    }
}

// ---- Agent process card (finalized multi-step turns) ----------------------

/** One entry in a finalized agentic turn: a reasoning block or a tool call. */
private enum class ProcessKind { THINKING, SEARCH, FETCH, JS, TOOL }

private class ProcessStep(
    val kind: ProcessKind,
    val label: String,
    val body: String,
    val markdown: Boolean,
    val sources: List<WebSource>
)

/** A web source surfaced by web_search / web_fetch, shown as a chip in a step. */
private data class WebSource(val domain: String, val title: String, val url: String?)

/**
 * Collapses a finalized multi-step (reasoning + tool) turn into a single
 * summary row. Tapping it reveals a vertical timeline of every step; tapping a
 * step reveals that step's reasoning, tool I/O, and web sources. Collapsed by
 * default so the answer sits right below it, not under a wall of cards.
 */
@Composable
private fun AgentProcessCard(
    message: Message,
    finalThinking: String,
    isGenerating: Boolean = false,
    answerStarted: Boolean = false,
) {
    val thinkingLabel = stringResource(R.string.thinking)
    val inputLabel = stringResource(R.string.tool_call_input)
    val outputLabel = stringResource(R.string.tool_call_output)
    val toolNames = mapOf(
        "run_javascript" to stringResource(R.string.tool_run_javascript_title),
        "web_search" to stringResource(R.string.tool_web_search_title),
        "web_fetch" to stringResource(R.string.tool_web_fetch_title),
    )
    // While generating, the still-streaming reasoning isn't a finished step yet —
    // it's shown as the animated live tail instead, so don't fold it in here.
    val effectiveFinalThinking = if (isGenerating) "" else finalThinking
    val steps = remember(message.toolCalls, effectiveFinalThinking) {
        buildProcessSteps(message, effectiveFinalThinking, thinkingLabel, inputLabel, outputLabel, toolNames)
    }
    val showLiveTail = isGenerating && !answerStarted
    if (steps.isEmpty() && !showLiveTail) return

    val searches = message.toolCalls.orEmpty().count { it.name == "web_search" }
    val fetches = message.toolCalls.orEmpty().count { it.name == "web_fetch" }
    val runs = message.toolCalls.orEmpty().count { it.name == "run_javascript" }
    val reasoned = steps.any { it.kind == ProcessKind.THINKING }
    val web = searches > 0 || fetches > 0
    val totalThinkSec = message.toolCalls.orEmpty()
        .sumOf { it.precedingThinkingDurationSeconds } + message.thinkingDurationSeconds
    val head = when {
        web && reasoned -> stringResource(R.string.process_reasoned_searched)
        web -> stringResource(R.string.process_searched)
        reasoned -> stringResource(R.string.process_reasoned)
        else -> stringResource(R.string.process_used_tools)
    }
    val parts = mutableListOf(head)
    if (totalThinkSec > 0) parts.add(formatDuration(totalThinkSec))
    if (searches > 0) parts.add(pluralStringResource(R.plurals.process_search_count, searches, searches))
    if (fetches > 0) parts.add(pluralStringResource(R.plurals.process_page_count, fetches, fetches))
    if (runs > 0) parts.add(pluralStringResource(R.plurals.process_run_count, runs, runs))
    val summary = parts.joinToString(" · ")

    // Default: expanded while generating (so you watch it populate live),
    // collapsed once done — unless the user has tapped to override.
    var userExpanded by remember(message.id) { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: isGenerating
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "processChevron"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { userExpanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.process_collapse else R.string.process_expand
                    ),
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
            AnimatedVisibility(visible = expanded) {
                Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                    // Continuous timeline spine behind the step nodes. matchParentSize
                    // gives it a BOUNDED height (the steps column's), so fillMaxHeight
                    // works even inside the unbounded-height LazyColumn; the opaque
                    // node circles mask it where they sit. Inset from the top so the
                    // line starts at the first node's centre.
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(start = 10.dp, top = 17.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(1.5.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                    Column {
                        steps.forEach { step ->
                            ProcessStepRow(step = step)
                        }
                        if (showLiveTail) {
                            LiveStepRow(message.id)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single timeline row: a node badge + label header (tap to expand) over an
 * optional body with the step's reasoning / tool I/O / web sources. The
 * connecting line is drawn separately as a spine behind these rows, so this row
 * never relies on fillMaxHeight (which is a no-op inside the LazyColumn).
 */
@Composable
private fun ProcessStepRow(step: ProcessStep) {
    var expanded by remember { mutableStateOf(false) }
    val rail = MaterialTheme.colorScheme.outlineVariant
    val nodeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val rowChevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "stepChevron"
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Node badge sitting on the spine.
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(nodeBg)
                    .border(1.dp, rail, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconFor(step.kind),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = step.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rowChevron),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        AnimatedVisibility(visible = expanded) {
            // Indent the body past the node + spacer so it lines up under the label.
            Column(modifier = Modifier.padding(start = 32.dp, bottom = 8.dp)) {
                if (step.sources.isNotEmpty()) {
                    SourceChips(step.sources)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                SelectionContainer {
                    val style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (step.markdown) {
                        Text(text = messageFormatter(text = step.body, primary = false, flatCode = true), style = style)
                    } else {
                        Text(text = step.body, style = style)
                    }
                }
            }
        }
    }
}

/** The animated "still working" tail row shown while the turn is generating. */
@Composable
private fun LiveStepRow(messageId: Long) {
    val rail = MaterialTheme.colorScheme.outlineVariant
    val nodeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val phrase = thinkingPhrase(messageId)
    val transition = rememberInfiniteTransition(label = "liveTail")
    val dotCount by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "liveDots"
    )
    val dots = ".".repeat(dotCount.toInt().coerceIn(0, 3))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(nodeBg)
                .border(1.dp, rail, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "$phrase$dots",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            fontStyle = FontStyle.Italic
        )
    }
}

/** Web-source chips (favicon + domain) for a search / fetch step. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceChips(sources: List<WebSource>) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<WebSource?>(null) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (src in sources) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.clickable { pending = src }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SubcomposeAsyncImage(
                        model = "https://www.google.com/s2/favicons?domain=${src.domain}&sz=64",
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        error = {
                            Icon(
                                imageVector = Icons.Outlined.Public,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = src.domain,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
    // Tap a chip → confirm before leaving the app to open the source.
    pending?.let { src ->
        val target = src.url ?: "https://${src.domain}"
        AlertDialog(
            onDismissRequest = { pending = null },
            icon = { Icon(imageVector = Icons.Outlined.Public, contentDescription = null) },
            title = { Text(text = stringResource(R.string.open_source_title)) },
            text = {
                Text(
                    text = target,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    }
                }) { Text(text = stringResource(R.string.open_source_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun iconFor(kind: ProcessKind): ImageVector = when (kind) {
    ProcessKind.THINKING -> Icons.Outlined.AutoAwesome
    ProcessKind.SEARCH -> Icons.Outlined.Search
    ProcessKind.FETCH -> Icons.Outlined.Public
    ProcessKind.JS -> Icons.Outlined.Code
    ProcessKind.TOOL -> Icons.Outlined.Build
}

/**
 * Flattens a finalized turn's tool calls (each preceded by its reasoning) plus
 * the final post-tool reasoning into an ordered list of timeline steps.
 */
private fun buildProcessSteps(
    message: Message,
    finalThinking: String,
    thinkingLabel: String,
    inputLabel: String,
    outputLabel: String,
    toolNames: Map<String, String>
): List<ProcessStep> {
    val steps = mutableListOf<ProcessStep>()
    message.toolCalls?.forEach { tc ->
        val pre = splitThinking(tc.precedingThinking)
        if (pre.thinkingContent.isNotEmpty()) {
            steps += ProcessStep(
                kind = ProcessKind.THINKING,
                label = buildString {
                    append("$thinkingLabel · ${formatDuration(tc.precedingThinkingDurationSeconds)}")
                    if (tc.precedingThinkingTokens > 0) append(" · ${tc.precedingThinkingTokens} tokens")
                },
                body = pre.thinkingContent,
                markdown = true,
                sources = emptyList()
            )
        }
        val durationSec = (tc.durationMs / 1000).toInt().coerceAtLeast(if (tc.durationMs > 0) 1 else 0)
        steps += ProcessStep(
            kind = when (tc.name) {
                "web_search" -> ProcessKind.SEARCH
                "web_fetch" -> ProcessKind.FETCH
                "run_javascript" -> ProcessKind.JS
                else -> ProcessKind.TOOL
            },
            label = "${toolNames[tc.name] ?: tc.name} · ${formatDuration(durationSec)}",
            body = buildString {
                if (tc.arguments.isNotBlank()) {
                    append(inputLabel).append('\n').append(prettyJson(tc.arguments)).append("\n\n")
                }
                append(outputLabel).append('\n').append(prettyJson(tc.result))
            },
            markdown = false,
            sources = extractSources(tc)
        )
    }
    if (finalThinking.isNotEmpty()) {
        steps += ProcessStep(
            kind = ProcessKind.THINKING,
            label = buildString {
                append("$thinkingLabel · ${formatDuration(message.thinkingDurationSeconds)}")
                if (message.thinkingTokens > 0) append(" · ${message.thinkingTokens} tokens")
            },
            body = finalThinking,
            markdown = true,
            sources = emptyList()
        )
    }
    return steps
}

/** Pull the source domains out of a web_search / web_fetch result for display. */
private fun extractSources(tc: ToolCallInfo): List<WebSource> = try {
    when (tc.name) {
        "web_search" -> {
            val arr = org.json.JSONObject(tc.result).optJSONArray("results")
            if (arr == null) {
                emptyList()
            } else {
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    // Production web_search hides the full URL behind a "ddg:"
                    // ref, so usually only the domain is available here.
                    val rawUrl = o.optString("url").ifBlank { null }
                    val domain = o.optString("domain").ifBlank { rawUrl?.let { hostOf(it) } ?: "" }
                    if (domain.isBlank()) null else WebSource(domain, o.optString("title"), rawUrl)
                }.distinctBy { it.domain }
            }
        }
        "web_fetch" -> {
            val o = org.json.JSONObject(tc.result)
            val rawUrl = o.optString("url").ifBlank { null }
            val domain = rawUrl?.let { hostOf(it) } ?: ""
            if (domain.isBlank()) emptyList() else listOf(WebSource(domain, o.optString("title"), rawUrl))
        }
        else -> emptyList()
    }
} catch (_: Exception) {
    emptyList()
}

/** Bare host without the leading "www.", or "" if the URL can't be parsed. */
private fun hostOf(url: String): String = try {
    (java.net.URI(url).host ?: "").removePrefix("www.")
} catch (_: Exception) {
    ""
}
