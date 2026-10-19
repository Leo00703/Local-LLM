package com.druk.lmplayground.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfMtpModelsTest {

    @Test
    fun `catalog Qwen 3_5 files are supported`() {
        assertTrue(SelfMtpModels.supports("Qwen_Qwen3.5-4B-Q4_K_M.gguf"))
        assertTrue(SelfMtpModels.supports("Qwen_Qwen3.5-0.8B-Q3_K_M.gguf"))
    }

    @Test
    fun `other spellings of the family are supported`() {
        assertTrue(SelfMtpModels.supports("Qwen3.6-35B-A3B-MTP-Q4_K_M.gguf"))
        assertTrue(SelfMtpModels.supports("qwen3.5-9b-instruct.GGUF"))
    }

    @Test
    fun `other models are not supported`() {
        assertFalse(SelfMtpModels.supports("Qwen_Qwen3-4B-Q4_K_M.gguf"))
        assertFalse(SelfMtpModels.supports("gemma-4-E4B-it-Q4_K_M.gguf"))
        assertFalse(SelfMtpModels.supports("Llama-3.2-3B-Instruct-Q4_K_M.gguf"))
    }

    @Test
    fun `projectors, remote and LiteRT models are never supported`() {
        assertFalse(SelfMtpModels.supports("mmproj-Qwen_Qwen3.5-4B-f16.gguf"))
        assertFalse(SelfMtpModels.supports("remote:Qwen3.5-4B"))
        assertFalse(SelfMtpModels.supports("gemma-4-E4B-it.litertlm"))
        assertFalse(SelfMtpModels.supports(null))
        assertFalse(SelfMtpModels.supports(""))
    }
}
