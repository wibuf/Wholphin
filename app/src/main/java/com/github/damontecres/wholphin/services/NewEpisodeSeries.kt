package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.HomePagePreferences
import com.github.damontecres.wholphin.ui.joinNotBlank
import com.github.damontecres.wholphin.ui.seasonEpisode
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork-only (GooseFlix #1501): Recently Added shows a single new episode as its show.
 *
 * The server groups several new episodes of a show into the show, but a single new episode comes
 * back as the episode, so the home page header showed that episode's name and synopsis: no help
 * if you don't know the show. With this on, the episode is swapped for its show (card, header and
 * click all behave as the show) and the header adds a line saying which episode is new.
 */
object NewEpisodeSeries {
    /** Series id -> "S1E2 · Episode name" for shows standing in for a new episode */
    private val labels = ConcurrentHashMap<UUID, String>()

    fun enabled(prefs: HomePagePreferences): Boolean = BuildConfig.GOOSEFLIX && !prefs.recentlyAddedEpisodeDetails

    /** Series ids to fetch for [items]: those of the episodes in it */
    fun seriesIds(items: List<BaseItemDto>): List<UUID> =
        items
            .filter { it.type == BaseItemKind.EPISODE }
            .mapNotNull { it.seriesId }
            .distinct()

    /**
     * [items] with each episode replaced by its show from [series], where it was found. Records the
     * line the header shows for each swapped show, and forgets it for shows that came back whole.
     */
    fun swap(
        items: List<BaseItemDto>,
        series: Map<UUID, BaseItemDto>,
    ): List<BaseItemDto> =
        items.map { dto ->
            when (dto.type) {
                BaseItemKind.EPISODE -> {
                    val show = dto.seriesId?.let { series[it] }
                    if (show != null) {
                        labels[show.id] = episodeLabel(dto)
                        show
                    } else {
                        dto
                    }
                }

                BaseItemKind.SERIES -> {
                    labels.remove(dto.id)
                    dto
                }

                else -> {
                    dto
                }
            }
        }

    fun episodeLabel(episode: BaseItemDto): String = listOf(episode.seasonEpisode, episode.name).joinNotBlank(" · ") ?: ""

    /** The episode line for a show standing in for a new episode, eg "S1E2 · The Beef", or null */
    fun label(item: BaseItem?): String? =
        item
            ?.takeIf { BuildConfig.GOOSEFLIX && it.type == BaseItemKind.SERIES }
            ?.let { labels[it.id] }
            ?.takeIf { it.isNotBlank() }
}
