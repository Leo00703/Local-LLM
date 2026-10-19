package com.druk.lmplayground.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtpModelsTest {

    @Test
    fun `catalog Qwen 3_5 files have their own head`() {
        assertTrue(MtpModels.hasOwnHead("Qwen_Qwen3.5-4B-Q4_K_M.gguf"))
        assertTrue(MtpModels.hasOwnHead("Qwen_Qwen3.5-0.8B-Q3_K_M.gguf"))
    }

    @Test
    fun `other spellings of the Qwen family have their own head`() {
        assertTrue(MtpModels.hasOwnHead("Qwen3.6-35B-A3B-MTP-Q4_K_M.gguf"))
        assertTrue(MtpModels.hasOwnHead("qwen3.5-9b-instruct.GGUF"))
    }

    @Test
    fun `Gemma 4 files need a drafter and have no own head`() {
        listOf(
            "gemma-4-E2B_q4_0-it.gguf",
            "gemma-4-E4B-it-Q4_K_M.gguf",
            "gemma-4-12b-it-qat-q4_0.gguf",
        ).forEach {
            assertTrue(it, MtpModels.needsDrafter(it))
            assertFalse(it, MtpModels.hasOwnHead(it))
            assertTrue(it, MtpModels.supports(it))
        }
    }

    @Test
    fun `other models are not supported`() {
        listOf(
            "Qwen_Qwen3-4B-Q4_K_M.gguf",
            "gemma-3-4b-it-Q4_K_M.gguf",
            "gemma-3n-E4B-it-Q4_K_M.gguf",
            "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        ).forEach { assertFalse(it, MtpModels.supports(it)) }
    }

    @Test
    fun `projectors, drafters, remote and LiteRT models are never supported`() {
        listOf(
            "mmproj-Qwen_Qwen3.5-4B-f16.gguf",
            "mmproj-gemma-4-E4B-it-Q8_0.gguf",
            "mtp-gemma-4-E4B-it.gguf",
            "remote:Qwen3.5-4B",
            "gemma-4-E4B-it.litertlm",
            "",
        ).forEach { assertFalse(it, MtpModels.supports(it)) }
        assertFalse(MtpModels.supports(null))
    }

    @Test
    fun `drafter files are recognised by name`() {
        assertTrue(MtpModels.isDrafterFile("mtp-gemma-4-E4B-it.gguf"))
        assertTrue(MtpModels.isDrafterFile("MTP-gemma-4-12B-it-Q4_0.GGUF"))
        assertFalse(MtpModels.isDrafterFile("gemma-4-E4B-it-Q4_K_M.gguf"))
        assertFalse(MtpModels.isDrafterFile("mtp-notes.txt"))
    }
}
