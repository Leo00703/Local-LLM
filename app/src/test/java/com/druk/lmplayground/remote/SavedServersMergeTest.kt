package com.druk.lmplayground.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedServersMergeTest {

    private val strong = "S:C:\\models\\q.gguf|m"
    private val weak = "W:|a|b"

    private fun server(id: String, url: String, fp: String = "", name: String = "", alternates: List<String> = emptyList()) =
        SavedServer(id, name, url, null, "Strata", alternates, fp)

    private val lan = server("1", "http://192.168.1.12:8080", strong, "PC")
    private val tailnet = server("2", "http://100.73.10.119:8080", strong)

    // --- recognising the same machine -----------------------------------------------------

    @Test
    fun theSameFingerprintAtAnotherAddressIsTheSameServer() {
        assertEquals(lan, SavedServers.findSameServer(listOf(lan), strong, "http://100.73.10.119:8080"))
    }

    @Test
    fun theSameAddressOrAnotherFingerprintIsNot() {
        assertNull(SavedServers.findSameServer(listOf(lan), strong, "http://192.168.1.12:8080/"))
        assertNull(SavedServers.findSameServer(listOf(lan), "S:other|m", "http://100.73.10.119:8080"))
        assertNull(SavedServers.findSameServer(listOf(lan), "", "http://100.73.10.119:8080"))
        assertNull(SavedServers.findSameServer(listOf(lan), strong, "http://100.73.10.119:8080", exceptId = "1"))
    }

    @Test
    fun anAddressAlreadyAddedIsNotProposedAgain() {
        val merged = lan.copy(alternates = listOf("http://100.73.10.119:8080"))
        assertNull(SavedServers.findSameServer(listOf(merged), strong, "http://100.73.10.119:8080"))
    }

    // --- merging -------------------------------------------------------------------------

    @Test
    fun mergingKeepsOneServerWithBothAddresses() {
        val out = SavedServers.merge(listOf(lan, tailnet, server("3", "http://x:1")), "1", "2")
        assertEquals(listOf("1", "3"), out.map { it.id })
        val kept = out.first()
        assertEquals("http://192.168.1.12:8080", kept.url)
        assertEquals(listOf("http://100.73.10.119:8080"), kept.alternates)
        assertEquals("PC", kept.name)
        assertEquals(strong, kept.fingerprint)
    }

    @Test
    fun mergingCarriesOverTheOtherAddressesAndNeverDuplicates() {
        val a = server("1", "http://192.168.1.12:8080", strong)
        val b = server("2", "http://100.73.10.119:8080", strong, alternates = listOf("http://192.168.1.12:8080", "http://pc.ts.net:8080"))
        val kept = SavedServers.merge(listOf(a, b), "1", "2").single()
        assertEquals(listOf("http://100.73.10.119:8080", "http://pc.ts.net:8080"), kept.alternates)
        assertEquals(
            listOf("http://192.168.1.12:8080", "http://100.73.10.119:8080", "http://pc.ts.net:8080"),
            kept.addresses,
        )
    }

    @Test
    fun mergingWithUnknownIdsChangesNothing() {
        val list = listOf(lan, tailnet)
        assertEquals(list, SavedServers.merge(list, "1", "9"))
        assertEquals(list, SavedServers.merge(list, "9", "1"))
        assertEquals(list, SavedServers.merge(list, "1", "1"))
    }

    @Test
    fun addingAnAddressFillsInAMissingFingerprint() {
        val bare = server("1", "http://192.168.1.12:8080")
        val out = SavedServers.addAddress(listOf(bare), "1", "http://100.73.10.119:8080", strong).single()
        assertEquals(strong, out.fingerprint)
        assertEquals(listOf("http://100.73.10.119:8080"), out.alternates)
    }

    // --- duplicates already saved ----------------------------------------------------------

    @Test
    fun onlyStrongFingerprintsMakeDuplicateGroups() {
        val w1 = server("3", "http://192.168.1.50:1234", weak)
        val w2 = server("4", "http://100.1.2.3:1234", weak)
        val groups = SavedServers.duplicateGroups(listOf(lan, tailnet, w1, w2))
        assertEquals(1, groups.size)
        assertEquals(listOf("1", "2"), groups.single().map { it.id })
    }

    @Test
    fun mergeDuplicatesFoldsEachGroupIntoItsFirstServerAndLeavesTheRest() {
        val w1 = server("3", "http://192.168.1.50:1234", weak)
        val w2 = server("4", "http://100.1.2.3:1234", weak)
        val out = SavedServers.mergeDuplicates(listOf(lan, w1, tailnet, w2))
        assertEquals(listOf("1", "3", "4"), out.map { it.id })
        assertEquals(listOf("http://100.73.10.119:8080"), out.first().alternates)
        assertTrue(SavedServers.duplicateGroups(out).isEmpty())
    }

    // --- saving and storing ----------------------------------------------------------------

    @Test
    fun editingAServerKeepsItsOtherAddressesAndItsFingerprint() {
        val merged = lan.copy(alternates = listOf("http://100.73.10.119:8080"))
        val out = SavedServers.save(listOf(merged), "1", "new", "Renamed", "http://192.168.1.12:8080", null, "Strata").single()
        assertEquals("Renamed", out.name)
        assertEquals(listOf("http://100.73.10.119:8080"), out.alternates)
        assertEquals(strong, out.fingerprint)
    }

    @Test
    fun anAlternateThatBecomesThePrimaryIsNotKeptTwice() {
        val merged = lan.copy(alternates = listOf("http://100.73.10.119:8080"))
        val out = SavedServers.save(listOf(merged), "1", "new", "", "http://100.73.10.119:8080", null, "Strata").single()
        assertEquals(emptyList<String>(), out.alternates)
    }

    @Test
    fun theNewFieldsSurviveJson() {
        val withAlt = lan.copy(alternates = listOf("http://100.73.10.119:8080", "http://pc.ts.net:8080"))
        val back = SavedServers.fromJson(SavedServers.toJson(listOf(withAlt, tailnet)))
        assertEquals(listOf(withAlt, tailnet), back)
    }

    // Lists saved before alternates existed must still load.
    @Test
    fun oldJsonWithoutTheNewFieldsStillLoads() {
        val old = """[{"id":"1","name":"PC","url":"http://192.168.1.12:8080","apiKey":"","type":"llama.cpp"}]"""
        val s = SavedServers.fromJson(old).single()
        assertEquals(emptyList<String>(), s.alternates)
        assertEquals("", s.fingerprint)
        assertEquals(listOf("http://192.168.1.12:8080"), s.addresses)
    }
}
