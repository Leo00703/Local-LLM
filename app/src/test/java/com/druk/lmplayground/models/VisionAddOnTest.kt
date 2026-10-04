package com.druk.lmplayground.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog's image add-ons (multimodal projectors). They are downloaded on demand
 * from the chat, land in the same folder as the models, and are paired back to their
 * model purely by file name, so a typo in the catalog shows up as a vision model that
 * quietly never gets vision. These pin the properties that keep that from happening.
 */
class VisionAddOnTest {

    private fun byName(name: String): ModelInfo =
        requireNotNull(ModelInfoProvider.allModels.find { it.name == name }) { "Model not found: $name" }

    private val withAddOn: List<ModelInfo> get() = ModelInfoProvider.allModels.filter { it.visionAddOn != null }

    @Test
    fun theExpectedFamiliesOfferAnAddOn() {
        listOf(
            "Qwen 3.5 0.8B", "Qwen 3.5 2B", "Qwen 3.5 4B", "Gemma 3 4B",
            "Ministral 3 3B Instruct", "Ministral 3 3B Reasoning",
            "Ministral 3 8B Instruct", "Ministral 3 8B Reasoning",
            "Gemma 4 E2B", "Gemma 4 E4B", "Gemma 4 E2B (Q4_K_M)", "Gemma 4 E4B (Q4_K_M)",
        ).forEach { name ->
            assertNotNull("$name should offer an image add-on", byName(name).visionAddOn)
        }
    }

    @Test
    fun textOnlyModelsOfferNone() {
        // Gemma 4 12B has no projector in the catalog; the rest are plain text models.
        listOf("Gemma 4 12B", "Llama 3.2 3B", "Phi-4 mini", "DeepSeek R1 Distill 7B", "Nemotron 3 Nano 4B")
            .forEach { name -> assertNull("$name must not offer an add-on", byName(name).visionAddOn) }
    }

    // The pairing step finds a projector by looking for "mmproj" in the file name; an
    // add-on that did not match would be downloaded and then ignored forever.
    @Test
    fun everyAddOnIsRecognisedAsAProjectorByName() {
        withAddOn.forEach { m ->
            assertTrue("${m.visionAddOn!!.filename} must look like a projector", MmprojPairing.isMmproj(m.visionAddOn!!.filename))
        }
    }

    @Test
    fun everyAddOnComesFromHuggingFaceAsAGguf() {
        withAddOn.forEach { m ->
            val a = m.visionAddOn!!
            // java.net.URI, not android.net.Uri: nothing here may depend on the framework.
            val u = java.net.URI(a.url)
            assertEquals(m.name, "https", u.scheme)
            assertEquals(m.name, "huggingface.co", u.host)
            assertTrue("${m.name}: ${a.url}", u.path.endsWith(".gguf"))
            assertTrue("${m.name}: ${a.filename}", a.filename.endsWith(".gguf"))
        }
    }

    @Test
    fun sizeLabelsUseTheSameFormatAsTheModelList() {
        // "672Mb" or "1.23Gb", like ModelInfoProvider.formatFileSize.
        val format = Regex("""\d+Mb|\d+\.\d{2}Gb""")
        withAddOn.forEach { m ->
            assertTrue("${m.name}: '${m.visionAddOn!!.sizeLabel}'", format.matches(m.visionAddOn!!.sizeLabel))
        }
    }

    // Different models must not collide on the stored name: they all share one folder,
    // and the second download would silently be treated as already present.
    @Test
    fun addOnsWithTheSameStoredNameAreTheSameFile() {
        withAddOn.groupBy { it.visionAddOn!!.filename }.forEach { (file, models) ->
            val distinct = models.map { it.visionAddOn!!.url to it.visionAddOn!!.sizeLabel }.toSet()
            assertEquals("'$file' is claimed by ${models.map { it.name }} with different sources", 1, distinct.size)
        }
    }

    @Test
    fun bothBuildsOfAGemma4SizeShareOneProjector() {
        assertEquals(byName("Gemma 4 E2B").visionAddOn, byName("Gemma 4 E2B (Q4_K_M)").visionAddOn)
        assertEquals(byName("Gemma 4 E4B").visionAddOn, byName("Gemma 4 E4B (Q4_K_M)").visionAddOn)
    }

