package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.HomeRowConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork-only (GooseFlix #1636) */
class PersonalRowsTest {
    private val rows = PersonalRows.rows().filterIsInstance<HomeRowConfig.CustomEndpoint>()
    private val picked = rows.first { it.endpoint.endsWith("picked-for-you") }
    private val mood = rows.first { it.endpoint.endsWith("mood") }

    @Test
    fun `every personal row is recognised as personal`() {
        assertEquals(1 + 3 + PersonalRows.MOOD_SLOTS + 1, rows.size)
        assertEquals(PersonalRows.MY_REQUESTS, rows.last().endpoint)
        assertTrue(rows.all { PersonalRows.isPersonal(it) })
        assertFalse(PersonalRows.isPersonal(HomeRowConfig.CustomEndpoint(endpoint = "/HomeScreen/Section/X", title = "X")))
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), rows.filter { it.endpoint.endsWith("mood") }.map { it.query!!.single().value })
    }

    @Test
    fun `the plugin's title wins, and a mood row says how many are in the library`() {
        assertEquals("Picked for you", PersonalRows.title(picked, null, 40))
        assertEquals("Because you watched Inception", PersonalRows.title(picked, " Because you watched Inception ", null))
        assertEquals("Heists & cons · 12 in your library", PersonalRows.title(mood, "Heists & cons", 12))
        assertEquals("Heists & cons", PersonalRows.title(mood, "Heists & cons", 0))
        // Percent-encoded by the plugin
        assertEquals("Because you watched Amélie", PersonalRows.title(picked, "Because%20you%20watched%20Am%C3%A9lie", null))
        assertEquals("Heists & cons · 3 in your library", PersonalRows.title(mood, "Heists%20%26%20cons", 3))
        assertEquals("C++ stories", PersonalRows.title(picked, "C++ stories", null))
    }

    @Test
    fun `after a not-found the plugin isn't asked again for a while`() {
        PersonalRows.reset()
        assertTrue(PersonalRows.available(now = 1_000))
        PersonalRows.markUnavailable(now = 1_000)
        assertFalse(PersonalRows.available(now = 1_000 + PersonalRows.UNAVAILABLE_FOR.inWholeMilliseconds - 1))
        assertTrue(PersonalRows.available(now = 1_000 + PersonalRows.UNAVAILABLE_FOR.inWholeMilliseconds))
        PersonalRows.markUnavailable(now = 1_000)
        PersonalRows.reset()
        assertTrue(PersonalRows.available(now = 1_000))
    }

    private fun dto(
        name: String,
        type: org.jellyfin.sdk.model.api.BaseItemKind = org.jellyfin.sdk.model.api.BaseItemKind.MOVIE,
        tmdb: String? = null,
        imdb: String? = null,
        year: Int? = null,
    ) = org.jellyfin.sdk.model.api.BaseItemDto(
        id = java.util.UUID.randomUUID(),
        type = type,
        name = name,
        productionYear = year,
        providerIds = listOfNotNull(tmdb?.let { "Tmdb" to it }, imdb?.let { "Imdb" to it }).toMap(),
    )

    @Test
    fun `a title in two libraries shows once, the first copy`() {
        val movies = dto("Interstellar", tmdb = "157336")
        val fourK = dto("Interstellar", tmdb = "157336")
        val dunkirk = dto("Dunkirk", tmdb = "374720")
        assertEquals(listOf(movies, dunkirk), PersonalRows.distinctTitles(listOf(movies, dunkirk, fourK)))
    }

    @Test
    fun `without ids, name and year decide, and a movie and a show may share a name`() {
        val a = dto("The Office", year = 2005)
        val b = dto("The Office", year = 2005)
        val show = dto("The Office", type = org.jellyfin.sdk.model.api.BaseItemKind.SERIES, year = 2005)
        val imdbOnly = dto("Heat", imdb = "tt0113277")
        val imdbAgain = dto("Heat", imdb = "tt0113277")
        assertEquals(listOf(a, show, imdbOnly), PersonalRows.distinctTitles(listOf(a, b, show, imdbOnly, imdbAgain)))
    }

    @Test
    fun `copies of the title a Because you watched row is built from are dropped`() {
        val odyssey = dto("The Odyssey", tmdb = "1")
        val other = dto("Troy", tmdb = "2")
        assertEquals(listOf(other), PersonalRows.distinctTitles(listOf(odyssey, other), sourceTmdb = "1"))
    }
}
