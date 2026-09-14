package com.github.damontecres.wholphin.ui.components

import com.github.damontecres.wholphin.data.model.MdbListRatingsResponse
import com.github.damontecres.wholphin.data.model.MdbListSource
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MdbListFormattingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(text: String) = json.decodeFromString<MdbListRatingsResponse>(text)

    // Gravity Falls, from a live server
    private val sample =
        parse(
            """
            {"hasCache":true,"ratings":[
            {"source":"imdb","value":8.9,"score":89,"votes":184721,"url":"487"},
            {"source":"tomatoes","value":100,"score":100,"votes":20},
            {"source":"popcorn","value":96,"score":96},
            {"source":"tmdb","value":86,"score":86,"votes":3598},
            {"source":"letterboxd"}]}
            """.trimIndent(),
        )

    @Test
    fun `imdb keeps its own out of ten scale`() {
        val value = sample.valueFor(MdbListSource.IMDB)!!
        assertEquals(8.9, value, 0.001)
        assertEquals("8.9", formatRating(MdbListSource.IMDB, value))
    }

    @Test
    fun `imdb converts a score when no value is present`() {
        // score is 0-100 while imdb's own scale is out of ten, so 89 must become 8.9 not 89.0
        val onlyScore = parse("""{"hasCache":true,"ratings":[{"source":"imdb","score":89}]}""")
        assertEquals(8.9, onlyScore.valueFor(MdbListSource.IMDB)!!, 0.001)
    }

    @Test
    fun `rotten tomatoes reads as a percentage`() {
        assertEquals("100%", formatRating(MdbListSource.TOMATOES, sample.valueFor(MdbListSource.TOMATOES)!!))
        assertEquals("96%", formatRating(MdbListSource.POPCORN, sample.valueFor(MdbListSource.POPCORN)!!))
    }

    @Test
    fun `tmdb is shown out of ten like the site does`() {
        // The API returns 86 out of 100, but TMDB presents it as 8.6
        assertEquals("8.6", formatRating(MdbListSource.TMDB, sample.valueFor(MdbListSource.TMDB)!!))
    }

    @Test
    fun `a source returned bare has no value`() {
        // The live response includes {"source":"tmdb"} style entries with nothing else on them
        val bare = parse("""{"hasCache":true,"ratings":[{"source":"tmdb"},{"source":"imdb"}]}""")
        assertNull(bare.valueFor(MdbListSource.TMDB))
        assertNull(bare.valueFor(MdbListSource.IMDB))
    }

    @Test
    fun `a source the plugin never returned has no value`() {
        val onlyImdb = parse("""{"hasCache":true,"ratings":[{"source":"imdb","value":7.0}]}""")
        assertNull(onlyImdb.valueFor(MdbListSource.TOMATOES))
    }

    @Test
    fun `percentages are whole numbers, never decimals`() {
        val odd = parse("""{"hasCache":true,"ratings":[{"source":"tomatoes","value":68.7}]}""")
        assertEquals("68%", formatRating(MdbListSource.TOMATOES, odd.valueFor(MdbListSource.TOMATOES)!!))
    }
}
