package com.github.damontecres.wholphin.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbListRatingsTest {
    private val json = Json { ignoreUnknownKeys = true }

    // A real response from a live server: Gravity Falls (tmdb 40075). Note that several
    // sources come back with no numbers at all, and that imdb carries both an out-of-ten
    // value and an out-of-100 score.
    private val sample =
        """
        {"hasCache":true,"cachedAtUtc":"2026-09-14T11:05:22.8975225+00:00",
         "ids":{"tmdb":40075,"imdb":"tt1865718"},
         "ratings":[{"source":"imdb","value":8.9,"score":89,"votes":184721,"url":"487"},
         {"source":"metacritic","votes":3,"url":"/gravity-falls"},
         {"source":"metacriticuser","value":9.2,"score":92,"votes":610},
         {"source":"trakt","value":89,"score":89,"votes":8055},
         {"source":"tomatoes","value":100,"score":100,"votes":20,"url":"/tv/gravity_falls"},
         {"source":"popcorn","value":96,"score":96,"url":"/tv/gravity_falls"},
         {"source":"tmdb","value":86,"score":86,"votes":3598},
         {"source":"letterboxd"},{"source":"rogerebert"},{"source":"myanimelist"},
         {"source":"tvmaze","value":8.8,"score":88,"url":"https://www.tvmaze.com/shows/396/gravity-falls"}]}
        """.trimIndent()

    private fun parse(text: String) = json.decodeFromString<MdbListRatingsResponse>(text)

    @Test
    fun `parses a real plugin response`() {
        val response = parse(sample)
        assertTrue(response.hasCache)
        assertEquals(11, response.ratings.size)
    }

    @Test
    fun `finds each source we display`() {
        val response = parse(sample)
        assertEquals(8.9, response.forSource(MdbListSource.IMDB)?.value)
        assertEquals(100.0, response.forSource(MdbListSource.TOMATOES)?.value)
        assertEquals(96.0, response.forSource(MdbListSource.POPCORN)?.value)
        assertEquals(86.0, response.forSource(MdbListSource.TMDB)?.value)
    }

    @Test
    fun `ignores ids and any other field the plugin adds`() {
        // "ids" is not modelled, and a newer plugin may add more; neither should fail the parse
        val withExtra = sample.replace("\"hasCache\": true", "\"hasCache\": true, \"somethingNew\": 5")
        assertEquals(11, parse(withExtra).ratings.size)
    }

    @Test
    fun `source matching is case insensitive`() {
        val upper = sample.replace("\"imdb\"", "\"IMDb\"")
        assertNotNull(parse(upper).forSource(MdbListSource.IMDB))
    }

    @Test
    fun `a source the plugin did not return is absent rather than zero`() {
        val onlyImdb = """{"hasCache":true,"ratings":[{"source":"imdb","value":7.2}]}"""
        val response = parse(onlyImdb)
        assertNotNull(response.forSource(MdbListSource.IMDB))
        assertNull(response.forSource(MdbListSource.TOMATOES))
    }

    @Test
    fun `an empty cache parses without ratings`() {
        val empty = """{"hasCache":false,"ratings":[]}"""
        val response = parse(empty)
        assertEquals(false, response.hasCache)
        assertTrue(response.ratings.isEmpty())
    }

    @Test
    fun `sources the plugin returns bare carry no numbers`() {
        // The live response includes {"source":"letterboxd"} with nothing else
        val response = parse(sample)
        val letterboxd = response.ratings.single { it.source == "letterboxd" }
        assertNull(letterboxd.value)
        assertNull(letterboxd.score)
    }

    @Test
    fun `a source with votes but no rating is still valueless`() {
        // metacritic comes back as votes plus a url, with no value or score
        val metacritic = parse(sample).ratings.single { it.source == "metacritic" }
        assertEquals(3, metacritic.votes)
        assertNull(metacritic.value)
        assertNull(metacritic.score)
    }

    @Test
    fun `url is not always a url`() {
        // imdb's url is "487" and metacritic's is a path, so it cannot be treated as a link
        assertEquals("487", parse(sample).forSource(MdbListSource.IMDB)?.url)
    }
}
