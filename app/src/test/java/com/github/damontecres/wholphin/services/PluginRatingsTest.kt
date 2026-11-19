package com.github.damontecres.wholphin.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Fork-only (GooseFlix #1671): the Rating sort's helpers */
class PluginRatingsTest {
    @Test
    fun `the ratings feature is read from capabilities, any case`() {
        assertTrue(PluginRatings.hasFeature("""{"Version":"0.1.1.502","Features":["dislikes","ratings"]}"""))
        assertTrue(PluginRatings.hasFeature("""{"features":["Ratings"]}"""))
        assertFalse(PluginRatings.hasFeature("""{"Version":"0.1.1.501","Features":["dislikes"]}"""))
        assertFalse(PluginRatings.hasFeature("""{"Version":"0.1.1.500"}"""))
        assertFalse(PluginRatings.hasFeature("not json"))
    }

    @Test
    fun `values come keyed by ids without dashes`() {
        val id = UUID.fromString("0c419071-40d8-02bb-5843-0fed7e2cd79e")
        val values =
            PluginRatings.parseValues(
                """{"source":"myanimelist","values":{"0c41907140d802bb58430fed7e2cd79e":{"value":9.2,"score":92,"votes":1000},"nonsense":{"score":1}}}""",
            )
        assertEquals(mapOf(id to RatingScore(9.2, 92.0, 1000)), values)
    }

    @Test
    fun `highest first, unrated last in name order, either direction`() {
        val (a, b, c, d) = List(4) { UUID.randomUUID() }
        val scores = mapOf(a to RatingScore(score = 70.0), c to RatingScore(score = 90.0))
        assertEquals(listOf(c, a, b, d), PluginRatings.order(listOf(a, b, c, d), scores, descending = true))
        assertEquals(listOf(a, c, b, d), PluginRatings.order(listOf(a, b, c, d), scores, descending = false))
    }

    @Test
    fun `ties keep the incoming (name) order`() {
        val (a, b) = List(2) { UUID.randomUUID() }
        val scores = mapOf(a to RatingScore(score = 80.0), b to RatingScore(score = 80.0))
        assertEquals(listOf(a, b), PluginRatings.order(listOf(a, b), scores, descending = true))
    }

    @Test
    fun `card labels suit each source`() {
        assertEquals("★ 9.2", PluginRatings.label(RatingSortSource.MAL, RatingScore(9.2, 92.0)))
        assertEquals("★ 8.0", PluginRatings.label(RatingSortSource.IMDB, RatingScore(8.0, 80.0)))
        assertEquals("94%", PluginRatings.label(RatingSortSource.TOMATOES, RatingScore(94.0, 94.0)))
        assertEquals("88%", PluginRatings.label(RatingSortSource.POPCORN, RatingScore(null, 88.0)))
        assertEquals("★ 3.9", PluginRatings.label(RatingSortSource.LETTERBOXD, RatingScore(3.9, 78.0)))
        assertEquals("★ 74", PluginRatings.label(RatingSortSource.METACRITIC, RatingScore(74.0, 74.0)))
    }

    @Test
    fun `MyAnimeList by default for anime libraries, IMDb otherwise`() {
        assertEquals(RatingSortSource.MAL, RatingSortSource.defaultFor("Anime"))
        assertEquals(RatingSortSource.MAL, RatingSortSource.defaultFor("Anime (Streaming Test)"))
        assertEquals(RatingSortSource.IMDB, RatingSortSource.defaultFor("Movies"))
        assertEquals(RatingSortSource.IMDB, RatingSortSource.defaultFor(null))
    }
}
