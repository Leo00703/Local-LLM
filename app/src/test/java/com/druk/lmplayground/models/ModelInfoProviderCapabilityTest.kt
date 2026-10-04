package com.druk.lmplayground.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInfoProviderCapabilityTest {

    private fun byName(name: String): ModelInfo =
        requireNotNull(ModelInfoProvider.allModels.find { it.name == name }) {
            "Model not found: $name"
        }

    @Test
    fun qwen3FamilySupportsToolsAndThinking() {
        listOf("Qwen 3 0.6B", "Qwen 3 4B", "Qwen 3.5 4B").forEach { name ->
            val model = byName(name)
            assertTrue("$name should support tools", model.supportsTools)
            assertTrue("$name should support thinking", model.supportsThinking)
        }
    }

    @Test
    fun gemmaInstructSupportsNeither() {
        listOf("Gemma 3 1B", "Gemma 3 4B", "Gemma 3n E2B", "Gemma2 9B").forEach { name ->
            val model = byName(name)
            assertFalse("$name should not support tools", model.supportsTools)
            assertFalse("$name should not support thinking", model.supportsThinking)
        }
    }

    @Test
    fun reasoningVariantsSupportThinking() {
        listOf(
            "DeepSeek R1 Distill 7B",
            "LFM2.5 1.2B Thinking",
            "Ministral 3 3B Reasoning",
        ).forEach { name ->
            assertTrue("$name should support thinking", byName(name).supportsThinking)
        }
    }

    @Test
    fun deepseekDistillSupportsThinkingButNotTools() {
        val model = byName("DeepSeek R1 Distill 1.5B")
        assertTrue(model.supportsThinking)
        assertFalse(model.supportsTools)
    }

    @Test
    fun toolOnlyModelsSupportToolsNotThinking() {
        listOf("Llama 3.2 3B", "Granite 4.0 Micro", "Qwen2.5 0.5B").forEach { name ->
            val model = byName(name)
            assertTrue("$name should support tools", model.supportsTools)
            assertFalse("$name should not support thinking", model.supportsThinking)
        }
    }

    // Llama 3.2 1B and Mistral 7B v0.3 ship chat templates with no tool logic at
    // all, and Phi-4 mini only reads `tools` from a per-system-message field, so the
    // engine never enables tools for them. The badge must not promise otherwise.
    @Test
    fun modelsWhoseTemplatesCannotCallToolsAreNotBadged() {
        listOf("Llama 3.2 1B", "Phi-4 mini", "Mistral 7B").forEach { name ->
            assertFalse("$name must not be badged as tool-capable", byName(name).supportsTools)
        }
    }

    // SmolLM3's published template never renders message.tool_calls, so on its own its
    // tool calls would reach the chat as raw XML. It is badged as tool-capable only
    // because it is loaded with a corrected template; the two must stay together.
    @Test
    fun smolLm3IsToolCapableOnlyBecauseItsTemplateIsOverridden() {
        val m = byName("SmolLM3 3B")
        assertTrue(m.supportsTools)
        assertTrue(m.supportsThinking)
        assertTrue(
            "the tool badge needs the template override",
            m.filename in ChatTemplateOverrides.OVERRIDES,
        )
    }

    @Test
    fun newReasoningModelsAreToolAndThinkingCapable() {
        listOf("LFM2.5 2.6B", "MiniCPM5 1B", "MiniCPM5 2B", "Granite 4.2 3B", "Spark-X2.5 1.7B", "Spark-X2.5 4B").forEach { name ->
            val model = byName(name)
            assertTrue("$name should support tools", model.supportsTools)
            assertTrue("$name should support thinking", model.supportsThinking)
        }
    }

    // The VL models call tools but their templates advertise a thinking mode they never
    // use, so they are badged for tools only. They see images only once their add-on is in.
    @Test
    fun lfmVisionModelsCallToolsButDoNotThink() {
        listOf("LFM2.5 VL 450M", "LFM2.5 VL 1.6B", "LFM2.5 VL 3B").forEach { name ->
            val m = byName(name)
            assertTrue("$name should support tools", m.supportsTools)
            assertFalse("$name must not be badged as thinking", m.supportsThinking)
            assertFalse("$name has no projector until it is downloaded", m.isVision)
            assertTrue("$name can see images once the add-on is fetched", m.canSeeImages)
        }
    }

    @Test
    fun customModelHasNoCapabilities() {
        val custom = ModelInfoProvider.createCustomModelInfo(
            filename = "custom.gguf",
            name = "Custom",
            sizeBytes = 1_000L
        )
        assertFalse(custom.supportsTools)
        assertFalse(custom.supportsThinking)
    }
}
