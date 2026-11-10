package com.druk.lmplayground.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerIdentityTest {

    // --- fingerprints ----------------------------------------------------------------------

    private fun props(path: String, alias: String = "m") = JSONObject(
        """{"default_generation_settings":{"n_ctx":4096},"model_path":${JSONObject.quote(path)},
            "model_alias":"$alias","build_info":"Strata 0.1.57"}"""
    )

    // Strata and a single-model llama-server name the model file on that machine's disk.
    @Test
    fun aModelPathMakesAStrongFingerprintThatIgnoresTheAddress() {
        val a = ServerIdentity.fingerprint(props("C:\\models\\q.gguf"), listOf("m"))
        val b = ServerIdentity.fingerprint(props("C:\\models\\q.gguf"), listOf("m"))
        assertTrue(ServerIdentity.isStrong(a))
        assertEquals(a, b)
        assertNotEquals(a, ServerIdentity.fingerprint(props("D:\\other.gguf"), listOf("m")))
    }

    // A router answers model_path "none": that says nothing about the machine.
    @Test
    fun withoutAModelPathOnlyTheModelListIdentifiesTheServer() {
        val router = JSONObject("""{"role":"router","model_path":"none","build_info":"b12110"}""")
        val fp = ServerIdentity.fingerprint(router, listOf("b", "a", "a"))
        assertFalse(ServerIdentity.isStrong(fp))
        assertEquals(fp, ServerIdentity.fingerprint(router, listOf("a", "b")))
        assertNotEquals(fp, ServerIdentity.fingerprint(router, listOf("a", "c")))
    }

    @Test
    fun lmStudioIsIdentifiedByItsModelsAlone() {
        val fp = ServerIdentity.fingerprint(null, listOf("loaded-llm", "idle-llm"))
        assertFalse(ServerIdentity.isStrong(fp))
        assertEquals("", ServerIdentity.fingerprint(null, emptyList()))
        assertEquals("", ServerIdentity.fingerprint(JSONObject("""{"error":"Unexpected endpoint"}"""), listOf(" ")))
    }

    // --- addresses -------------------------------------------------------------------------

    @Test
    fun addressesAreRankedLanTailscaleOther() {
        assertEquals(0, ServerIdentity.addressRank("http://192.168.1.12:8080"))
        assertEquals(0, ServerIdentity.addressRank("http://10.0.0.5:1234"))
        assertEquals(0, ServerIdentity.addressRank("http://172.20.1.1:1234"))
        assertEquals(0, ServerIdentity.addressRank("http://my-pc:8080"))
        assertEquals(0, ServerIdentity.addressRank("http://pc.local:8080"))
        assertEquals(1, ServerIdentity.addressRank("http://100.73.10.119:8080"))
        assertEquals(1, ServerIdentity.addressRank("http://pc.tailnet-1234.ts.net:8080"))
        assertEquals(2, ServerIdentity.addressRank("https://llm.example.com"))
        assertEquals(2, ServerIdentity.addressRank("http://8.8.8.8:80"))
        assertEquals(2, ServerIdentity.addressRank("http://172.32.0.1:80"))
        assertEquals(2, ServerIdentity.addressRank("http://100.128.0.1:80"))
    }

    @Test
    fun theLocalNetworkIsTriedBeforeTailscale() {
        val ordered = ServerIdentity.orderAddresses(
            listOf("http://100.73.10.119:8080", "https://x.example.com", "http://192.168.1.12:8080", "http://10.1.1.1:8080")
        )
        assertEquals(
            listOf("http://192.168.1.12:8080", "http://10.1.1.1:8080", "http://100.73.10.119:8080", "https://x.example.com"),
            ordered,
        )
    }

    @Test
    fun anAddressIsDescribedWithItsKind() {
        assertEquals("192.168.1.12:8080 · Wi-Fi", ServerIdentity.describeAddress("http://192.168.1.12:8080", "Wi-Fi", "Tailscale"))
        assertEquals("100.73.10.119:8080 · Tailscale", ServerIdentity.describeAddress("http://100.73.10.119:8080", "Wi-Fi", "Tailscale"))
        assertEquals("llm.example.com", ServerIdentity.describeAddress("https://llm.example.com", "Wi-Fi", "Tailscale"))
    }
}
