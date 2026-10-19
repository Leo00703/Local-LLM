package com.druk.lmplayground.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MTP drafters of the Gemma 4 catalog models. A drafter is downloaded on demand into
 * the models folder and found again purely by file name, so a typo here is a switch that
 * downloads a file the engine never uses (or lists as a model of its own).
 */
class DrafterAddOnTest {

    private fun byName(name: String): ModelInfo =
        requireNotNull(ModelInfoProvider.allModels.find { it.name == name }) { "Model not found: $name" }

    private val withDrafter: List<ModelInfo> get() = ModelInfoProvider.allModels.filter { it.drafterAddOn != null }

    @Test
    fun everyGemma4SizeOffersADrafter() {
        listOf(
            "Gemma 4 E2B", "Gemma 4 E4B", "Gemma 4 12B",
            "Gemma 4 E2B (Q4_K_M)", "Gemma 4 E4B (Q4_K_M)",
        ).forEach { name ->
            assertNotNull("$name should offer a drafter", byName(name).drafterAddOn)
        }
    }

    @Test
    fun otherModelsOfferNone() {
        listOf("Qwen 3.5 4B", "Gemma 3 4B", "Llama 3.2 3B", "Phi-4 mini")
            .forEach { name -> assertNull("$name must not offer a drafter", byName(name).drafterAddOn) }
    }

    // The model list hides drafters by name and MtpModels picks the switch's mode by name.
    @Test
    fun everyDrafterLooksLikeOneAndBelongsToAGemma4Model() {
        withDrafter.forEach { m ->
            val d = m.drafterAddOn!!
            assertTrue("${d.filename} must be recognised as a drafter", MtpModels.isDrafterFile(d.filename))
            assertTrue("${m.name} must be treated as needing a drafter", MtpModels.needsDrafter(m.filename))
            assertTrue("${d.filename} must be a hosted https file", d.url.startsWith("https://huggingface.co/"))
            assertTrue("${d.filename} url must end with a gguf file", d.url.endsWith(".gguf"))
            assertTrue("${d.filename} needs a size for the prompt", d.sizeLabel.endsWith("Mb"))
        }
    }

    // E2B's QAT and Q4_K_M builds share one drafter file: it must be the same download.
    @Test
    fun aSharedDrafterFileHasOneUrlAndOneSize() {
        withDrafter.groupBy { it.drafterAddOn!!.filename }.forEach { (file, models) ->
            assertEquals("$file must resolve to one url", 1, models.map { it.drafterAddOn!!.url }.distinct().size)
            assertEquals("$file must have one size", 1, models.map { it.drafterAddOn!!.sizeLabel }.distinct().size)
        }
    }
}
