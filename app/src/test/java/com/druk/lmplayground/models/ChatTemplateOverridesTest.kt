package com.druk.lmplayground.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A chat-template override is applied by file name and read from an asset, and a typo
 * in either would fail silently: the model would simply load with its original,
 * defective template. These pin the wiring that cannot be seen from the UI.
 */
class ChatTemplateOverridesTest {

    // Gradle runs unit tests with the module (app/) as the working directory; accept the
    // repository root too so the test also works when run from an IDE.
    private fun assetFile(name: String): File =
        listOf("src/main/assets", "app/src/main/assets")
            .map { File(it, "${ChatTemplateOverrides.ASSET_DIR}/$name") }
            .firstOrNull { it.isFile }
            ?: error("override asset '$name' not found under src/main/assets/${ChatTemplateOverrides.ASSET_DIR}")

    @Test
    fun everyOverrideTargetsACatalogModelByExactFilename() {
        ChatTemplateOverrides.OVERRIDES.keys.forEach { filename ->
            assertTrue("'$filename' is not a catalog model", ModelInfoProvider.getByFilename(filename) != null)
        }
    }

    @Test
    fun everyOverrideHasItsAssetAndItIsNotEmpty() {
        ChatTemplateOverrides.OVERRIDES.forEach { (filename, asset) ->
            val text = assetFile(asset).readText()
            assertTrue("$filename: asset $asset is empty", text.isNotBlank())
        }
    }

    // Jinja output is whitespace-sensitive: a carriage return from a Windows checkout would
    // land inside every prompt the template renders (the loader strips them as a backstop).
    @Test
    fun overrideAssetsAreCheckedInWithoutCarriageReturns() {
        ChatTemplateOverrides.OVERRIDES.values.forEach { asset ->
            val bytes = assetFile(asset).readBytes()
            assertFalse("$asset contains CR bytes", bytes.contains('\r'.code.toByte()))
        }
    }

    // The whole point of the SmolLM3 override: the published template never writes
    // message.tool_calls, so the corrected one must.
    @Test
    fun smolLm3OverrideActuallyRendersToolCalls() {
        val asset = ChatTemplateOverrides.OVERRIDES.getValue("HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf")
        val text = assetFile(asset).readText()
        assertTrue("must iterate message.tool_calls", text.contains("message.tool_calls"))
        assertTrue("must emit the <tool_call> block the system prompt asks for", text.contains("<tool_call>"))
        // And it must still be the chat format SmolLM3 expects.
        assertTrue(text.contains("<|im_start|>"))
        assertTrue(text.contains("<|im_end|>"))
    }

    // The VL 450M override must stay a template that renders NO tool calls: its own renders
    // them in a form llama.cpp turns into a grammar with empty rules (the failure the
    // override exists to avoid). Rendering them again would bring that grammar back.
    @Test
    fun lfmVl450mOverrideRendersNoToolCalls() {
        val asset = ChatTemplateOverrides.OVERRIDES.getValue("LFM2.5-VL-450M-Q4_K_M.gguf")
        val text = assetFile(asset).readText()
        assertFalse("must not render message.tool_calls", text.contains("tool_calls"))
        // Still the LFM chat format, with the tools it is told about listed in the prompt.
        assertTrue(text.contains("<|im_start|>"))
        assertTrue(text.contains("tools"))
    }

    @Test
    fun aModelWithoutAnOverrideKeepsItsOwnTemplate() {
        assertEquals(null, ChatTemplateOverrides.OVERRIDES["Qwen3-4B-Q4_K_M.gguf"])
    }

    // A model is only badged as tool-capable "because" of an override if the override
    // really is registered; this guards the list from drifting apart from the map.
    @Test
    fun theOverriddenModelIsBadgedToolCapable() {
        ChatTemplateOverrides.OVERRIDES.keys.forEach { filename ->
            val m = requireNotNull(ModelInfoProvider.getByFilename(filename))
            assertTrue("${m.name} has a template override but no tool badge", m.supportsTools)
        }
    }
}
