package com.druk.lmplayground.conversation

/**
 * Rough token estimate WITHOUT invoking the model tokenizer — the single source of
 * truth for the file chips and the live context-ring estimate. Script-aware: CJK
 * characters are counted ~1 token each, other text ~4 chars/token (plain chars/4
 * badly underestimates Chinese/Japanese/Korean). For Latin text it stays ≈ chars/4,
 * so it never regresses the previous behaviour.
 *
 * The context ring still self-corrects to the REAL KV token count after each turn
 * (via the backend getReport()); the exact per-model tokenizer lands in a later
 * native build.
 */
fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    for (c in text) {
        if (isCjkChar(c)) cjk++ else other++
    }
    return cjk + (other + 3) / 4
}

private fun isCjkChar(c: Char): Boolean {
    val code = c.code
    return code in 0x4E00..0x9FFF ||   // CJK Unified Ideographs
        code in 0x3040..0x30FF ||      // Hiragana + Katakana
        code in 0xAC00..0xD7A3 ||      // Hangul syllables
        code in 0x3400..0x4DBF ||      // CJK Extension A
        code in 0xF900..0xFAFF         // CJK Compatibility Ideographs
}
