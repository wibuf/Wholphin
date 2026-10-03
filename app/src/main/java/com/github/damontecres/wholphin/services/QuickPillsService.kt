package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest
import org.jellyfin.sdk.model.api.request.GetLiveTvProgramsRequest
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
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
 * writes it, with each channel's Jellyfin id beside its number; users without one see no row.
 *
 * Built for speed, since it sits on the home page: the last config and the ids it needed are kept
 * on the device, so [cached] draws the row with no network at all, and [refresh] then catches up
 * with one request for the config and one for what's on. Backdrops are stock art bundled in the app.
 *
 * Live games (#1427, #1422): while a Boston game is on, Scuffed's /api/gooseflix/live lists it and
 * its pill goes at the front of the same row, for everyone unless their config turns
 * [QuickPillsConfig.liveEvents] off; users with no pills of their own get a row of just that.
 * Fetched alongside the config, and again by [liveNow] every minute while home is up.
 */
@Singleton
class QuickPillsService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val displayPreferencesService: DisplayPreferencesService,
        private val navDrawerService: NavDrawerService,
        private val latestNextUpService: LatestNextUpService,
        private val imageUrlService: ImageUrlService,
        @param:StandardOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        private val prefs by lazy { context.getSharedPreferences("fork_quick_pills", Context.MODE_PRIVATE) }
        private val idMap = MapSerializer(String.serializer(), String.serializer())

        /** The row as it was last time, from the device alone; null if there's nothing kept */
        fun cached(
            userId: UUID,
            tvAccess: Boolean,
        ): List<ResolvedPill>? {
            val config = prefs.getString("config_$userId", null)?.let(::parseConfig)
            val live = livePills(config, tvAccess, cachedEvents(), LocalDateTime.now())
            if (config == null && live.isEmpty()) return null
            return live + build(userId, config?.pills.orEmpty(), onNow = emptyMap(), resume = null, resumeKnown = false)
        }

        /** The live game pills on their own, fresh from Scuffed, for the minute-by-minute check */
        suspend fun liveNow(
            userId: UUID,
            tvAccess: Boolean,
        ): List<ResolvedPill.Live> {
            val config = prefs.getString("config_$userId", null)?.let(::parseConfig)
            if (config?.liveEvents == false || !tvAccess) return emptyList()
            return livePills(config, tvAccess, fetchEvents() ?: cachedEvents(), LocalDateTime.now())
        }

        /** The user's current config, ids and what's on, from the server, kept for [cached] */
        suspend fun refresh(
            userId: UUID,
            tvAccess: Boolean,
        ): List<ResolvedPill> =
            coroutineScope {
                // Both at once: the live list comes from Scuffed, the rest from Jellyfin
                val events = async { if (tvAccess) fetchEvents() else null }
                val raw =
                    try {
                        displayPreferencesService
                            .getDisplayPreferences(userId, DISPLAY_PREFS_ID, CLIENT)
                            .customPrefs[PREF_KEY]
                    } catch (ex: Exception) {
                        Timber.w(ex, "Could not read quick pills")
                        events.cancel()
                        return@coroutineScope cached(userId, tvAccess).orEmpty()
                    }
                val config = raw?.let(::parseConfig)
                if (raw == null || config == null) {
                    prefs.edit().remove("config_$userId").apply()
                } else {
                    prefs.edit().putString("config_$userId", raw).apply()
                }
                val pills = refreshPills(userId, tvAccess, config?.pills.orEmpty())
                livePills(config, tvAccess, events.await() ?: cachedEvents(), LocalDateTime.now()) + pills
            }

        private suspend fun refreshPills(
            userId: UUID,
            tvAccess: Boolean,
            pills: List<QuickPill>,
        ): List<ResolvedPill> {
            if (pills.isEmpty()) return emptyList()
            fillMissingIds(userId, tvAccess, pills)

            val channelIds = pills.mapNotNull { channelIdFor(it) }
            val onNow =
                if (channelIds.isEmpty()) {
                    emptyMap()
                } else {
                    try {
                        api.liveTvApi
                            .getLiveTvPrograms(
                                GetLiveTvProgramsRequest(
                                    channelIds = channelIds,
                                    userId = userId,
                                    isAiring = true,
                                    enableImages = false,
                                    limit = channelIds.size * 2,
                                ),
                            ).content.items
                            .filter { it.channelId != null }
                            .associate { it.channelId!! to program(it) }
                    } catch (ex: Exception) {
                        Timber.w(ex, "Could not get what's on for quick pills")
                        emptyMap()
                    }
                }
            val resume =
                if (pills.any { it.type == QuickPill.Type.RESUME }) lastWatched(userId) else null
            return build(userId, pills, onNow, resume, resumeKnown = true)
        }

        private fun build(
            userId: UUID,
            pills: List<QuickPill>,
            onNow: Map<UUID, OnNow>,
            resume: BaseItem?,
            resumeKnown: Boolean,
        ): List<ResolvedPill> =
            pills.mapNotNull { pill ->
                when (pill.type) {
                    QuickPill.Type.GUIDE -> {
                        val (id, type) = prefs.getString("livetv_$userId", null)?.split('|') ?: return@mapNotNull null
                        val library = id.toUUIDOrNull() ?: return@mapNotNull null
                        val kind = runCatching { BaseItemKind.valueOf(type) }.getOrDefault(BaseItemKind.USER_VIEW)
                        ResolvedPill.Guide(pill.label ?: "Guide", library, kind, artUri(pill.art ?: "guide"))
                    }

                    QuickPill.Type.CHANNEL -> {
                        val id = channelIdFor(pill) ?: return@mapNotNull null
                        ResolvedPill.Channel(
                            label = pill.label ?: pill.number.orEmpty(),
                            number = pill.number.orEmpty(),
                            channelId = id,
                            channelName = pill.channelName ?: prefs.getString("name_$id", null).orEmpty(),
                            logoUrl = imageUrlService.getItemImageUrl(id, ImageType.PRIMARY, maxHeight = 96),
                            onNow = onNow[id],
                            artUri = artUri(pill.art ?: "tv"),
                        )
                    }

                    QuickPill.Type.RESUME -> {
                        // Kept before the lookup finishes, so the row doesn't jump; hidden only
                        // once it's known there's nothing to resume
                        if (resume == null && resumeKnown) return@mapNotNull null
                        ResolvedPill.Resume(pill.label ?: "Resume", resume)
                    }
                }
            }

        private fun livePills(
            config: QuickPillsConfig?,
            tvAccess: Boolean,
            events: List<LiveEvent>,
            now: LocalDateTime,
        ): List<ResolvedPill.Live> {
            if (config?.liveEvents == false || !tvAccess) return emptyList()
            return events.mapNotNull { event -> resolveLive(event, now) }
        }

        private fun resolveLive(
            event: LiveEvent,
            now: LocalDateTime,
        ): ResolvedPill.Live? {
            val window = liveWindow(event, now) ?: return null
            val channelId = event.channelId.toUUIDOrNull() ?: return null
            return ResolvedPill.Live(
                label = event.label,
                number = event.number,
                channelId = channelId,
                isLive = window,
                title = event.title,
                subtitle = event.subtitle,
                start = localTime(event.start)!!,
                end = localTime(event.end)!!,
                onNow = event.onNow,
                colors = LiveColors.of(event.colors),
                logoUrl = imageUrlService.getItemImageUrl(channelId, ImageType.PRIMARY, maxHeight = 96),
                artUri = artUri("sports"),
            )
        }

        /** What Scuffed says is on, kept on the device for [cached]; null when it can't be reached */
        private suspend fun fetchEvents(): List<LiveEvent>? {
            val url =
                SCUFFED_URL
                    ?.toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addPathSegments("api/gooseflix/live")
                    ?.build() ?: return null
            return try {
                withContext(Dispatchers.IO) {
                    withTimeout(LIVE_TIMEOUT_MS) {
                        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                            if (!response.isSuccessful) return@use null
                            val body = response.body.string()
                            artJson.decodeFromString<LiveEvents>(body).events.also {
                                prefs.edit().putString("live_events", body).apply()
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                Timber.d(ex, "Could not get live events")
                null
            }
        }

        private fun cachedEvents(): List<LiveEvent> =
            prefs
                .getString("live_events", null)
                ?.let { runCatching { artJson.decodeFromString<LiveEvents>(it).events }.getOrNull() }
                .orEmpty()

        private fun channelIdFor(pill: QuickPill): UUID? =
            if (pill.type != QuickPill.Type.CHANNEL) {
                null
            } else {
                pill.channelId?.toUUIDOrNull()
                    ?: pill.number?.let { prefs.getString("channel_${it.trim()}", null)?.toUUIDOrNull() }
            }

        /**
         * Looks up, once, what the config and the device don't know yet: the live TV library and
         * any channel Scuffed didn't give an id for. Normally nothing; the full channel list is only
         * fetched when a channel number is new.
         */
        private suspend fun fillMissingIds(
            userId: UUID,
            tvAccess: Boolean,
            pills: List<QuickPill>,
        ) {
            if (pills.any { it.type == QuickPill.Type.GUIDE } && prefs.getString("livetv_$userId", null) == null) {
                try {
                    navDrawerService
                        .getAllUserLibraries(userId, tvAccess)
                        .firstOrNull { it.collectionType == CollectionType.LIVETV }
                        ?.let { prefs.edit().putString("livetv_$userId", "${it.itemId}|${it.type.name}").apply() }
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not find the live TV library for quick pills")
                }
            }
            val unknown = pills.filter { it.type == QuickPill.Type.CHANNEL && channelIdFor(it) == null }
            val unnamed =
                pills
                    .filter { it.channelName == null }
                    .mapNotNull { channelIdFor(it) }
                    .filter { prefs.getString("name_$it", null) == null }
            if (unknown.isEmpty() && unnamed.isEmpty()) return
            try {
                val channels =
                    api.liveTvApi
                        .getLiveTvChannels(GetLiveTvChannelsRequest(userId = userId, enableImages = false))
                        .content.items
                val edit = prefs.edit()
                channels.forEach { channel ->
                    val number = channel.channelNumber?.trim() ?: return@forEach
                    if (unknown.any { it.number?.trim() == number }) edit.putString("channel_$number", channel.id.toString())
                    if (channel.id in unnamed || unknown.any { it.number?.trim() == number }) {
                        edit.putString("name_${channel.id}", channel.name.orEmpty())
                    }
                }
                edit.apply()
            } catch (ex: Exception) {
                Timber.w(ex, "Could not list channels for quick pills")
            }
        }

        private fun program(dto: BaseItemDto): OnNow {
            val (title, isNew) = cleanProgramTitle(dto.name.orEmpty())
            return OnNow(
                title = title,
                isNew = isNew,
                episodeTitle = dto.episodeTitle,
                overview = dto.overview,
                start = dto.startDate,
                end = dto.endDate,
                imageUrl = null,
                season = dto.parentIndexNumber,
                episode = dto.indexNumber,
                isMovie = dto.isMovie == true,
            )
        }

        /**
         * Art from TMDB, through Scuffed (which holds the TMDB key and caches the answers), for
         * what's on but not in the library, eg a Hallmark movie. GooseFlix builds only.
         */
        private suspend fun tmdbArt(onNow: OnNow): LibraryMatch? {
            val base = SCUFFED_URL ?: return null
            val type =
                when {
                    onNow.isMovie -> "movie"
                    onNow.season != null -> "tv"
                    else -> ""
                }
            val url =
                base
                    .toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addPathSegments("api/gooseflix/art")
                    ?.addQueryParameter("title", onNow.title)
                    ?.addQueryParameter("type", type)
                    ?.build() ?: return null
            return try {
                withContext(Dispatchers.IO) {
                    okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val art = artJson.decodeFromString<TmdbArt>(response.body.string())
                        LibraryMatch(found = true, backdropUrl = art.backdrop, logoUrl = art.logo)
                    }
                }
            } catch (ex: Exception) {
                Timber.d(ex, "No TMDB art for %s", onNow.title)
                null
            }
        }

        private val libraryCache = java.util.concurrent.ConcurrentHashMap<String, LibraryMatch>()

        /**
         * Art and episode details for what's on, from the same show or movie in the library: the
         * guide data has no images. Run after the row is drawn, never before; results are kept for
         * the rest of the session. Null when there's no match.
         */
        suspend fun libraryMatch(
            userId: UUID,
            onNow: OnNow,
        ): LibraryMatch? {
            val key = "${onNow.title.lowercase()}|${onNow.season}|${onNow.episode}"
            libraryCache[key]?.let { return it.takeIf { m -> m.found } }
            val match =
                try {
                    findInLibrary(userId, onNow)
                } catch (ex: Exception) {
                    Timber.d(ex, "No library match for %s", onNow.title)
                    null
                } ?: tmdbArt(onNow)
            libraryCache[key] = match ?: LibraryMatch.NONE
            return match
        }

        private suspend fun findInLibrary(
            userId: UUID,
            onNow: OnNow,
        ): LibraryMatch? {
            if (onNow.title.isBlank()) return null
            val item =
                api.itemsApi
                    .getItems(
                        userId = userId,
                        searchTerm = onNow.title,
                        includeItemTypes = listOf(BaseItemKind.SERIES, BaseItemKind.MOVIE),
                        recursive = true,
                        limit = 5,
                    ).content.items
                    .firstOrNull { it.name.equals(onNow.title, ignoreCase = true) }
                    ?: return null
            val backdrop =
                if (!item.backdropImageTags.isNullOrEmpty()) {
                    imageUrlService.getItemImageUrl(
                        item.id,
                        ImageType.BACKDROP,
                        maxWidth = 1280,
                    )
                } else {
                    null
                }
            val logo =
                if (item.imageTags?.get(ImageType.LOGO) !=
                    null
                ) {
                    imageUrlService.getItemImageUrl(item.id, ImageType.LOGO, maxHeight = 160)
                } else {
                    null
                }
            // The exact episode, when the guide says which one
            val episode =
                if (item.type == BaseItemKind.SERIES && onNow.season != null && onNow.episode != null) {
                    api.tvShowsApi
                        .getEpisodes(
                            seriesId = item.id,
                            userId = userId,
                            season = onNow.season,
                            fields = listOf(ItemFields.OVERVIEW),
                        ).content.items
                        .firstOrNull { it.indexNumber == onNow.episode }
                } else {
                    null
                }
            val still =
                episode?.takeIf { it.imageTags?.get(ImageType.PRIMARY) != null }?.let {
                    imageUrlService.getItemImageUrl(it.id, ImageType.PRIMARY, maxWidth = 1280)
                }
            return LibraryMatch(
                found = true,
                backdropUrl = backdrop ?: still,
                logoUrl = logo,
                episodeTitle = episode?.name,
                overview = episode?.overview,
            )
        }

        /** The newest thing in Continue Watching, else the first Next Up */
        suspend fun lastWatched(userId: UUID): BaseItem? =
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

        /** A bundled backdrop, as a URI the backdrop's image loader takes */
        private fun artUri(key: String): String {
            val res =
                when (key.lowercase()) {
                    "guide" -> R.drawable.pill_art_guide
                    "news" -> R.drawable.pill_art_news
                    "usa" -> R.drawable.pill_art_usa
                    "hallmark" -> R.drawable.pill_art_hallmark
                    "sports" -> R.drawable.pill_art_sports
                    else -> R.drawable.pill_art_tv
                }
            return "android.resource://${context.packageName}/$res"
        }

        companion object {
            const val DISPLAY_PREFS_ID = "gooseflix"
            const val CLIENT = "gooseflix"
            const val PREF_KEY = "quickPills"

            /**
             * The title without the guide's superscript marker, and whether it was "New":
             * "7 News Today  ᴺᵉʷ" is ("7 News Today", true)
             */
            fun cleanProgramTitle(raw: String): Pair<String, Boolean> {
                fun isMarker(c: Char) = c in 'ʰ'..'˿' || c in 'ᴀ'..'ᶿ'
                val marker = raw.filter(::isMarker)
                val title = raw.filterNot(::isMarker).trim()
                // ᴺᵉʷ: superscript capital N. Guides also mark "ᴸᶦᵛᵉ", which isn't "new"
                return title to marker.startsWith('ᴺ')
            }

            /** Don't hold the home page up for the live list */
            const val LIVE_TIMEOUT_MS = 1500L

            /** How early a game's pill may show; Scuffed decides, this only drops stale ones */
            private val PREGAME: java.time.Duration = java.time.Duration.ofMinutes(60)

            /**
             * Whether [event] is on now, from its own times (a kept list can be a while old):
             * true while the game is live, false in its pregame, null when it shouldn't show.
             */
            fun liveWindow(
                event: LiveEvent,
                now: LocalDateTime,
            ): Boolean? {
                val start = localTime(event.start) ?: return null
                val end = localTime(event.end) ?: return null
                return when {
                    !now.isBefore(end) -> null
                    !now.isBefore(start) -> true
                    !now.isBefore(start.minus(PREGAME)) -> false
                    else -> null
                }
            }

            /** Scuffed's UTC "2026-10-04T00:00:00Z" in local time, as the SDK gives programme times */
            fun localTime(utc: String): LocalDateTime? =
                runCatching { LocalDateTime.ofInstant(java.time.Instant.parse(utc), java.time.ZoneId.systemDefault()) }
                    .getOrNull()

            private val json = Json { ignoreUnknownKeys = true }
            private val artJson = Json { ignoreUnknownKeys = true }

            /** Scuffed, which also serves the app's updates; null outside GooseFlix builds */
            private val SCUFFED_URL: String? =
                BuildConfig.DEFAULT_UPDATE_URL.takeIf { BuildConfig.GOOSEFLIX }?.substringBefore("/update")

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
    /** Live game pills at the front of the row; written by Scuffed only when turned off */
    val liveEvents: Boolean = true,
)

/** Scuffed's /api/gooseflix/live */
@Serializable
data class LiveEvents(
    val events: List<LiveEvent> = emptyList(),
)

@Serializable
data class LiveEvent(
    /** The team, eg "Patriots" */
    val label: String,
    val number: String,
    val channelId: String,
    /** "live" or "pregame", when Scuffed looked; [QuickPillsService.liveWindow] decides now */
    val phase: String = "live",
    /** The matchup, eg "Patriots at Bills" */
    val title: String,
    /** League and network, eg "NFL Football · CBS (WBZ)" */
    val subtitle: String = "",
    /** UTC, eg "2026-10-04T17:00:00Z" */
    val start: String,
    val end: String,
    /** What's airing, eg the pregame show */
    val onNow: String = "",
    val colors: Map<String, String> = emptyMap(),
)

/** A team's pill colours, as ARGB */
data class LiveColors(
    val background: Long,
    val backgroundEnd: Long,
    val tile: Long,
) {
    companion object {
        val DEFAULT = LiveColors(0xFF002244, 0xFF0B3A75, 0xFFC60C30)

        fun of(colors: Map<String, String>): LiveColors =
            LiveColors(
                background = parse(colors["bg"]) ?: DEFAULT.background,
                backgroundEnd = parse(colors["bg2"]) ?: parse(colors["bg"]) ?: DEFAULT.backgroundEnd,
                tile = parse(colors["tile"]) ?: DEFAULT.tile,
            )

        /** "#C60C30" as 0xFFC60C30 */
        fun parse(hex: String?): Long? =
            hex
                ?.trim()
                ?.removePrefix("#")
                ?.takeIf { it.length == 6 }
                ?.toLongOrNull(16)
                ?.let { it or 0xFF000000 }
    }
}

@Serializable
data class QuickPill(
    val type: Type,
    /** Channel number, for [Type.CHANNEL] */
    val number: String? = null,
    /** What the pill says; defaults to the channel number, "Guide" or "Resume" */
    val label: String? = null,
    /** The channel's Jellyfin id, filled in by Scuffed so the app needn't look it up */
    val channelId: String? = null,
    /** The channel's full name (eg "WHDH - News 7 Boston"), also from Scuffed */
    val channelName: String? = null,
    /** Which bundled backdrop to use: guide, news, usa, hallmark, sports, tv */
    val art: String? = null,
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
    /** The guide marks it as a new episode */
    val isNew: Boolean = false,
    val episodeTitle: String?,
    val overview: String?,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val imageUrl: String?,
    val season: Int? = null,
    val episode: Int? = null,
    /** The show's logo, when it's in the library */
    val logoUrl: String? = null,
    val isMovie: Boolean = false,
)

@Serializable
private data class TmdbArt(
    val backdrop: String? = null,
    val logo: String? = null,
)

/** What the library knows about a programme */
data class LibraryMatch(
    val found: Boolean,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val episodeTitle: String? = null,
    val overview: String? = null,
) {
    companion object {
        val NONE = LibraryMatch(found = false)
    }
}

sealed interface ResolvedPill {
    val label: String

    /** The same pill across reloads, for the row's keys and the header */
    val key: String

    /** A game on one of the team channels, at the front of the row while it's on */
    data class Live(
        override val label: String,
        val number: String,
        val channelId: UUID,
        /** False during the pregame */
        val isLive: Boolean,
        val title: String,
        val subtitle: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val onNow: String,
        val colors: LiveColors,
        val logoUrl: String?,
        val artUri: String,
    ) : ResolvedPill {
        override val key get() = "live_$channelId"
    }

    data class Guide(
        override val label: String,
        val libraryId: UUID,
        val libraryType: BaseItemKind,
        val artUri: String,
    ) : ResolvedPill {
        override val key get() = "guide"
    }

    data class Channel(
        override val label: String,
        val number: String,
        val channelId: UUID,
        val channelName: String,
        val logoUrl: String?,
        val onNow: OnNow?,
        val artUri: String? = null,
    ) : ResolvedPill {
        override val key get() = "channel_$channelId"
    }

    data class Resume(
        override val label: String,
        /** Null until looked up; pressing it then looks it up and plays */
        val item: BaseItem?,
    ) : ResolvedPill {
        override val key get() = "resume"
    }
}
