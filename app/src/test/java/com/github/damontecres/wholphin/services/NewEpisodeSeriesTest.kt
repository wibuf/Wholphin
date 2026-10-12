package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.BaseItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class NewEpisodeSeriesTest {
    private val showId = UUID.randomUUID()
    private val show = BaseItemDto(id = showId, type = BaseItemKind.SERIES, name = "The Bear")
    private val episode =
        BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.EPISODE,
            name = "The Beef",
            seriesId = showId,
            seriesName = "The Bear",
            parentIndexNumber = 1,
            indexNumber = 2,
        )
    private val movie = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, name = "Sintel")

    @Test
    fun aSingleNewEpisodeBecomesItsShow() {
        assumeTrue(BuildConfig.GOOSEFLIX)
        val out = NewEpisodeSeries.swap(listOf(movie, episode), mapOf(showId to show))
        assertEquals(listOf(movie.id, showId), out.map { it.id })
        assertEquals("S1 E2 · The Beef", NewEpisodeSeries.label(BaseItem(show)))
        assertNull(NewEpisodeSeries.label(BaseItem(movie)))
    }

    @Test
    fun anEpisodeWhoseShowIsMissingStays() {
        val out = NewEpisodeSeries.swap(listOf(episode), emptyMap())
        assertEquals(listOf(episode.id), out.map { it.id })
    }

    @Test
    fun aShowThatComesBackWholeLosesTheLine() {
        assumeTrue(BuildConfig.GOOSEFLIX)
        NewEpisodeSeries.swap(listOf(episode), mapOf(showId to show))
        NewEpisodeSeries.swap(listOf(show), emptyMap())
        assertNull(NewEpisodeSeries.label(BaseItem(show)))
    }

    @Test
    fun onlyEpisodesNeedTheirShowFetched() {
        assertEquals(listOf(showId), NewEpisodeSeries.seriesIds(listOf(movie, episode, show, episode)))
    }
}
