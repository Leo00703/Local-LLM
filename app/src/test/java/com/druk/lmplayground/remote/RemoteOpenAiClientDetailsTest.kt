package com.druk.lmplayground.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RemoteOpenAiClient.fetchModelDetails] against scripted servers, using the answers the real
 * ones give: which context each server's native API says the model runs with.
 */
class RemoteOpenAiClientDetailsTest {

    private val servers = mutableListOf<MockWebServer>()

    @After
    fun stop() = servers.forEach { it.shutdown() }

    /** A server that answers the given paths with 200 + JSON and everything else with [fallback]. */
    private fun serve(routes: Map<String, String>, fallback: () -> MockResponse = { MockResponse().setResponseCode(404) }): RemoteOpenAiClient {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = routes[request.path?.substringBefore('?')]
                return if (body != null) MockResponse().setBody(body) else fallback()
            }
        }
        server.start()
        servers += server
        return RemoteOpenAiClient(server.url("/").toString().trimEnd('/'))
    }

    private fun details(client: RemoteOpenAiClient, id: String) =
        runBlocking { client.fetchModelDetails(id) }

    // --- Strata ----------------------------------------------------------------------

    // Strata does not say owned_by "llamacpp": it used to be skipped, so the context fell back to 8192.
    @Test
    fun strataContextComesFromProps() {
        val client = serve(
            mapOf(
                "/v1/models" to """{"object":"list","data":[{"id":"qwen","object":"model","status":{"value":"loaded"},
                    "meta":{"n_ctx":204800},"architecture":{"input_modalities":["text","image"]}}]}""",
                "/props" to """{"default_generation_settings":{"n_ctx":204800},"total_slots":1,"model_alias":"qwen",
                    "chat_template_caps":{"supports_tool_calls":true},"modalities":{"vision":true},
                    "build_info":"Strata 0.1.57"}""",
            )
        )
        val d = details(client, "qwen")!!
        assertEquals(204800, d.contextWindow)
        assertEquals(ContextSource.SERVER, d.contextSource)
        assertEquals("Strata", d.publisher)
        assertEquals(listOf("vision", "tools"), d.capabilities)
    }

    // --- llama.cpp router (Llama-macOS app) --------------------------------------------

    @Test
    fun routerModelUsesItsLaunchContextNotTheTrainingOne() {
        val client = serve(
            mapOf(
                "/v1/models" to """{"data":[{"id":"org/vision-small","owned_by":"llamacpp","context_length":128000,
                    "status":{"value":"unloaded","args":["serve","--ctx-size","4096"]}}]}""",
                "/props" to """{"role":"router","default_generation_settings":{"n_ctx":0},"build_info":"b12110"}""",
            )
        )
        val d = details(client, "org/vision-small")!!
        assertEquals(4096, d.contextWindow)
        assertEquals(ContextSource.LAUNCH_ARGS, d.contextSource)
        assertEquals(128000, d.maxContext)
        assertEquals("llama.cpp", d.publisher)
    }

    @Test
    fun aModelWithOnlyATrainingContextIsNotTreatedAsKnown() {
        val client = serve(
            mapOf(
                "/v1/models" to """{"data":[{"id":"m","owned_by":"llamacpp","context_length":128000}]}""",
                "/props" to """{"role":"router","default_generation_settings":{"n_ctx":0}}""",
            )
        )
        val d = details(client, "m")!!
        assertEquals(0, d.contextWindow)
        assertEquals(ContextSource.MODEL_MAX, d.contextSource)
        assertEquals(128000, d.maxContext)
    }

    // --- LM Studio ---------------------------------------------------------------------

    private val lmStudioModels = """{"data":[
        {"id":"loaded-llm","type":"llm","state":"loaded","max_context_length":262144,"loaded_context_length":50176},
        {"id":"idle-llm","type":"llm","state":"not-loaded","max_context_length":1048576}]}"""

    @Test
    fun lmStudioLoadedModelUsesTheLoadedContextNotTheMaximum() {
        val client = serve(mapOf("/api/v0/models" to lmStudioModels))
        val d = details(client, "loaded-llm")!!
        assertEquals(50176, d.contextWindow)
        assertEquals(ContextSource.LOADED, d.contextSource)
        assertEquals(262144, d.maxContext)
    }

    @Test
    fun lmStudioIdleModelHasNoVerifiedContext() {
        val client = serve(mapOf("/api/v0/models" to lmStudioModels))
        val d = details(client, "idle-llm")!!
        assertEquals(0, d.contextWindow)
        assertEquals(ContextSource.MODEL_MAX, d.contextSource)
    }

    // LM Studio answers unknown paths (like /props) with 200 and an error object.
    @Test
    fun lmStudioErrorBodiesAreNeverTakenForLlamaProps() {
        val client = serve(
            mapOf("/v1/models" to """{"data":[{"id":"m"}]}""", "/props" to """{"error":"Unexpected endpoint or method."}""")
        )
        assertNull(details(client, "m"))
    }

    // --- Ollama --------------------------------------------------------------------------

    @Test
    fun ollamaRunningModelReportsTheContextItRunsWith() {
        val client = serve(
            mapOf(
                "/api/show" to """{"details":{"family":"qwen3"},"model_info":{"qwen3.context_length":40960},"capabilities":["completion"]}""",
                "/api/ps" to """{"models":[{"name":"qwen3:8b","context_length":16384}]}""",
            )
        )
        // /api/show is a POST in real Ollama; the scripted server answers any method.
        val d = details(client, "qwen3:8b")!!
        assertEquals(16384, d.contextWindow)
        assertEquals(40960, d.maxContext)
    }
}
