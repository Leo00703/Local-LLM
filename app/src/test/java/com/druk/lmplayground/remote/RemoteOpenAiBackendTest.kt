package com.druk.lmplayground.remote

import com.druk.llamacpp.LlamaGenerationCallback
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The remote backend against a scripted server: how a reply ends (normally, at a limit, cut
 * off, empty, asking for tools) and what the follow-up request carries once the tool budget
 * is spent. These are the cases behind "generation stops without any output".
 */
class RemoteOpenAiBackendTest {

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
        var last = ""
        override fun onFullResponse(response: String) {
            last = response
        }
    }

    private fun backend(): RemoteOpenAiBackend = RemoteOpenAiBackend(
        client = RemoteOpenAiClient(server.url("/").toString()),
        model = "test-model",
        systemPrompt = "",
        temperature = 0.7f, topP = 0.9f, topK = 40, minP = 0f, repeatPenalty = 1.0f, seed = -1,
        maxContext = 8192,
    )

    private fun frame(delta: String, finish: String? = null) =
        """{"choices":[{"index":0,"delta":$delta,"finish_reason":${if (finish == null) "null" else "\"$finish\""}}]}"""

    private fun stream(vararg frames: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(frames.joinToString("") { "data: $it\n\n" })

    private val toolsJson =
        """[{"type":"function","function":{"name":"web_search","description":"d","parameters":{"type":"object","properties":{}}}}]"""

    /** A reply where the model asks for web_search("cats") and then finishes with tool_calls. */
    private fun toolCallStream() = stream(
        frame("""{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":""}}]}"""),
        frame("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":\"cats\"}"}}]}"""),
        frame("{}", "tool_calls"),
        "[DONE]",
    )

    private fun generate(b: RemoteOpenAiBackend): Pair<Int, String> {
        val cb = Collect()
        val rc = runBlocking { b.generateAll(cb) }
        return rc to cb.last
    }

    private fun nextRequestBody(): JSONObject =
        JSONObject(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())

    // --- how a reply ends ---------------------------------------------------------------

    @Test
    fun aNormalReplyEndsWithoutAWarning() {
        server.enqueue(stream(frame("""{"content":"Hello"}"""), frame("{}", "stop"), "[DONE]"))
        val b = backend().apply { addMessage("hi", false) }
        val (rc, text) = generate(b)
        assertEquals(0, rc)
        assertEquals("Hello", text)
    }

    @Test
    fun aReplyCutByTheServersLimitSaysSo() {
        server.enqueue(stream(frame("""{"content":"Half an ans"}"""), frame("{}", "length"), "[DONE]"))
        val (rc, text) = generate(backend().apply { addMessage("hi", false) })
        assertEquals(0, rc)
        assertTrue(text, text.startsWith("Half an ans"))
        assertTrue(text, text.endsWith(RemoteOpenAiBackend.NOTE_LENGTH))
    }

    // The connection dropping mid-reply looks like a clean end of stream to the reader.
    @Test
    fun aStreamThatEndsWithoutAnEndMarkerSaysTheConnectionClosed() {
        server.enqueue(stream(frame("""{"content":"Partial"}""")))
        val (rc, text) = generate(backend().apply { addMessage("hi", false) })
        assertEquals(0, rc)
        assertTrue(text, text.startsWith("Partial"))
        assertTrue(text, text.endsWith(RemoteOpenAiBackend.NOTE_CUT))
    }

    @Test
    fun anEmptyReplyIsReportedInsteadOfLeavingABlankBubble() {
        server.enqueue(stream(frame("{}", "stop"), "[DONE]"))
        val (rc, text) = generate(backend().apply { addMessage("hi", false) })
        assertEquals(0, rc)
        assertEquals(RemoteOpenAiBackend.NOTE_EMPTY, text)
    }

    @Test
    fun theEndNoteRulesInIsolation() {
        assertNull(RemoteOpenAiBackend.endNote("stop", sawDone = true, hasText = true))
        assertNull(RemoteOpenAiBackend.endNote(null, sawDone = true, hasText = true))
        assertEquals(RemoteOpenAiBackend.NOTE_LENGTH, RemoteOpenAiBackend.endNote("length", sawDone = true, hasText = true))
        assertEquals(RemoteOpenAiBackend.NOTE_CUT, RemoteOpenAiBackend.endNote(null, sawDone = false, hasText = true))
        assertEquals(RemoteOpenAiBackend.NOTE_EMPTY, RemoteOpenAiBackend.endNote("stop", sawDone = true, hasText = false))
    }

    // --- tool calls ---------------------------------------------------------------------

    @Test
    fun streamedToolCallFragmentsAreJoinedAndReturnedAsATurnForTheToolLoop() {
        server.enqueue(toolCallStream())
        val b = backend().apply { addMessage("research cats", false); setTools(toolsJson) }
        val (rc, _) = generate(b)
        assertEquals(2, rc)
        val calls = JSONArray(b.getToolCallsJson())
        assertEquals(1, calls.length())
        assertEquals("call_1", calls.getJSONObject(0).getString("id"))
        assertEquals("web_search", calls.getJSONObject(0).getString("name"))
        assertEquals("""{"query":"cats"}""", calls.getJSONObject(0).getString("arguments"))
        assertTrue("tools are offered while the budget lasts", nextRequestBody().has("tools"))
    }

    // The fix for "stops without an answer": at the cap the pending call is answered with a
    // limit message, tools are withdrawn, and the next request is a plain, well-formed one.
    @Test
    fun whenTheBudgetIsSpentThePendingCallIsAnsweredAndToolsAreWithdrawn() {
        server.enqueue(toolCallStream())
        server.enqueue(stream(frame("""{"content":"Here is what I found."}"""), frame("{}", "stop"), "[DONE]"))
        val b = backend().apply { addMessage("research cats", false); setTools(toolsJson) }
        assertEquals(2, generate(b).first)
        server.takeRequest(5, TimeUnit.SECONDS)

        b.concludeWithoutTools(enableThinking = false)
        val (rc, text) = generate(b)
        assertEquals(0, rc)
        assertEquals("Here is what I found.", text)

        val body = nextRequestBody()
        assertFalse("no tools once the budget is spent", body.has("tools"))
        val messages = body.getJSONArray("messages")
        val last = messages.getJSONObject(messages.length() - 1)
        assertEquals("tool", last.getString("role"))
        assertEquals("call_1", last.getString("tool_call_id"))
        assertTrue(last.getString("content"), last.getString("content").contains("limit"))
        // The model's own call is still in the history, directly before its answer.
        val before = messages.getJSONObject(messages.length() - 2)
        assertEquals("assistant", before.getString("role"))
        assertEquals(1, before.getJSONArray("tool_calls").length())
    }

    @Test
    fun theNextUserTurnOffersToolsAgain() {
        server.enqueue(toolCallStream())
        server.enqueue(stream(frame("""{"content":"Done."}"""), frame("{}", "stop"), "[DONE]"))
        server.enqueue(stream(frame("""{"content":"Sure."}"""), frame("{}", "stop"), "[DONE]"))
        val b = backend().apply { addMessage("research cats", false); setTools(toolsJson) }
        generate(b)
        b.concludeWithoutTools(enableThinking = false)
        generate(b)
        server.takeRequest(5, TimeUnit.SECONDS)
        server.takeRequest(5, TimeUnit.SECONDS)

        // What the view model does before every message.
        b.setTools(toolsJson)
        b.addMessage("and dogs?", false)
        generate(b)
        assertTrue(nextRequestBody().has("tools"))
    }
}
