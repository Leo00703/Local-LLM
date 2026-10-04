package com.druk.lmplayground.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the thinking UI follows measured behaviour (ThinkingMode) rather than
 * the chat template's own flag, so a catalog edit cannot quietly bring back a
 * toggle that lies: one that does nothing, or one that offers "off" to a model
 * that keeps reasoning anyway.
 */
class ThinkingModeTest {

    private fun byName(name: String): ModelInfo =
        requireNotNull(ModelInfoProvider.allModels.find { it.name == name }) {
            "Model not found: $name"
        }

    private fun model(mode: ThinkingMode, templateFlag: Boolean) = ModelInfo(
        name = "Test",
        filename = "test.gguf",
        description = "test",
        supportsThinking = templateFlag,
        thinkingMode = mode,
    )

    @Test
    fun noneNeverThinksWhateverTheTemplateSays() {
        val m = model(ThinkingMode.NONE, templateFlag = true)
        assertFalse(m.canThink(templateFlag = true))
        assertFalse(m.offersThinkingSwitch(templateFlag = true))
    }

    @Test
    fun optionalThinksAndOffersTheSwitchEvenIfTheTemplateIsSilent() {
        val m = model(ThinkingMode.OPTIONAL, templateFlag = false)
        assertTrue(m.canThink(templateFlag = false))
        assertTrue(m.offersThinkingSwitch(templateFlag = false))
    }

    @Test
    fun alwaysThinksButHasNoSwitch() {
        val m = model(ThinkingMode.ALWAYS, templateFlag = false)
        assertTrue("the thinking budget still applies", m.canThink(templateFlag = false))
        assertFalse("\"off\" would be ignored", m.offersThinkingSwitch(templateFlag = false))
    }

    @Test
    fun unknownFallsBackToTheTemplateFlag() {
        assertTrue(model(ThinkingMode.UNKNOWN, false).canThink(templateFlag = true))
        assertFalse(model(ThinkingMode.UNKNOWN, true).canThink(templateFlag = false))
        assertTrue(model(ThinkingMode.UNKNOWN, false).offersThinkingSwitch(templateFlag = true))
        assertFalse(model(ThinkingMode.UNKNOWN, true).offersThinkingSwitch(templateFlag = false))
    }

    @Test
    fun customModelsAreUnmeasured() {
        val custom = ModelInfoProvider.createCustomModelInfo("c.gguf", "Custom", 1_000L)
        assertEquals(ThinkingMode.UNKNOWN, custom.thinkingMode)
    }

    // Templates advertise a thinking mode that these models never use.
    @Test
    fun modelsThatNeverThinkAreMeasuredAsNone() {
        listOf(
            "Ministral 3 3B Instruct", "LFM2.5 350M", "LFM2.5 1.2B Instruct",
            "LFM2.5 VL 450M", "LFM2.5 VL 1.6B", "LFM2.5 VL 3B",
        ).forEach { name ->
            val m = byName(name)
            assertEquals(name, ThinkingMode.NONE, m.thinkingMode)
            assertFalse("$name must not be badged as thinking", m.supportsThinking)
        }
    }

    // Reasoning-tuned: they keep thinking with the flag off.
    @Test
    fun reasoningTunedModelsAreMeasuredAsAlways() {
        listOf(
            "DeepSeek R1 Distill 1.5B",
            "LFM2.5 1.2B Thinking",
            "LFM2.5 2.6B",
            "Ministral 3 3B Reasoning",
        ).forEach { name ->
            val m = byName(name)
            assertEquals(name, ThinkingMode.ALWAYS, m.thinkingMode)
            assertTrue("$name should be badged as thinking", m.supportsThinking)
            assertFalse("$name should have no on/off switch", m.offersThinkingSwitch())
        }
    }

    // Verified to honour the toggle in both directions.
    @Test
    fun modelsWithAWorkingToggleAreMeasuredAsOptional() {
        listOf(
            "Gemma 4 E2B",
            "Gemma 4 E4B",
            "SmolLM3 3B",
            "MiniCPM5 1B",
            "MiniCPM5 2B",
            "Granite 4.2 3B",
            "Spark-X2.5 4B",
            "Nemotron 3 Nano 4B",
        ).forEach { name ->
            val m = byName(name)
            assertEquals(name, ThinkingMode.OPTIONAL, m.thinkingMode)
            assertTrue("$name should offer the switch", m.offersThinkingSwitch())
        }
    }

    @Test
    fun unmeasuredCatalogModelsKeepTheirFamilyFlag() {
        // Qwen 3.5 ships as Q3_K_M here and was only measured at another quant.
        val qwen35 = byName("Qwen 3.5 4B")
        assertEquals(ThinkingMode.UNKNOWN, qwen35.thinkingMode)
        assertTrue(qwen35.supportsThinking)
        assertTrue(qwen35.offersThinkingSwitch())
    }
}
