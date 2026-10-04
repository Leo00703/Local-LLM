package com.druk.lmplayground.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedServersTest {

    private fun server(id: String, url: String, name: String = "", key: String? = null, type: String = "") =
        SavedServer(id, name, url, key, type)

    // --- addresses ---------------------------------------------------------------------

    @Test
    fun aMissingSchemeAndTrailingSlashAreFixed() {
        assertEquals("http://192.168.1.5:8080", SavedServers.normalizeUrl("192.168.1.5:8080/"))
        assertEquals("https://llm.example.com", SavedServers.normalizeUrl("https://llm.example.com//"))
    }

    // The client appends /v1/models itself; a pasted "/v1" would turn into /v1/v1/models.
    @Test
    fun aTrailingV1IsDropped() {
        assertEquals("http://host:1234", SavedServers.normalizeUrl("http://host:1234/v1"))
        assertEquals("http://host:1234", SavedServers.normalizeUrl("host:1234/V1/"))
    }

    @Test
    fun otherPathsAndBlankInputAreLeftAlone() {
        assertEquals("http://host:1234/api", SavedServers.normalizeUrl("http://host:1234/api"))
        assertEquals("", SavedServers.normalizeUrl("   "))
    }

    @Test
    fun theHostIsWhatFollowsTheScheme() {
        assertEquals("192.168.1.5:8080", SavedServers.hostOf("http://192.168.1.5:8080"))
        assertEquals("nohost", SavedServers.hostOf("nohost"))
    }

    @Test
    fun theLabelIsTheNameElseTheAddress() {
        assertEquals("My PC", server("1", "http://a:1", name = "My PC").label)
        assertEquals("a:1", server("1", "http://a:1").label)
    }

    @Test
    fun sameEndpointIgnoresCaseAndTrailingSlash() {
        assertTrue(SavedServers.sameEndpoint("http://Host:1234/", "http://host:1234"))
        assertFalse(SavedServers.sameEndpoint("http://host:1234", "http://host:1235"))
    }

    // --- the list ----------------------------------------------------------------------

    @Test
    fun upsertKeepsThePlaceOfAnExistingServer() {
        val list = listOf(server("a", "http://a:1"), server("b", "http://b:1"), server("c", "http://c:1"))
        val updated = SavedServers.upsert(list, server("b", "http://b:2", name = "B"))
        assertEquals(listOf("a", "b", "c"), updated.map { it.id })
        assertEquals("http://b:2", updated[1].url)
    }

    @Test
    fun upsertAppendsANewServer() {
        val list = listOf(server("a", "http://a:1"))
        assertEquals(listOf("a", "b"), SavedServers.upsert(list, server("b", "http://b:1")).map { it.id })
    }

    @Test
    fun removeDropsOnlyThatServer() {
        val list = listOf(server("a", "http://a:1"), server("b", "http://b:1"))
        assertEquals(listOf("b"), SavedServers.remove(list, "a").map { it.id })
        assertEquals(2, SavedServers.remove(list, "zzz").size)
    }

    // --- saving the form ---------------------------------------------------------------

    @Test
    fun savingAnewAddressAddsAServer() {
        val list = SavedServers.save(emptyList(), null, "new-id", "  Desk  ", "http://a:1", "  key  ", "llama.cpp")
        assertEquals(1, list.size)
        assertEquals(SavedServer("new-id", "Desk", "http://a:1", "key", "llama.cpp"), list[0])
    }

    @Test
    fun aBlankKeyIsStoredAsNone() {
        assertNull(SavedServers.save(emptyList(), null, "x", "", "http://a:1", "   ", "").single().apiKey)
    }

    @Test
    fun editingUpdatesTheSameEntryInPlace() {
        val list = listOf(server("a", "http://a:1"), server("b", "http://b:1"))
        val saved = SavedServers.save(list, "a", "unused", "Renamed", "http://a:9", null, "Ollama")
        assertEquals(listOf("a", "b"), saved.map { it.id })
        assertEquals("Renamed", saved[0].name)
        assertEquals("http://a:9", saved[0].url)
        assertEquals("Ollama", saved[0].type)
    }

    // An address is never saved twice: typing a saved address again updates that entry.
    @Test
    fun savingAnAddressThatIsAlreadySavedUpdatesIt() {
        val list = listOf(server("a", "http://a:1", name = "Old"))
        val saved = SavedServers.save(list, null, "fresh", "New name", "http://A:1/", "k", "")
        assertEquals(1, saved.size)
        assertEquals("a", saved[0].id)
        assertEquals("New name", saved[0].name)
    }

    @Test
    fun editingAServerOntoAnotherAddressReplacesTheOther() {
        val list = listOf(server("a", "http://a:1"), server("b", "http://b:1"))
        val saved = SavedServers.save(list, "a", "unused", "A", "http://b:1", null, "")
        assertEquals(listOf("a"), saved.map { it.id })
        assertEquals("http://b:1", saved[0].url)
    }

    // --- the single server from before ------------------------------------------------

    @Test
    fun theOldSingleServerBecomesTheFirstSavedOne() {
        val migrated = SavedServers.fromLegacy("id1", " Studio ", "192.168.1.5:1234/", " key ", "LM Studio")
        assertEquals(SavedServer("id1", "Studio", "http://192.168.1.5:1234", "key", "LM Studio"), migrated)
    }

    @Test
    fun noOldServerMeansNothingToMigrate() {
        assertNull(SavedServers.fromLegacy("id", "name", null, null, null))
        assertNull(SavedServers.fromLegacy("id", "name", "  ", "key", "Ollama"))
    }

    // --- storage ----------------------------------------------------------------------

    @Test
    fun theListSurvivesAJsonRoundTrip() {
        val list = listOf(
            server("a", "http://a:1", name = "A", key = "secret", type = "llama.cpp"),
            server("b", "http://b:2"),
        )
        assertEquals(list, SavedServers.fromJson(SavedServers.toJson(list)))
    }

    // A name or key with quotes and unicode must not break the stored text.
    @Test
    fun awkwardCharactersSurviveStorage() {
        val list = listOf(server("a", "http://a:1", name = "Leo's \"PC\" éè", key = "k\\ey\"1"))
        assertEquals(list, SavedServers.fromJson(SavedServers.toJson(list)))
    }

    @Test
    fun aDamagedEntryIsSkippedNotTheWholeList() {
        val json = """[{"id":"a","url":"http://a:1"},{"id":"","url":"http://x:1"},{"id":"c"},"junk",{"id":"d","url":"http://d:1","name":"D"}]"""
        assertEquals(listOf("a", "d"), SavedServers.fromJson(json).map { it.id })
    }

    @Test
    fun unreadableStorageGivesAnEmptyList() {
        assertTrue(SavedServers.fromJson("not json at all").isEmpty())
        assertTrue(SavedServers.fromJson("").isEmpty())
    }
}
