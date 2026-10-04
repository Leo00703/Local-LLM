package com.druk.lmplayground.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The GPU TopK sampler is a native plugin the LiteRT-LM runtime loads by name. If it does not
 * load, the runtime silently falls back to CPU sampling and speculative decoding becomes about
 * 2.5x slower than plain decode, so nothing crashes and nothing is logged where a user looks.
 * These pin what the file must be, read straight from its ELF headers.
 */
class LiteRtSamplerLibraryTest {

    // Gradle runs unit tests with the module (app/) as the working directory; accept the
    // repository root too so the test also works when run from an IDE.
    private fun module(path: String): File =
        listOf("src/main/$path", "app/src/main/$path").map(::File).firstOrNull { it.isFile }
            ?: error("$path not found")

    private fun gradleFile(): File =
        listOf("build.gradle.kts", "app/build.gradle.kts").map(::File).first { it.isFile }

    private val samplerPath = "jniLibs/arm64-v8a/libLiteRtTopKOpenClSampler.so"

    /** Just enough ELF64 to read the dynamic section and the dynamic symbol table. */
    private class Elf(bytes: ByteArray) {
        val b: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val machine: Int
        val loadAlignments = mutableListOf<Long>()
        val needed = mutableListOf<String>()
        val defined = mutableSetOf<String>()
        val undefined = mutableSetOf<String>()

        init {
            require(bytes.size > 64 && b.getInt(0) == 0x464c457f) { "not an ELF file" }
            require(bytes[4].toInt() == 2) { "not ELF64" }
            machine = b.getShort(0x12).toInt() and 0xFFFF

            val phOff = b.getLong(0x20).toInt()
            val phEnt = b.getShort(0x36).toInt() and 0xFFFF
            val phNum = b.getShort(0x38).toInt() and 0xFFFF
            for (i in 0 until phNum) {
                val o = phOff + i * phEnt
                if (b.getInt(o) == 1) loadAlignments += b.getLong(o + 48)   // PT_LOAD, p_align
            }

            val shOff = b.getLong(0x28).toInt()
            val shEnt = b.getShort(0x3A).toInt() and 0xFFFF
            val shNum = b.getShort(0x3C).toInt() and 0xFFFF
            fun sec(i: Int) = shOff + i * shEnt
            for (i in 0 until shNum) {
                val o = sec(i)
                val type = b.getInt(o + 4)
                val off = b.getLong(o + 24).toInt()
                val size = b.getLong(o + 32).toInt()
                val strtab = sec(b.getInt(o + 40))
                val strOff = b.getLong(strtab + 24).toInt()
                when (type) {
                    6 -> for (e in 0 until size / 16) {          // SHT_DYNAMIC: DT_NEEDED entries
                        val tag = b.getLong(off + e * 16)
                        if (tag == 0L) break
                        if (tag == 1L) needed += cstr(strOff + b.getLong(off + e * 16 + 8).toInt())
                    }
                    11 -> for (e in 1 until size / 24) {         // SHT_DYNSYM
                        val s = off + e * 24
                        val name = cstr(strOff + b.getInt(s))
                        if (name.isEmpty()) continue
                        if ((b.getShort(s + 6).toInt() and 0xFFFF) != 0) defined += name else undefined += name
                    }
                }
            }
        }

        private fun cstr(o: Int): String {
            var e = o
            while (b.get(e).toInt() != 0) e++
            return String(b.array(), o, e - o, Charsets.UTF_8)
        }
    }

    private val elf by lazy { Elf(module(samplerPath).readBytes()) }

    @Test
    fun isAnArm64SharedObject() {
        assertEquals("EM_AARCH64", 183, elf.machine)
    }

    // 0.17.0 folded libLiteRt.so into liblitertlm_jni.so, so the library no longer exists. The old
    // patched sampler carried it as a DT_NEEDED and fails to load without it.
    @Test
    fun doesNotNeedTheRuntimeLibraryThatNoLongerExists() {
        assertFalse(elf.needed.toString(), "libLiteRt.so" in elf.needed)
    }

    @Test
    fun hasNoUnresolvedLiteRtSymbols() {
        val missing = elf.undefined.filter { it.startsWith("LiteRt") }
        assertTrue("these would have to come from a library that is not there: $missing", missing.isEmpty())
    }

    @Test
    fun exportsTheEntryPointsTheRuntimeLooksUp() {
        listOf(
            "LiteRtTopKOpenClSampler_Create",
            "LiteRtTopKOpenClSampler_Destroy",
            "LiteRtTopKOpenClSampler_SampleToIdAndScoreBuffer",
            "LiteRtTopKOpenClSampler_UpdateConfig",
        ).forEach { assertTrue("$it is not exported", it in elf.defined) }
    }

    // Devices with 16 KB pages refuse a library whose segments are only 4 KB aligned.
    @Test
    fun isAlignedForSixteenKilobytePages() {
        assertTrue(elf.loadAlignments.toString(), elf.loadAlignments.isNotEmpty())
        elf.loadAlignments.forEach { assertTrue("alignment $it", it >= 0x4000) }
    }

    // The sampler must come from the same release as the runtime it plugs into.
    @Test
    fun provenanceMatchesTheRuntimeVersionInTheBuild() {
        val version = Regex("""litertlm-android:([0-9][0-9.]*)""")
            .find(gradleFile().readText())?.groupValues?.get(1)
            ?: error("litertlm-android version not found in build.gradle.kts")
        val readme = module("jniLibs/README.md").readText()
        assertTrue("jniLibs/README.md must record tag v$version", readme.contains("tag **v$version**"))
    }
}
