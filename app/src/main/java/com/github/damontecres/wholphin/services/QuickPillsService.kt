package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.BaseItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fork-only: a short row of one-press "pills" at the top of a user's home page (GooseFlix #1421).
 *
 * Which pills a user gets is stored with their Jellyfin account, in display preferences
 * [DISPLAY_PREFS_ID] for client [CLIENT], custom pref [PREF_KEY], as [QuickPillsConfig] JSON. Scuffed
 * writes it; users without one see no row. Each device can still hide the row in home settings.
 */
@Singleton
class QuickPillsService
    @Inject
    constructor(
        private val api: ApiClient,
        private val displayPreferencesService: DisplayPreferencesService,
        private val navDrawerService: NavDrawerService,
        private val latestNextUpService: LatestNextUpService,
        private val imageUrlService: ImageUrlService,
    ) {
        /** The user's pills, or empty if they have none or they can't be read */
        suspend fun configFor(userId: UUID): List<QuickPill> =
            try {
                displayPreferencesService
                    .getDisplayPreferences(userId, DISPLAY_PREFS_ID, CLIENT)
                    .customPrefs[PREF_KEY]
                    ?.let(::parseConfig)
                    ?.pills
                    .orEmpty()
            } catch (ex: Exception) {
                Timber.w(ex, "Could not read quick pills")
                emptyList()
            }

        /** Everything the row and header need, looked up now (what's on changes over time) */
        suspend fun resolve(
            userId: UUID,
            tvAccess: Boolean,
            pills: List<QuickPill>,
        ): List<ResolvedPill> {
            if (pills.isEmpty()) return emptyList()
            val channels =
                if (pills.any { it.type == QuickPill.Type.CHANNEL }) {
                    try {
                        api.liveTvApi
                            .getLiveTvChannels(
                                GetLiveTvChannelsRequest(
                                    userId = userId,
                                    addCurrentProgram = true,
                                    enableImages = true,
                                ),
                            ).content.items
                            .associateBy { it.channelNumber?.trim() }
                    } catch (ex: Exception) {
                        Timber.w(ex, "Could not list channels for quick pills")
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }
            val liveTv =
                if (pills.any { it.type == QuickPill.Type.GUIDE }) {
                    try {
                        navDrawerService
                            .getAllUserLibraries(userId, tvAccess)
                            .firstOrNull { it.collectionType == CollectionType.LIVETV }
                    } catch (ex: Exception) {
                        Timber.w(ex, "Could not find the live TV library for quick pills")
                        null
                    }
                } else {
                    null
                }
            return pills.mapNotNull { pill ->
                when (pill.type) {
                    QuickPill.Type.GUIDE -> {
                        val library = liveTv ?: return@mapNotNull null
                        ResolvedPill.Guide(pill.label ?: "Guide", library.itemId, library.type)
                    }

                    QuickPill.Type.CHANNEL -> {
                        val channel = channels[pill.number?.trim()] ?: return@mapNotNull null
                        ResolvedPill.Channel(
                            label = pill.label ?: channel.name ?: pill.number.orEmpty(),
                            number = channel.channelNumber.orEmpty(),
                            channelId = channel.id,
                            channelName = channel.name.orEmpty(),
                            logoUrl = imageUrlService.getItemImageUrl(channel.id, ImageType.PRIMARY, maxHeight = 96),
                            onNow = channel.currentProgram?.let(::program),
                        )
                    }

                    QuickPill.Type.RESUME -> {
                        val item = lastWatched(userId) ?: return@mapNotNull null
                        ResolvedPill.Resume(pill.label ?: "Resume", item)
                    }
                }
            }
        }

        private fun program(dto: BaseItemDto): OnNow {
            val imageType =
                listOf(ImageType.THUMB, ImageType.BACKDROP, ImageType.PRIMARY)
                    .firstOrNull { dto.imageTags?.get(it) != null || (it == ImageType.BACKDROP && !dto.backdropImageTags.isNullOrEmpty()) }
            return OnNow(
                title = dto.name.orEmpty(),
                episodeTitle = dto.episodeTitle,
                overview = dto.overview,
                start = dto.startDate,
                end = dto.endDate,
                imageUrl = imageType?.let { imageUrlService.getItemImageUrl(dto.id, it, maxWidth = 1280) },
            )
        }

        /** The newest thing in Continue Watching, else the first Next Up */
        private suspend fun lastWatched(userId: UUID): BaseItem? =
            try {
                latestNextUpService.getResume(userId, 1, includeEpisodes = true).firstOrNull()
                    ?: latestNextUpService
                        .getNextUp(
                            userId = userId,
                            limit = 1,
                            enableRewatching = false,
                            enableResumable = false,
                            maxDays = 365,
                        ).firstOrNull()
            } catch (ex: Exception) {
                Timber.w(ex, "Could not find something to resume")
                null
            }

        companion object {
            const val DISPLAY_PREFS_ID = "gooseflix"
            const val CLIENT = "gooseflix"
            const val PREF_KEY = "quickPills"

            private val json = Json { ignoreUnknownKeys = true }

            fun parseConfig(text: String): QuickPillsConfig? =
                runCatching { json.decodeFromString<QuickPillsConfig>(text) }
                    .onFailure { Timber.w(it, "Unreadable quick pills config") }
                    .getOrNull()
        }
    }

@Serializable
data class QuickPillsConfig(
    val version: Int = 1,
    val pills: List<QuickPill> = emptyList(),
)

@Serializable
data class QuickPill(
    val type: Type,
    /** Channel number, for [Type.CHANNEL] */
    val number: String? = null,
    /** What the pill says; defaults to the channel name, "Guide" or "Resume" */
    val label: String? = null,
) {
    @Serializable
    enum class Type {
        @SerialName("guide")
        GUIDE,

        @SerialName("channel")
        CHANNEL,

        @SerialName("resume")
        RESUME,
    }
}

/** What's airing on a channel now */
data class OnNow(
    val title: String,
    val episodeTitle: String?,
    val overview: String?,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val imageUrl: String?,
)

sealed interface ResolvedPill {
    val label: String

    data class Guide(
        override val label: String,
        val libraryId: UUID,
        val libraryType: org.jellyfin.sdk.model.api.BaseItemKind,
    ) : ResolvedPill

    data class Channel(
        override val label: String,
        val number: String,
        val channelId: UUID,
        val channelName: String,
        val logoUrl: String?,
        val onNow: OnNow?,
    ) : ResolvedPill

    data class Resume(
        override val label: String,
        val item: BaseItem,
    ) : ResolvedPill
}
