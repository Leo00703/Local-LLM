package com.druk.lmplayground.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * Render a LaTeX formula to a transparent bitmap (black glyphs, later tinted to the
 * theme colour via ColorFilter). Returns null if jlatexmath can't parse it, so the
 * caller can fall back to a cleaned-up Unicode string (the "hybrid" behaviour).
 */
private fun latexToImageBitmap(latex: String, textSizePx: Float): ImageBitmap? {
    val formula = latex.trim()
    if (formula.isEmpty()) return null
    return try {
        val drawable = JLatexMathDrawable.builder(formula)
            .textSize(textSizePx)
            .padding(0)
            .background(0) // fully transparent
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    } catch (t: Throwable) {
        // Invalid / unsupported LaTeX — let the caller use the text fallback.
        null
    }
}

/**
 * Display ("block") math: a centred, horizontally scrollable formula. Tries to render
 * the real LaTeX; on failure shows the Unicode-cleaned text so nothing is ever lost.
 */
@Composable
fun MathBlock(latex: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    // Display math a touch larger than body text.
    val textSizePx = with(density) { 19.sp.toPx() }
    val image = remember(latex, textSizePx) { latexToImageBitmap(latex, textSizePx) }
    if (image != null) {
        val scroll = rememberScrollState()
        Box(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(scroll),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                colorFilter = ColorFilter.tint(color)
            )
        }
    } else {
        Text(
            text = cleanupLatexToText(latex),
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            color = color,
            modifier = modifier.fillMaxWidth()
        )
    }
}

private val GREEK = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
    "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ",
    "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν",
    "xi" to "ξ", "omicron" to "ο", "pi" to "π", "rho" to "ρ", "sigma" to "σ",
    "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ",
    "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
    "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ",
    "Omega" to "Ω"
)

private val SYMBOLS = mapOf(
    "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
    "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
    "ll" to "≪", "gg" to "≫", "approx" to "≈", "equiv" to "≡", "cong" to "≅",
    "sim" to "∼", "simeq" to "≃", "propto" to "∝", "infty" to "∞",
    "partial" to "∂", "nabla" to "∇", "rightarrow" to "→", "to" to "→",
    "leftarrow" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
    "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "mapsto" to "↦",
    "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮",
    "forall" to "∀", "exists" to "∃", "nexists" to "∄", "in" to "∈", "notin" to "∉",
    "ni" to "∋", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃",
    "supseteq" to "⊇", "cup" to "∪", "cap" to "∩", "setminus" to "∖",
    "emptyset" to "∅", "varnothing" to "∅", "angle" to "∠", "perp" to "⊥",
    "parallel" to "∥", "degree" to "°", "circ" to "∘", "bullet" to "•",
    "ast" to "∗", "star" to "⋆", "dagger" to "†", "ldots" to "…", "cdots" to "⋯",
    "vdots" to "⋮", "ddots" to "⋱", "land" to "∧", "wedge" to "∧", "lor" to "∨",
    "vee" to "∨", "neg" to "¬", "oplus" to "⊕", "otimes" to "⊗", "hbar" to "ℏ",
    "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ", "quad" to "  ",
    "qquad" to "    ", "prime" to "′"
)

private val SUPERSCRIPTS = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵',
    '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻',
    '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ'
)

private val SUBSCRIPTS = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅',
    '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋',
    '=' to '₌', '(' to '₍', ')' to '₎'
)

private fun toScript(text: String, map: Map<Char, Char>): String {
    // Only convert if every char has a script form, else keep ^{..}/_{..} markers.
    if (text.all { map.containsKey(it) }) {
        return buildString { for (c in text) append(map.getValue(c)) }
    }
    return if (map === SUPERSCRIPTS) "^$text" else "_$text"
}

// Constant patterns for cleanupLatexToText, hoisted out of the function body.
// MarkdownContent re-parses the whole growing response on every streamed token,
// invoking cleanupLatexToText for each inline math span, so compiling these
// inline recompiled ~11 identical Patterns per call, purely as GC churn on the
// main thread during rendering. Compile them once.
private val LTX_FONT_WRAPPER = Regex("""\\(?:text|textbf|textit|textrm|texttt|mathrm|mathbf|mathit|mathsf|mathtt|mathcal|mathbb|boldsymbol|operatorname)\s*\{([^{}]*)\}""")
private val LTX_FRAC = Regex("""\\frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}""")
private val LTX_SQRT = Regex("""\\sqrt\s*\{([^{}]*)\}""")
private val LTX_DELIMS = Regex("""\\(?:left|right|bigl|bigr|biggl|biggr|big|Big|bigg|Bigg)\b""")
private val LTX_SPACING = Regex("""\\[,;:!]""")
private val LTX_SUP_BRACE = Regex("""\^\{([^{}]*)\}""")
private val LTX_SUP_CHAR = Regex("""\^(\w)""")
private val LTX_SUB_BRACE = Regex("""_\{([^{}]*)\}""")
private val LTX_SUB_CHAR = Regex("""_(\w)""")
private val LTX_NAMED_CMD = Regex("""\\([a-zA-Z]+)""")
private val LTX_MULTI_SPACE = Regex("""[ \t]{2,}""")

/**
 * Best-effort conversion of a LaTeX fragment to readable Unicode text. Used both as
 * the fallback when jlatexmath fails and for inline `$...$` snippets (which read fine
 * as plain text, e.g. `$256\text{ bit}$` -> "256 bit").
 */
fun cleanupLatexToText(input: String): String {
    var s = input.trim()
    // Strip math delimiters if a whole fragment was passed in with them.
    if (s.startsWith("$$") && s.endsWith("$$") && s.length >= 4) s = s.substring(2, s.length - 2)
    else if (s.startsWith("$") && s.endsWith("$") && s.length >= 2) s = s.substring(1, s.length - 1)
    if (s.startsWith("\\[") && s.endsWith("\\]")) s = s.substring(2, s.length - 2)
    if (s.startsWith("\\(") && s.endsWith("\\)")) s = s.substring(2, s.length - 2)

    // Font/format wrappers -> their contents.
    s = LTX_FONT_WRAPPER.replace(s) { it.groupValues[1] }
    // Fractions and roots.
    s = LTX_FRAC.replace(s) { "(${it.groupValues[1]})/(${it.groupValues[2]})" }
    s = LTX_SQRT.replace(s) { "√(${it.groupValues[1]})" }
    // Delimiters / spacing commands.
    s = s.replace(LTX_DELIMS, "")
    s = s.replace(LTX_SPACING, " ")
    s = s.replace("\\\\", " ")
    // Super/sub-scripts.
    s = LTX_SUP_BRACE.replace(s) { toScript(it.groupValues[1], SUPERSCRIPTS) }
    s = LTX_SUP_CHAR.replace(s) { toScript(it.groupValues[1], SUPERSCRIPTS) }
    s = LTX_SUB_BRACE.replace(s) { toScript(it.groupValues[1], SUBSCRIPTS) }
    s = LTX_SUB_CHAR.replace(s) { toScript(it.groupValues[1], SUBSCRIPTS) }
    // Named commands -> Greek letters / symbols (unknown ones just lose the backslash).
    s = LTX_NAMED_CMD.replace(s) { m ->
        val w = m.groupValues[1]
        GREEK[w] ?: SYMBOLS[w] ?: w
    }
    // Leftover grouping braces and tidy whitespace.
    s = s.replace("{", "").replace("}", "")
    s = s.replace(LTX_MULTI_SPACE, " ").trim()
    return s
}