    // Gemma 4 uses the Q8_0 projector: BF16 has no optimised ARM CPU kernel and took
    // ~64s per image against ~14s for Q8_0 where the encoder falls back to the CPU.
    @Test
    fun gemma4UsesTheQuantisedProjector() {
        assertTrue(byName("Gemma 4 E2B").visionAddOn!!.filename.endsWith("Q8_0.gguf"))
        assertTrue(byName("Gemma 4 E4B").visionAddOn!!.filename.endsWith("Q8_0.gguf"))
    }

    // The host's own file name for Gemma 3's projector is the generic
    // "mmproj-model-f16.gguf"; it must be stored under a model-specific one.
    @Test
    fun gemma3ProjectorIsStoredUnderASpecificName() {
        val a = byName("Gemma 3 4B").visionAddOn!!
        assertTrue(a.url.endsWith("/mmproj-model-f16.gguf"))
        assertEquals("gemma-3-4b-it-mmproj-f16.gguf", a.filename)
    }

    @Test
    fun deletionHelperFindsEveryModelSharingAProjector() {
        val users = ModelInfoProvider.modelsUsingAddOn("mmproj-gemma-4-E2B-it-Q8_0.gguf").map { it.name }.toSet()
        assertEquals(setOf("Gemma 4 E2B", "Gemma 4 E2B (Q4_K_M)"), users)
        assertTrue(ModelInfoProvider.modelsUsingAddOn("no-such-file.gguf").isEmpty())
    }

    @Test
    fun addOnFilenamesAreKnownSoTheyNeverListAsCustomModels() {
        withAddOn.forEach { m ->
            assertTrue(m.visionAddOn!!.filename in ModelInfoProvider.knownFilenames)
        }
    }

    // --- capability flags ---------------------------------------------------------------

    @Test
    fun aVisionModelCanSeeImagesBeforeItsAddOnIsDownloaded() {
        val m = byName("Qwen 3.5 4B")
        assertFalse("not vision until a projector is on disk", m.isVision)
        assertTrue("but it can see once the add-on is fetched", m.canSeeImages)
    }

    @Test
    fun aTextModelCanNeverSeeImages() {
        assertFalse(byName("Llama 3.2 3B").canSeeImages)
    }

    // --- pairing with files on disk -----------------------------------------------------

    private fun status(downloaded: Set<String>, projectors: List<String>, name: String): ModelWithStatus =
        ModelInfoProvider.getModelsWithStatus(downloaded, emptyList(), projectors)
            .first { it.model.name == name }

    @Test
    fun aDownloadedModelPairsWithItsAddOnOnceTheFileIsThere() {
        val m = byName("Qwen 3.5 4B")
        val addOn = m.visionAddOn!!.filename
        val s = status(setOf(m.filename), listOf(addOn), "Qwen 3.5 4B")
        assertEquals(addOn, s.model.mmprojFilename)
        assertTrue(s.model.isVision)
    }

    @Test
    fun withoutTheAddOnFileTheModelIsNotYetVision() {
        val m = byName("Qwen 3.5 4B")
        val s = status(setOf(m.filename), emptyList(), "Qwen 3.5 4B")
        assertNull(s.model.mmprojFilename)
        assertFalse(s.model.isVision)
        assertTrue("the offer is still there", s.model.canSeeImages)
    }

    // An exact catalog name must beat the token-matching guess, so a similarly named
    // sideloaded projector sitting in the folder can never steal the pairing.
    @Test
    fun theCatalogAddOnWinsOverALookAlikeProjector() {
        val m = byName("Qwen 3.5 4B")
        val addOn = m.visionAddOn!!.filename
        val lookAlike = "mmproj-Qwen_Qwen3.5-4B-extra-experimental.gguf"
        val s = status(setOf(m.filename), listOf(lookAlike, addOn), "Qwen 3.5 4B")
        assertEquals(addOn, s.model.mmprojFilename)
    }

    // The add-on is a companion, not a model: it must not make a model look downloaded.
    @Test
    fun theAddOnAloneDoesNotMarkTheModelDownloaded() {
        val m = byName("Qwen 3.5 4B")
        val s = status(emptySet(), listOf(m.visionAddOn!!.filename), "Qwen 3.5 4B")
        assertFalse(s.isDownloaded)
        assertNull("no pairing for a model that is not on disk", s.model.mmprojFilename)
    }
}
