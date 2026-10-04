package com.druk.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeErrorsTest {

    @Test
    fun anEmptyRecordGivesNothingToShow() {
        assertEquals("", NativeErrors.summarize(""))
        assertEquals("", NativeErrors.summarize("\n  \n\n"))
    }

    @Test
    fun aShortRecordIsKeptAsIs() {
        val raw = "llama_model_load: error loading model: missing tensor 'blk.0.attn_qkv.weight'"
        assertEquals(raw, NativeErrors.summarize(raw))
    }

    // The reason comes last: earlier lines are the build-up to it.
    @Test
    fun onlyTheLastLinesAreKept() {
        val raw = (1..8).joinToString("\n") { "error $it" }
        assertEquals("error 6\nerror 7\nerror 8", NativeErrors.summarize(raw))
        assertEquals("error 8", NativeErrors.summarize(raw, maxLines = 1))
    }

    // A failing backend repeats one line for every tensor.
    @Test
    fun consecutiveRepeatsCollapse() {
        val raw = "alloc failed\nalloc failed\nalloc failed\nalloc failed\nout of memory"
        assertEquals("alloc failed\nout of memory", NativeErrors.summarize(raw))
    }

    @Test
    fun aRepeatSeparatedByAnotherLineIsNotMerged() {
        assertEquals("a\nb\na", NativeErrors.summarize("a\nb\na"))
    }

    @Test
    fun aLongLineIsCutWithAnEllipsis() {
        val out = NativeErrors.summarize("x".repeat(500), maxChars = 100)
        assertEquals(100, out.length)
        assertEquals('\u2026', out.last())
    }

    @Test
    fun surroundingWhitespaceAndWindowsLineEndingsAreIgnored() {
        assertEquals("one\ntwo", NativeErrors.summarize("  one  \r\n\r\n  two\r\n"))
    }
}
