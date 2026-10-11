package com.druk.lmplayground.remote

import com.druk.llamacpp.LlamaGenerationCallback
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** What the request carries after an earlier turn failed and left its user message unanswered. */
class RemoteOpenAiBackendHistoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private class Collect : LlamaGenerationCallback {
        override fun onFullResponse(response: String) {}
    }

    private fun backend() = RemoteOpenAiBackend(
        client = RemoteOpenAiClient(server.url("/").toString()),
        model = "test-model",
        systemPrompt = "",
        temperature = 0.7f, topP = 0.9f, topK = 40, minP = 0f, repeatPenalty = 1.0f, seed = -1,
        maxContext = 8192,
    )

    private fun okStream() = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n"
        )

    // A server whose template requires alternating roles rejects two user turns in a row, for
    // every later message, until the chat is reset.
    @Test
    fun aFailedTurnIsMergedWithTheRetry() {
        val b = backend()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        b.addMessage("first try", true)
        runBlocking { b.generateAll(Collect()) }
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(okStream())
        b.addMessage("second try", true)
        runBlocking { b.generateAll(Collect()) }

        val body = JSONObject(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("first try\n\nsecond try", messages.getJSONObject(0).getString("content"))
    }
}
