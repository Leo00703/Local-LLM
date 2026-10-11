package com.druk.lmplayground.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private class Fixed(override val name: String, private val reply: String) : Tool {
        override val description = "test tool"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override fun execute(arguments: String): String = reply
    }

    private class Failing(override val name: String) : Tool {
        override val description = "test tool"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override fun execute(arguments: String): String = throw IllegalStateException("boom")
    }

    private fun calls(vararg names: String): String =
        JSONArray().also { arr ->
            names.forEachIndexed { i, n ->
                arr.put(JSONObject().put("id", "c$i").put("name", n).put("arguments", "{}"))
            }
        }.toString()

    private fun contentOf(results: String, index: Int): JSONObject =
        JSONObject(JSONArray(results).getJSONObject(index).getString("content"))

    @Test
    fun enabledToolRuns() {
        val registry = ToolRegistry().apply {
            register(Fixed("echo", """{"ok":true}"""))
            setToolEnabled("echo", true)
        }
        val out = registry.executeToolCalls(calls("echo"), enforceEnabled = true)
        assertTrue(contentOf(out, 0).getBoolean("ok"))
    }

    // The model was only shown enabled tools, but it can still name one that was left out.
    @Test
    fun disabledToolIsRefusedWhenEnforced() {
        val registry = ToolRegistry().apply { register(Fixed("echo", """{"ok":true}""")) }
        val out = registry.executeToolCalls(calls("echo"), enforceEnabled = true)
        assertTrue(contentOf(out, 0).has("error"))
    }

    @Test
    fun disabledToolStillRunsWhenNotEnforced() {
        val registry = ToolRegistry().apply { register(Fixed("echo", """{"ok":true}""")) }
        val out = registry.executeToolCalls(calls("echo"))
        assertTrue(contentOf(out, 0).getBoolean("ok"))
    }

    @Test
    fun unknownToolIsAnError() {
        val out = ToolRegistry().executeToolCalls(calls("nope"), enforceEnabled = true)
        assertTrue(contentOf(out, 0).getString("error").contains("nope"))
    }

    // One tool throwing must not lose the other calls of the same round.
    @Test
    fun aThrowingToolDoesNotLoseTheOthers() {
        val registry = ToolRegistry().apply {
            register(Failing("bad"))
            register(Fixed("good", """{"ok":true}"""))
            setToolEnabled("bad", true)
            setToolEnabled("good", true)
        }
        val out = registry.executeToolCalls(calls("bad", "good"), enforceEnabled = true)
        assertEquals(2, JSONArray(out).length())
        assertEquals("boom", contentOf(out, 0).getString("error"))
        assertTrue(contentOf(out, 1).getBoolean("ok"))
    }
}
