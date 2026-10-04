package com.druk.lmplayground

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.jni.NativeLlamaCpp
import com.druk.llamacpp.jni.NativeLlamaModel
import com.druk.llamacpp.jni.NativeLlamaSession
import com.druk.lmplayground.conversation.ResponseProcessor
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

/**
 * Instrumented test that loads a real GGUF model and validates generation output
 * and response processing.
 *
 * Setup: copy a .gguf model to /data/local/tmp/ on the device:
 *   adb shell "cp /sdcard/Models/Qwen3-0.6B-Q4_K_M.gguf /data/local/tmp/ && chmod 666 /data/local/tmp/Qwen3-0.6B-Q4_K_M.gguf"
 */
@RunWith(AndroidJUnit4::class)
class ModelGenerationTest {

    companion object {
        private const val TAG = "ModelGenerationTest"
        /**
         * Known model files to look for, in order of preference (smallest first).
         * Copy a model to /data/local/tmp/ before running:
         *   adb shell "cp /sdcard/Models/<model>.gguf /data/local/tmp/ && chmod 666 /data/local/tmp/<model>.gguf"
         */
        private val CANDIDATE_MODELS = listOf(
            "Qwen3-0.6B-Q4_K_M.gguf",
            "Qwen_Qwen3.5-0.8B-Q4_K_M.gguf",
            "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
            "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
            "Qwen_Qwen3.5-2B-Q4_K_M.gguf",
            "Qwen_Qwen3.5-2B-Q3_K_M.gguf",
            "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf"
        )
        private const val MODELS_PATH = "/data/local/tmp"
    }

