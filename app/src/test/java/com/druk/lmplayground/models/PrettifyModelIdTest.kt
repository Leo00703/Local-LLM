package com.druk.lmplayground.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a server's raw model id becomes the name shown in the picker. LM Studio ids are
 * already tidy ("qwen/qwen3-4b"); llama.cpp and Ollama return file names, Hugging Face
 * repo names and tags, which used to surface as "Qwen 3 4B Q4 K M.gguf".
 */
class PrettifyModelIdTest {

    private fun pretty(id: String) = ModelInfoProvider.prettifyModelId(id)

    // --- LM Studio style ids keep their look ------------------------------------------

    @Test
    fun lmStudioIdsAreUnchanged() {
        assertEquals("Qwen 3.5 4B MTP", pretty("qwen3.5-4b-mtp"))
        assertEquals("Gemma 4 12B QAT", pretty("google/gemma-4-12b-qat"))
        assertEquals("Qwen 3 4B 2507", pretty("qwen/qwen3-4b-2507"))
        assertEquals("GPT OSS 20B", pretty("openai/gpt-oss-20b"))
    }

    // --- llama.cpp ---------------------------------------------------------------------

    @Test
    fun aGgufFileNameLosesItsExtensionAndKeepsTheQuant() {
        assertEquals("Qwen 3 4B (Q4_K_M)", pretty("Qwen3-4B-Q4_K_M.gguf"))
        assertEquals("Gemma 4 E4B IT (Q4_K_M)", pretty("gemma-4-E4B-it-Q4_K_M.gguf"))
        assertEquals("LFM 2.5 1.2B Instruct (Q4_K_M)", pretty("LFM2.5-1.2B-Instruct-Q4_K_M.gguf"))
        assertEquals("Meta Llama 3.1 8B Instruct (IQ4_XS)", pretty("Meta-Llama-3.1-8B-Instruct-IQ4_XS.gguf"))
    }

    @Test
    fun aHuggingFaceRepoAndTagReadsAsOneModel() {
        assertEquals("Gemma 3 4B IT (Q4_K_M)", pretty("ggml-org/gemma-3-4b-it-GGUF:Q4_K_M"))
        assertEquals("Qwen 3 30B A3B (UD-Q4_K_XL)", pretty("unsloth/Qwen3-30B-A3B-GGUF:UD-Q4_K_XL"))
    }

    // bartowski's repos repeat the publisher in the model name.
    @Test
    fun aRepeatedPublisherWordIsDropped() {
        assertEquals("Qwen 3 4B (Q4_K_M)", pretty("hf.co/bartowski/Qwen_Qwen3-4B-GGUF:Q4_K_M"))
    }

    @Test
    fun aShardSuffixIsDropped() {
        assertEquals("Qwen 3 235B A22B (Q4_K_M)", pretty("Qwen3-235B-A22B-Q4_K_M-00001-of-00003.gguf"))
    }

    @Test
    fun theQuantCanSitInTheMiddleOrFollowADot() {
        assertEquals("Gemma 4 E2B IT (Q4_0)", pretty("gemma-4-E2B_q4_0-it.gguf"))
        assertEquals("Mistral 7B Instruct V0.3 (Q5_K_M)", pretty("/models/Mistral-7B-Instruct-v0.3.Q5_K_M.gguf"))
        assertEquals("GPT OSS 20B (MXFP4)", pretty("gpt-oss-20b-mxfp4.gguf"))
    }

    // Two quants of one model must stay distinguishable in the list.
    @Test
    fun differentQuantsOfOneModelGiveDifferentNames() {
        assertEquals(
            2,
            setOf(pretty("Qwen3-4B-Q4_K_M.gguf"), pretty("Qwen3-4B-Q8_0.gguf")).size
        )
    }

    // --- Ollama ------------------------------------------------------------------------

    @Test
    fun ollamaTagsReadAsSizes() {
        assertEquals("Qwen 3 4B", pretty("qwen3:4b"))
        assertEquals("Gemma 3 4B IT QAT", pretty("gemma3:4b-it-qat"))
    }

    @Test
    fun theLatestTagIsNotShown() {
        assertEquals("Llama 3.2", pretty("llama3.2:latest"))
    }

    @Test
    fun aLmStudioQuantVariantReadsLikeTheOthers() {
        assertEquals("Qwen 3 4B (Q8_0)", pretty("qwen3-4b@q8_0"))
    }

    // --- edges -------------------------------------------------------------------------

    // Nothing left to show: fall back to the raw id rather than a blank row.
    @Test
    fun anIdThatIsOnlyAQuantIsReturnedAsIs() {
        assertEquals("Q4_K_M", pretty("Q4_K_M"))
        assertEquals("", pretty(""))
    }

    // A model named with a Q-word must not be mistaken for a quant.
    @Test
    fun familyNamesThatStartWithQAreNotQuants() {
        assertEquals("Qwen 3 4B", pretty("qwen3-4b"))
        assertEquals("QWQ 32B", pretty("qwq-32b"))
    }
}
