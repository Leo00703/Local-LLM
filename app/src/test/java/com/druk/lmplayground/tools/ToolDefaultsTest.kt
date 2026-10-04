package com.druk.lmplayground.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDefaultsTest {

    private val all = listOf("web_search", "calculator", "location", "memory")

    // --- what a model starts with ------------------------------------------------------

    @Test
    fun aRemoteModelStartsWithEveryToolOn() {
        listOf("web_search", "calculator", "memory").forEach {
            assertTrue(it, ToolDefaults.defaultFor("remote:qwen3-4b", it, globalDefault = false, locationGranted = false))
        }
    }

    @Test
    fun aRemoteModelGetsLocationOnlyWithItsPermission() {
        assertFalse(ToolDefaults.defaultFor("remote:x", "location", globalDefault = false, locationGranted = false))
        assertTrue(ToolDefaults.defaultFor("remote:x", "location", globalDefault = false, locationGranted = true))
    }

    // The remote default replaces the global one: the user chose the server to use the tools.
    @Test
    fun theGlobalDefaultDoesNotSwitchOffARemoteModel() {
        assertTrue(ToolDefaults.defaultFor("remote:x", "calculator", globalDefault = false, locationGranted = false))
    }

    @Test
    fun aLocalModelFollowsTheGlobalDefault() {
        assertFalse(ToolDefaults.defaultFor("Qwen3-4B-Q4_K_M.gguf", "calculator", globalDefault = false, locationGranted = true))
        assertTrue(ToolDefaults.defaultFor("Qwen3-4B-Q4_K_M.gguf", "calculator", globalDefault = true, locationGranted = false))
        // A local model never gets Location on just because the permission is held.
        assertFalse(ToolDefaults.defaultFor("Qwen3-4B-Q4_K_M.gguf", "location", globalDefault = false, locationGranted = true))
    }

    @Test
    fun onlyTheRemotePrefixMakesAModelRemote() {
        assertTrue(ToolDefaults.isRemote("remote:llama3.2:latest"))
        assertFalse(ToolDefaults.isRemote("gemma-4-E2B-it.litertlm"))
        assertFalse(ToolDefaults.isRemote("my-remote:model.gguf"))
    }

    // --- the Enable all switch ---------------------------------------------------------

    @Test
    fun switchingOnSkipsLocationWithoutItsPermission() {
        assertEquals(listOf("web_search", "calculator", "memory"), ToolDefaults.bulkTargets(all, locationGranted = false, enable = true))
        assertEquals(all, ToolDefaults.bulkTargets(all, locationGranted = true, enable = true))
    }

    // Switching off clears everything, including a Location left on from before.
    @Test
    fun switchingOffTouchesEveryTool() {
        assertEquals(all, ToolDefaults.bulkTargets(all, locationGranted = false, enable = false))
    }

    @Test
    fun theSwitchReadsOnWhenEveryToolItCouldEnableIsOn() {
        val states = mapOf("web_search" to true, "calculator" to true, "memory" to true, "location" to false)
        // Without the permission Location is out of scope, so the switch reads on...
        assertTrue(ToolDefaults.allOn(all, states, locationGranted = false))
        // ...and with it, a Location left off keeps the switch off.
        assertFalse(ToolDefaults.allOn(all, states, locationGranted = true))
    }

    @Test
    fun theSwitchReadsOffWhenOneToolIsOffOrStatesAreEmpty() {
        assertFalse(ToolDefaults.allOn(all, mapOf("web_search" to true, "calculator" to false, "memory" to true), locationGranted = false))
        assertFalse(ToolDefaults.allOn(all, emptyMap(), locationGranted = false))
    }

    @Test
    fun noToolsMeansTheSwitchIsOff() {
        assertFalse(ToolDefaults.allOn(emptyList(), emptyMap(), locationGranted = true))
    }
}
