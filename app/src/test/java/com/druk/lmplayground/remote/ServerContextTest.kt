package com.druk.lmplayground.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerContextTest {

    private fun ctx(tokens: Int, source: ContextSource) = ServerContext(tokens, source)

    // --- LM Studio ---------------------------------------------------------------------

    // The size it was loaded with is what the server enforces; max_context_length is the training maximum.
    @Test
    fun lmStudioLoadedModelUsesTheLoadedContext() {
        assertEquals(ctx(50176, ContextSource.LOADED), ServerContexts.lmStudio("loaded", 50176, 262144))
    }

    @Test
    fun lmStudioUnloadedModelOnlyHasAnUpperBound() {
        val c = ServerContexts.lmStudio("not-loaded", 0, 1048576)
        assertEquals(ctx(1048576, ContextSource.MODEL_MAX), c)
        assertFalse(c.exact)
    }

    @Test
    fun lmStudioWithNothingAdvertisedIsUnknown() {
        assertEquals(ServerContext.UNKNOWN, ServerContexts.lmStudio(null, 0, 0))
    }

    // --- Ollama ------------------------------------------------------------------------

    @Test
    fun ollamaRunningModelReportsItsContext() {
        val ps = """{"models":[{"name":"qwen3:8b","model":"qwen3:8b","context_length":16384}]}"""
        assertEquals(ctx(16384, ContextSource.LOADED), ServerContexts.ollama(ps, "qwen3:8b", 40960))
    }

    @Test
    fun ollamaModelThatIsNotRunningFallsBackToItsMaximum() {
        assertEquals(ctx(40960, ContextSource.MODEL_MAX), ServerContexts.ollama("""{"models":[]}""", "qwen3:8b", 40960))
        assertEquals(ctx(40960, ContextSource.MODEL_MAX), ServerContexts.ollama(null, "qwen3:8b", 40960))
        assertEquals(ServerContext.UNKNOWN, ServerContexts.ollama("not json", "x", 0))
    }

    // --- llama.cpp /props --------------------------------------------------------------

    @Test
    fun aSingleModelServerReportsItsContextInProps() {
        val props = JSONObject("""{"default_generation_settings":{"n_ctx":32768},"build_info":"b1"}""")
        assertEquals(32768, ServerContexts.propsContext(props))
        assertTrue(ServerContexts.isLlamaProps(props))
    }

    // A router answers n_ctx 0 (the context belongs to each model): never take that for "no limit".
    @Test
    fun aRouterHasNoContextOfItsOwn() {
        val props = JSONObject("""{"role":"router","default_generation_settings":{"n_ctx":0}}""")
        assertEquals(0, ServerContexts.propsContext(props))
        assertTrue(ServerContexts.isLlamaProps(props))
    }

    // LM Studio answers unknown paths with 200 and an error object.
    @Test
    fun anErrorBodyIsNotLlamaProps() {
        assertFalse(ServerContexts.isLlamaProps(JSONObject("""{"error":"Unexpected endpoint or method."}""")))
        assertFalse(ServerContexts.isLlamaProps(null))
    }

    // --- Strata ------------------------------------------------------------------------

    private val strataProps = JSONObject(
        """{"default_generation_settings":{"n_ctx":204800},"total_slots":1,"model_alias":"m",
            "modalities":{"vision":true},"build_info":"Strata 0.1.57"}"""
    )
    private val strataEntry = JSONObject(
        """{"id":"m","object":"model","status":{"value":"loaded"},"meta":{"n_ctx":204800},
            "architecture":{"input_modalities":["text","image"]}}"""
    )

    @Test
    fun strataIsRecognisedByItsBuildInfo() {
        assertTrue(ServerContexts.isStrata(strataProps))
        assertFalse(ServerContexts.isStrata(JSONObject("""{"build_info":"b12110-cd959bae9"}""")))
        assertFalse(ServerContexts.isStrata(null))
    }

    @Test
    fun strataContextComesFromProps() {
        assertEquals(ctx(204800, ContextSource.SERVER), ServerContexts.llamaModel(strataEntry, strataProps))
    }

    @Test
    fun strataWithoutPropsStillHasMetaNCtx() {
        assertEquals(ctx(204800, ContextSource.LOADED), ServerContexts.llamaModel(strataEntry, null))
    }

    // --- llama.cpp launch arguments ----------------------------------------------------

    private fun args(vararg a: String) = JSONArray(a.toList())

    @Test
    fun launchContextIsReadFromTheCommonSpellings() {
        assertEquals(4096, ServerContexts.llamaLaunchContext(args("serve", "--ctx-size", "4096"), null))
        assertEquals(8192, ServerContexts.llamaLaunchContext(args("-c", "8192"), null))
        assertEquals(16384, ServerContexts.llamaLaunchContext(args("--ctx-size=16384"), null))
    }

    @Test
    fun launchContextFallsBackToThePresetText() {
        assertEquals(65536, ServerContexts.llamaLaunchContext(null, "[m]\nctx-size = 65536\nagent = 1\n"))
    }

    @Test
    fun ctxSizeZeroMeansUnknown() {
        assertEquals(0, ServerContexts.llamaLaunchContext(args("--ctx-size", "0"), null))
        assertEquals(0, ServerContexts.llamaLaunchContext(args("--model", "x.gguf"), ""))
    }

    @Test
    fun explicitSlotsSplitTheContextUnlessTheCacheIsUnified() {
        assertEquals(16384, ServerContexts.llamaLaunchContext(args("-c", "65536", "-np", "4"), null))
        assertEquals(65536, ServerContexts.llamaLaunchContext(args("-c", "65536", "-np", "4", "--kv-unified"), null))
        assertEquals(65536, ServerContexts.llamaLaunchContext(args("-c", "65536", "--parallel", "1"), null))
    }

    // --- llama.cpp router model --------------------------------------------------------

    // Real shape of a Llama-macOS router entry: context_length is the training context (128000),
    // the context it will be launched with is in status.args.
    private val routerEntry = JSONObject(
        """{"id":"org/vision-small:Q8_0","owned_by":"llamacpp","context_length":128000,
            "status":{"value":"unloaded","args":["serve","--ctx-size","4096","--model","x.gguf"],
                      "preset":"[org/vision-small:Q8_0]\nctx-size = 4096\n"}}"""
    )
    private val routerProps = JSONObject("""{"role":"router","default_generation_settings":{"n_ctx":0}}""")

    @Test
    fun aRouterModelUsesItsLaunchContextNotTheTrainingOne() {
        assertEquals(ctx(4096, ContextSource.LAUNCH_ARGS), ServerContexts.llamaModel(routerEntry, routerProps))
    }

    @Test
    fun aLoadedRouterModelReportsMetaNCtx() {
        val loaded = JSONObject(routerEntry.toString()).put("meta", JSONObject().put("n_ctx", 3000))
        assertEquals(ctx(3000, ContextSource.LOADED), ServerContexts.llamaModel(loaded, routerProps))
    }

    @Test
    fun withNoRuntimeInformationTheTrainingContextIsOnlyABound() {
        val bare = JSONObject("""{"id":"m","context_length":128000}""")
        val c = ServerContexts.llamaModel(bare, routerProps)
        assertEquals(ctx(128000, ContextSource.MODEL_MAX), c)
        assertFalse(c.exact)
        assertEquals(ServerContext.UNKNOWN, ServerContexts.llamaModel(JSONObject("""{"id":"m"}"""), null))
    }
}
