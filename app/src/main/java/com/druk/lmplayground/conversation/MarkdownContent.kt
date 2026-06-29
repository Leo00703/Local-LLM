package com.druk.lmplayground.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.druk.lmplayground.R
import kotlinx.coroutines.delay
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Block
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

private val richParser: Parser by lazy {
    Parser.builder()
        .extensions(
            listOf(
                StrikethroughExtension.create(),
                TablesExtension.create()
            )
        )
        .build()
}

// Display math on its own: $$ ... $$ or \[ ... \] (kept out of the markdown parser
// so backslashes / underscores in LaTeX aren't mangled by markdown).
private val blockMathRegex = Regex("""\$\$(.+?)\$\$|\\\[(.+?)\\\]""", RegexOption.DOT_MATCHES_ALL)
// Inline math inside a line: $ ... $ or \( ... \). Rendered as cleaned Unicode text.
private val inlineMathRegex = Regex("""\$([^\$\n]+?)\$|\\\((.+?)\\\)""")

private const val CODE_COLLAPSE_LINES = 14

/**
 * Rich renderer for assistant output: real block-level layout instead of one flat
 * AnnotatedString. Paragraphs/lists/quotes/headings render as selectable text;
 * fenced code becomes a copyable, collapsible code card; GFM tables render as a real
 * grid; `$$..$$` / `$..$` math is rendered (jlatexmath) or cleaned to Unicode.
 */
@Composable
fun MarkdownContent(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val segments = splitBlockMath(text)
        var first = true
        for (seg in segments) {
            if (!first) Spacer(Modifier.height(6.dp))
            when (seg) {
                is Seg.Math -> MathBlock(seg.latex, Modifier.padding(vertical = 4.dp))
                is Seg.Md -> MarkdownBlocks(seg.text, primary)
            }
            first = false
        }
    }
}

private sealed interface Seg {
    data class Md(val text: String) : Seg
    data class Math(val latex: String) : Seg
}

private fun splitBlockMath(text: String): List<Seg> {
    val out = mutableListOf<Seg>()
    var last = 0
    for (m in blockMathRegex.findAll(text)) {
        val pre = text.substring(last, m.range.first)
        if (pre.isNotBlank()) out.add(Seg.Md(pre))
        val latex = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (latex.isNotBlank()) out.add(Seg.Math(latex))
        last = m.range.last + 1
    }
    val tail = if (last < text.length) text.substring(last) else ""
    if (tail.isNotBlank()) out.add(Seg.Md(tail))
    if (out.isEmpty()) out.add(Seg.Md(text))
    return out
}

@Composable
private fun MarkdownBlocks(markdown: String, primary: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val ctx = InlineCtx(
        colorScheme = colorScheme,
        primary = primary,
        codeBg = colorScheme.surfaceContainerHigh
    )
    val document = richParser.parse(markdown)
    var node = document.firstChild
    var first = true
    while (node != null) {
        if (!first) Spacer(Modifier.height(8.dp))
        when (val n = node) {
            is FencedCodeBlock -> CodeBlock(
                code = n.literal.trimEnd('\n'),
                language = n.info?.trim()?.takeIf { it.isNotEmpty() }
            )
            is IndentedCodeBlock -> CodeBlock(code = n.literal.trimEnd('\n'), language = null)
            is TableBlock -> MarkdownTable(n, ctx)
            else -> {
                val styled = buildAnnotatedString { appendNode(n, ctx, isFirst = true, listDepth = 0) }
                if (styled.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            text = styled,
                            style = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current)
                        )
                    }
                }
            }
        }
        first = false
        node = node.next
    }
}