    private lateinit var llamaCpp: NativeLlamaCpp
    private var llamaModel: NativeLlamaModel? = null
    private var session: NativeLlamaSession? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        llamaCpp = NativeLlamaCpp()
        llamaCpp.init(context.applicationInfo.nativeLibraryDir)
    }

    @After
    fun tearDown() {
        session?.destroy()
        session = null
        llamaModel?.unloadModel()
        llamaModel = null
    }

    private fun findModel(): File? {
        for (name in CANDIDATE_MODELS) {
            val file = File(MODELS_PATH, name)
            if (file.exists() && file.canRead()) {
                return file
            }
        }
        return null
    }

    private fun loadModel(modelFile: File): NativeLlamaModel {
        Log.d(TAG, "Loading model: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")
        val model = llamaCpp.loadModel(
            modelFile.absolutePath,
            object : LlamaProgressCallback {
                override fun onProgress(progress: Float) {
                    Log.d(TAG, "Loading: ${(progress * 100).toInt()}%")
                }
            },
            disableRepack = false,
        ) ?: error("native loadModel returned null for ${modelFile.absolutePath}")
        llamaModel = model
        return model
    }

    private fun generateFullResponse(
        session: NativeLlamaSession,
        maxTokens: Int = 2048,
        timeoutMs: Long = 120_000
    ): String {
        var lastResponse = ""
        var tokenCount = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        val callback = object : LlamaGenerationCallback {
            override fun onFullResponse(response: String) {
                lastResponse = response
            }
        }
        while (session.generate(callback) == 0) {
            tokenCount++
            if (tokenCount >= maxTokens) {
                Log.d(TAG, "Reached max token limit ($maxTokens)")
                break
            }
            if (System.currentTimeMillis() > deadline) {
                Log.d(TAG, "Reached timeout (${timeoutMs}ms) after $tokenCount tokens")
                break
            }
        }
        return lastResponse
    }

    @Test
    fun testModelLoadsSuccessfully() {
        val modelFile = findModel()
        assumeTrue("No model file found in $MODELS_PATH. Run: adb shell cp /sdcard/Models/<model>.gguf $MODELS_PATH/", modelFile != null)

        val model = loadModel(modelFile!!)
        assertTrue("Model size should be > 0", model.getModelSize() > 0)
    }

    @Test
    fun testSupportsThinkingDetection() {
        val modelFile = findModel()
        assumeTrue("No model file found in $MODELS_PATH. Run: adb shell cp /sdcard/Models/<model>.gguf $MODELS_PATH/", modelFile != null)

        val model = loadModel(modelFile!!)
        val supports = model.supportsThinking()
        Log.d(TAG, "Model ${modelFile.name} supportsThinking: $supports")
        // Qwen3 models support thinking
        if (modelFile.name.contains("Qwen3")) {
            assertTrue("Qwen3 should support thinking", supports)
        }
    }

    @Test
    fun testGenerateWithThinkingEnabled() {
        val modelFile = findModel()
        assumeTrue("No model file found in $MODELS_PATH. Run: adb shell cp /sdcard/Models/<model>.gguf $MODELS_PATH/", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        session.addMessage("Say hello in one short sentence", true)
        val raw = generateFullResponse(session)

        Log.d(TAG, "Raw response (thinking enabled):\n$raw")
        assertTrue("Response should not be empty", raw.isNotBlank())

        if (model.supportsThinking()) {
            assertTrue(
                "Thinking model response should contain <think> tag",
                raw.contains("<think>")
            )
            assertTrue(
                "Thinking model response should contain </think> tag",
                raw.contains("</think>")
            )
        }
    }

    @Test
    fun testGenerateWithThinkingDisabled() {
        val modelFile = findModel()
        assumeTrue("No model file found in $MODELS_PATH. Run: adb shell cp /sdcard/Models/<model>.gguf $MODELS_PATH/", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        session.addMessage("Say hello in one short sentence", false)
        val raw = generateFullResponse(session)

        Log.d(TAG, "Raw response (thinking disabled):\n$raw")
        assertTrue("Response should not be empty", raw.isNotBlank())
    }

    @Test
    fun testResponseProcessorOnRealOutput() {
        val modelFile = findModel()
        assumeTrue("No model file found in $MODELS_PATH. Run: adb shell cp /sdcard/Models/<model>.gguf $MODELS_PATH/", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        session.addMessage("Say hello in one short sentence", true)
        val raw = generateFullResponse(session)
        Log.d(TAG, "Raw response:\n$raw")

        val processed = ResponseProcessor.process(raw)
        Log.d(TAG, "Processed response:\n$processed")

        assertTrue("Processed response should not be empty", processed.isNotBlank())
        assertFalse(
            "Processed response should not contain separator line after </think>",
            Regex("""</think>\s*[-—_]{2,}""").containsMatchIn(processed)
        )
    }

    @Test
    fun testMultiTurnConversation() {
        val modelFile = findModel()
        assumeTrue("No model file found in $MODELS_PATH. Run: adb shell cp /sdcard/Models/<model>.gguf $MODELS_PATH/", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        // First turn
        session.addMessage("Say hello", true)
        val response1 = generateFullResponse(session)
        Log.d(TAG, "Turn 1 raw:\n$response1")
        assertTrue("First response should not be empty", response1.isNotBlank())

        val processed1 = ResponseProcessor.process(response1)
        Log.d(TAG, "Turn 1 processed:\n$processed1")

        // Second turn
        session.addMessage("What did I just ask you?", true)
        val response2 = generateFullResponse(session)
        Log.d(TAG, "Turn 2 raw:\n$response2")
        assertTrue("Second response should not be empty", response2.isNotBlank())

        val processed2 = ResponseProcessor.process(response2)
        Log.d(TAG, "Turn 2 processed:\n$processed2")
    }

    @Test(timeout = 300_000)
    fun testMultiTurnThinkingDisabled() {
        val modelFile = findModel()
        assumeTrue("No model file found in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        // Turn 1
        session.addMessage("Say hello in one short sentence", false)
        val r1 = generateFullResponse(session, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Turn 1 raw (${r1.length} chars):\n$r1")
        assertTrue("Turn 1 should not be empty", r1.isNotBlank())

        // Turn 2
        session.addMessage("Now say goodbye in one short sentence", false)
        val r2 = generateFullResponse(session, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Turn 2 raw (${r2.length} chars):\n$r2")
        assertTrue("Turn 2 should not be empty", r2.isNotBlank())

        // Turn 3
        session.addMessage("What was the first thing you said?", false)
        val r3 = generateFullResponse(session, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Turn 3 raw (${r3.length} chars):\n$r3")
        assertTrue("Turn 3 should not be empty", r3.isNotBlank())

        // The key assertion: responses should not be identical (the original bug
        // caused the model to repeat the same response every turn)
        assertFalse(
            "Responses should not all be identical (indicates broken multi-turn)",
            r1 == r2 && r2 == r3
        )
    }

    private fun findSpecificModel(nameFragment: String): File? {
        for (name in CANDIDATE_MODELS) {
            if (name.contains(nameFragment)) {
                val file = File(MODELS_PATH, name)
                if (file.exists() && file.canRead()) return file
            }
        }
        return null
    }

    @Test(timeout = 180_000)
    fun testQwen35ThinkingEnabled() {
        val modelFile = findSpecificModel("Qwen3.5")
        assumeTrue("Qwen 3.5 model not found in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)
        Log.d(TAG, "supportsThinking: ${model.supportsThinking()}")
        assertTrue("Qwen 3.5 should support thinking", model.supportsThinking())

        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        session.addMessage("Say hello in one sentence", true)
        val raw = generateFullResponse(session, maxTokens = 512, timeoutMs = 120_000)
        Log.d(TAG, "Qwen3.5 thinking=true raw (${raw.length} chars):\n$raw")
        assertTrue("Response should not be empty", raw.isNotBlank())

        val hasThinkClose = raw.contains("</think>")
        Log.d(TAG, "Contains </think>: $hasThinkClose")
    }

    @Test(timeout = 180_000)
    fun testQwen35ThinkingDisabled() {
        val modelFile = findSpecificModel("Qwen3.5")
        assumeTrue("Qwen 3.5 model not found in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        session.addMessage("Say hello in one sentence", false)
        val raw = generateFullResponse(session, maxTokens = 512, timeoutMs = 120_000)
        Log.d(TAG, "Qwen3.5 thinking=false raw (${raw.length} chars):\n$raw")
        assertTrue("Response should not be empty with thinking disabled", raw.isNotBlank())
    }

    @Test(timeout = 300_000)
    fun testQwen35MultiTurnThinking() {
        val modelFile = findSpecificModel("Qwen3.5")
        assumeTrue("Qwen 3.5 model not found in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        // Turn 1
        session.addMessage("Say hello", true)
        val r1 = generateFullResponse(session, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Qwen3.5 multi-turn T1 (${r1.length} chars):\n$r1")
        assertTrue("Turn 1 should not be empty", r1.isNotBlank())

        // Turn 2
        session.addMessage("What is 2+2?", true)
        val r2 = generateFullResponse(session, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Qwen3.5 multi-turn T2 (${r2.length} chars):\n$r2")
        assertTrue("Turn 2 should not be empty", r2.isNotBlank())

        // Turn 3
        session.addMessage("Thanks!", true)
        val r3 = generateFullResponse(session, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Qwen3.5 multi-turn T3 (${r3.length} chars):\n$r3")
        assertTrue("Turn 3 should not be empty", r3.isNotBlank())
    }

    @Test(timeout = 300_000)
    fun testNemotronLoadsAndGenerates() {
        val modelFile = findSpecificModel("Nemotron")
        assumeTrue("Nemotron model not found in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)
        assertTrue("Model size should be > 0", model.getModelSize() > 0)
        Log.d(TAG, "Nemotron supportsThinking: ${model.supportsThinking()}")

        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        session.addMessage("Say hello in one sentence", false)
        val raw = generateFullResponse(session, maxTokens = 256, timeoutMs = 120_000)
        Log.d(TAG, "Nemotron raw (${raw.length} chars):\n$raw")
        assertTrue("Response should not be empty", raw.isNotBlank())
    }

    @Test(timeout = 300_000)
    fun testNemotronThinking() {
        val modelFile = findSpecificModel("Nemotron")
        assumeTrue("Nemotron model not found in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue("Nemotron does not support thinking", model.supportsThinking())

        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        session.addMessage("What is 2+2?", true)
        val raw = generateFullResponse(session, maxTokens = 512, timeoutMs = 120_000)
        Log.d(TAG, "Nemotron thinking=true raw (${raw.length} chars):\n$raw")
        assertTrue("Response should not be empty", raw.isNotBlank())
        assertTrue("Should contain </think> tag", raw.contains("</think>"))
    }

    @Test(timeout = 300_000)
    fun testNemotronMultiTurn() {
        val modelFile = findSpecificModel("Nemotron")
        assumeTrue("Nemotron model not found in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        session.addMessage("Say hello", false)
        val r1 = generateFullResponse(session, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Nemotron T1 (${r1.length} chars):\n$r1")
        assertTrue("Turn 1 should not be empty", r1.isNotBlank())

        session.addMessage("What did I just ask you?", false)
        val r2 = generateFullResponse(session, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Nemotron T2 (${r2.length} chars):\n$r2")
        assertTrue("Turn 2 should not be empty", r2.isNotBlank())
    }

    @Test(timeout = 180_000)
    fun testThinkingBudgetLimitsTokens() {
        val modelFile = findModel()
        assumeTrue("No model file found in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue("Model must support thinking", model.supportsThinking())

        val budget = 50
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, budget, "")!!
        this.session = session

        session.addMessage("Explain quantum computing", true)
        val raw = generateFullResponse(session, maxTokens = 1024, timeoutMs = 120_000)
        Log.d(TAG, "Budget=$budget raw (${raw.length} chars):\n$raw")
        assertTrue("Response should not be empty", raw.isNotBlank())

        val hasThinkClose = raw.contains("</think>")
        assertTrue("Response should contain </think> (budget should force close)", hasThinkClose)

        // Count tokens in the thinking block (rough char-based estimate:
        // the thinking content between <think> and </think> should be limited)
        val thinkStart = raw.indexOf("<think>")
        val thinkEnd = raw.indexOf("</think>")
        if (thinkStart >= 0 && thinkEnd > thinkStart) {
            val thinkContent = raw.substring(thinkStart + 7, thinkEnd)
            Log.d(TAG, "Thinking content length: ${thinkContent.length} chars")
            // With a 50-token budget, thinking should be short (rough ~4 chars/token)
            assertTrue(
                "Thinking content should be limited (got ${thinkContent.length} chars)",
                thinkContent.length < 500
            )
        }
    }

}
