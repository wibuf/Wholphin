package com.github.damontecres.wholphin.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbListRatingsTest {
    private val json = Json { ignoreUnknownKeys = true }

    // A real response from the plugin, as posted in the feature discussion
    private val sample =
        """
        {
          "hasCache": true,
          "cachedAtUtc": "2026-08-18T14:12:20Z",
          "ids": { "tmdb": 203801, "imdb": "tt1638355" },
          "ratings": [
            { "source": "imdb", "value": 7.2, "score": 72, "votes": 359161 },
            { "source": "tomatoes", "value": 68, "score": 68, "votes": 294 },
            { "source": "popcorn", "value": 73, "score": 73, "votes": 2699 },
            { "source": "tmdb", "value": 70, "score": 70, "votes": 6969 }
          ]
        }
        """.trimIndent()

    private fun parse(text: String) = json.decodeFromString<MdbListRatingsResponse>(text)

    @Test
    fun `parses a real plugin response`() {
        val response = parse(sample)
        assertTrue(response.hasCache)
        assertEquals(4, response.ratings.size)
    }

    @Test
    fun `finds each source we display`() {
        val response = parse(sample)
        assertEquals(7.2, response.forSource(MdbListSource.IMDB)?.value)
        assertEquals(68.0, response.forSource(MdbListSource.TOMATOES)?.value)
        assertEquals(73.0, response.forSource(MdbListSource.POPCORN)?.value)
        assertEquals(70.0, response.forSource(MdbListSource.TMDB)?.value)
    }

    @Test
    fun `ignores ids and any other field the plugin adds`() {
        // "ids" is not modelled, and a newer plugin may add more; neither should fail the parse
        val withExtra = sample.replace("\"hasCache\": true", "\"hasCache\": true, \"somethingNew\": 5")
        assertEquals(4, parse(withExtra).ratings.size)
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
    fun `a rating with no value at all still parses`() {
        val partial = """{"hasCache":true,"ratings":[{"source":"trakt"}]}"""
        val rating = parse(partial).ratings.single()
        assertEquals("trakt", rating.source)
        assertNull(rating.value)
        assertNull(rating.score)
    }
}
