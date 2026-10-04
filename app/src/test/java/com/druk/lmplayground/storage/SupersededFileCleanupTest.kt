package com.druk.lmplayground.storage

import com.druk.lmplayground.models.ModelInfoProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The janitor that removes a retired model build. Deleting a user's multi-gigabyte model
 * is the one thing here that cannot be undone, so the rule that gates it is pinned.
 */
class SupersededFileCleanupTest {

    private val old4b = "Qwen_Qwen3.5-4B-Q3_K_M.gguf"
    private val new4b = "Qwen_Qwen3.5-4B-Q4_K_M.gguf"

    @Test
    fun aRetiredFileGoesOnlyOnceItsReplacementIsOnDisk() {
        assertEquals(listOf(old4b), SupersededFileCleanup.selectDeletable(setOf(old4b, new4b)))
    }

    // The user who has not fetched the new build yet must keep a model that works.
    @Test
    fun aRetiredFileIsKeptWhileItsReplacementIsMissing() {
        assertTrue(SupersededFileCleanup.selectDeletable(setOf(old4b)).isEmpty())
    }

    @Test
    fun theReplacementAloneIsNeverDeleted() {
        assertTrue(SupersededFileCleanup.selectDeletable(setOf(new4b)).isEmpty())
    }

    @Test
    fun anEmptyFolderHasNothingToDelete() {
        assertTrue(SupersededFileCleanup.selectDeletable(emptySet()).isEmpty())
    }

    // The other sizes are independent: a new 2B does not release the old 4B.
    @Test
    fun eachRetiredFileWaitsForItsOwnReplacement() {
        val present = setOf(old4b, "Qwen_Qwen3.5-2B-Q3_K_M.gguf", "Qwen_Qwen3.5-2B-Q4_K_M.gguf")
        assertEquals(listOf("Qwen_Qwen3.5-2B-Q3_K_M.gguf"), SupersededFileCleanup.selectDeletable(present))
    }

    @Test
    fun unrelatedFilesAreNeverSelected() {
        val present = setOf("gemma-4-E2B-it-Q4_K_M.gguf", "mmproj-Qwen_Qwen3.5-4B-f16.gguf", "my-own-model.gguf")
        assertTrue(SupersededFileCleanup.selectDeletable(present).isEmpty())
    }

    // The map must stay tied to the catalog: a retired entry stays known but hidden, and
    // what replaces it must be a real model that is offered.
    @Test
    fun everyRetiredFileIsADeprecatedCatalogModel() {
        SupersededFileCleanup.supersededFilenames().forEach { filename ->
            val m = ModelInfoProvider.getByFilename(filename)
            assertNotNull("$filename must stay in the catalog so the file keeps its identity", m)
            assertTrue("$filename must be deprecated so it is never offered", m!!.deprecated)
        }
    }

    @Test
    fun everyReplacementIsAnOfferedCatalogModel() {
        SupersededFileCleanup.SUPERSEDED.forEach { (old, replacement) ->
            val m = ModelInfoProvider.getByFilename(replacement)
            assertNotNull("$old is replaced by '$replacement', which is not in the catalog", m)
            assertFalse("$replacement must not itself be deprecated", m!!.deprecated)
            assertFalse("$old must not replace itself", old == replacement)
        }
    }

    // A replacement that is itself retired would leave a chain the one-step rule cannot follow.
    @Test
    fun noReplacementIsItselfRetired() {
        val retired = SupersededFileCleanup.supersededFilenames()
        SupersededFileCleanup.SUPERSEDED.values.forEach { assertFalse(it in retired) }
    }

    // A retired build is not offered for download, but a copy already on disk still shows
    // up (and loads) until the janitor removes it.
    @Test
    fun retiredBuildsAreHiddenUnlessTheFileIsOnDisk() {
        val retired = SupersededFileCleanup.supersededFilenames()
        val offered = ModelInfoProvider.getModelsWithStatus(emptySet()).map { it.model.filename }
        retired.forEach { assertFalse("$it must not be offered", it in offered) }

        val shown = ModelInfoProvider.getModelsWithStatus(setOf(old4b)).first { it.model.filename == old4b }
        assertTrue(shown.isDownloaded)
    }

    // Qwen 3.5 is Q4_K_M because the OpenCL backend has kernels for Q4_K and none for
    // Q3_K; an offered Qwen 3.5 in another quant would silently fall back to the CPU.
    @Test
    fun offeredQwen35BuildsAreQ4KM() {
        val offered = ModelInfoProvider.allModels.filter { it.name.startsWith("Qwen 3.5") && !it.deprecated }
        assertEquals(3, offered.size)
        offered.forEach { assertTrue(it.filename, it.filename.endsWith("-Q4_K_M.gguf")) }
    }

    // The new build must carry over everything the old one offered, or the user trades
    // a capability for the quant.
    @Test
    fun aReplacementKeepsTheCapabilitiesOfWhatItReplaces() {
        SupersededFileCleanup.SUPERSEDED.forEach { (old, replacement) ->
            val o = requireNotNull(ModelInfoProvider.getByFilename(old))
            val n = requireNotNull(ModelInfoProvider.getByFilename(replacement))
            assertEquals("$replacement tools", o.supportsTools, n.supportsTools)
            assertEquals("$replacement thinking", o.supportsThinking, n.supportsThinking)
            assertEquals("$replacement add-on", o.visionAddOn, n.visionAddOn)
        }
    }
}