@Composable
private fun CodeBlock(code: String, language: String?) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val totalLines = remember(code) { code.count { it == '\n' } + 1 }
    val collapsible = totalLines > CODE_COLLAPSE_LINES
    if (copied) {
        LaunchedEffect(Unit) {
            delay(1500)
            copied = false
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                if (copied) {
                    Text(
                        text = stringResource(R.string.copied),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.copy),
                        modifier = Modifier.size(15.dp),
                        tint = if (copied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
                }
            }
            val hScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(hScroll)
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    maxLines = if (collapsible && !expanded) CODE_COLLAPSE_LINES else Int.MAX_VALUE,
                    overflow = TextOverflow.Clip
                )
            }
            if (collapsible) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(if (expanded) R.string.show_less else R.string.show_more),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: TableBlock, ctx: InlineCtx) {
    val header = mutableListOf<List<AnnotatedString>>()
    val body = mutableListOf<List<AnnotatedString>>()
    var section = table.firstChild
    while (section != null) {
        val target = if (section is TableHead) header else body
        var row = section.firstChild
        while (row != null) {
            if (row is TableRow) {
                val cells = mutableListOf<AnnotatedString>()
                var cell = row.firstChild
                while (cell != null) {
                    if (cell is TableCell) {
                        cells.add(buildAnnotatedString { appendChildren(cell, ctx, listDepth = 0) })
                    }
                    cell = cell.next
                }
                target.add(cells)
            }
            row = row.next
        }
        section = section.next
    }
    val colCount = (header + body).maxOfOrNull { it.size } ?: 0
    if (colCount == 0) return
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val hScroll = rememberScrollState()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.horizontalScroll(hScroll)) {
            val allRows = header + body
            allRows.forEachIndexed { index, row ->
                val isHeader = index < header.size
                Row {
                    for (c in 0 until colCount) {
                        val cellText = row.getOrNull(c) ?: AnnotatedString("")
                        Box(
                            modifier = Modifier
                                .width(150.dp)
                                .border(0.5.dp, borderColor)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = cellText,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private class InlineCtx(
    val colorScheme: androidx.compose.material3.ColorScheme,
    val primary: Boolean,
    val codeBg: Color
)

private fun codeSpan(ctx: InlineCtx) = SpanStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    background = ctx.codeBg
)

private fun headingSize(level: Int) = when (level) {
    1 -> 22.sp
    2 -> 19.sp
    3 -> 17.sp
    4 -> 16.sp
    5 -> 15.sp
    else -> 14.sp
}

/**
 * Append a single markdown node (block or inline) into the current AnnotatedString.
 * Mirrors the inline-only formatter but adds inline-math cleanup; top-level code
 * blocks / tables are handled separately as their own composables.
 */
private fun AnnotatedString.Builder.appendNode(
    node: Node,
    ctx: InlineCtx,
    isFirst: Boolean,
    listDepth: Int,
    orderedIndex: Int? = null
) {
    when (node) {
        is Heading -> {
            if (!isFirst) append("\n\n")
            pushStyle(SpanStyle(fontSize = headingSize(node.level), fontWeight = FontWeight.Bold))
            appendChildren(node, ctx, listDepth)
            pop()
        }

        is Paragraph -> {
            val insideListItem = node.parent is ListItem
            if (!isFirst && !insideListItem) append("\n\n")
            appendChildren(node, ctx, listDepth)
        }

        is BlockQuote -> {
            if (!isFirst) append("\n\n")
            pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = ctx.colorScheme.outline))
            append("│ ")
            appendChildren(node, ctx, listDepth)
            pop()
        }

        is BulletList -> {
            if (!isFirst) append(if (node.parent is ListItem) "\n" else "\n\n")
            var child = node.firstChild
            var childFirst = true
            while (child != null) {
                appendNode(child, ctx, childFirst, listDepth + 1)
                childFirst = false
                child = child.next
            }
        }

        is OrderedList -> {
            if (!isFirst) append(if (node.parent is ListItem) "\n" else "\n\n")
            var child = node.firstChild
            var index = node.markerStartNumber ?: 1
            var childFirst = true
            while (child != null) {
                appendNode(child, ctx, childFirst, listDepth + 1, index)
                index++
                childFirst = false
                child = child.next
            }
        }

        is ListItem -> {
            if (!isFirst) append("\n")
            val indent = "  ".repeat((listDepth - 1).coerceAtLeast(0))
            append(if (orderedIndex != null) "$indent$orderedIndex. " else "$indent• ")
            appendChildren(node, ctx, listDepth)
        }

        is ThematicBreak -> {
            if (!isFirst) append("\n\n")
            pushStyle(SpanStyle(color = ctx.colorScheme.outline))
            append("⸻")
            pop()
        }

        // Code blocks nested inside a quote/list (rare): inline monospace fallback.
        is FencedCodeBlock -> {
            if (!isFirst) append("\n\n")
            pushStyle(codeSpan(ctx))
            append(node.literal.trimEnd('\n'))
            pop()
        }

        is IndentedCodeBlock -> {
            if (!isFirst) append("\n\n")
            pushStyle(codeSpan(ctx))
            append(node.literal.trimEnd('\n'))
            pop()
        }

        is StrongEmphasis -> {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            appendChildren(node, ctx, listDepth)
            pop()
        }

        is Emphasis -> {
            pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
            appendChildren(node, ctx, listDepth)
            pop()
        }

        is Code -> {
            pushStyle(codeSpan(ctx))
            append(node.literal)
            pop()
        }

        is Link -> {
            val linkColor = if (ctx.primary) ctx.colorScheme.inversePrimary else ctx.colorScheme.primary
            pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            appendChildren(node, ctx, listDepth)
            pop()
        }

        is Strikethrough -> {
            pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
            appendChildren(node, ctx, listDepth)
            pop()
        }

        is Image -> appendChildren(node, ctx, listDepth)

        is HardLineBreak -> append("\n")
        is SoftLineBreak -> append("\n")
        is MdText -> appendInlineText(node.literal)
        is HtmlInline -> append(node.literal)
        is HtmlBlock -> {
            if (!isFirst) append("\n\n")
            append(node.literal.trim())
        }

        else -> appendChildren(node, ctx, listDepth)
    }
}

private fun AnnotatedString.Builder.appendChildren(
    parent: Node,
    ctx: InlineCtx,
    listDepth: Int
) {
    var child = parent.firstChild
    var first = true
    while (child != null) {
        appendNode(child, ctx, first, listDepth)
        if (child is Block) first = false
        child = child.next
    }
}

/** Append a text run, converting any inline `$..$` / `\(..\)` math to readable Unicode. */
private fun AnnotatedString.Builder.appendInlineText(literal: String) {
    if (!literal.contains('$') && !literal.contains("\\(")) {
        append(literal)
        return
    }
    var last = 0
    for (m in inlineMathRegex.findAll(literal)) {
        if (m.range.first > last) append(literal.substring(last, m.range.first))
        val latex = m.groupValues[1].ifEmpty { m.groupValues[2] }
        append(cleanupLatexToText(latex))
        last = m.range.last + 1
    }
    if (last < literal.length) append(literal.substring(last))
}
