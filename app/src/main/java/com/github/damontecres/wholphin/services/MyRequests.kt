package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fork-only (GooseFlix #1636): the signed-in user's own requests for the home page's "My requests"
 * row, newest first, as Discover cards.
 *
 * Asks Scuffed's `GET /api/gooseflix/my-requests` (one call, cards ready to draw: title, poster,
 * Media Adder's status and download progress). If that isn't there (an older Scuffed), it falls back
 * to the Seerr API, which Scuffed's proxy answers from Media Adder (GooseFlix #1517), at the cost of
 * one details call per request. Nothing (an empty list) for an account without requests, or blocked
 * from them (eg `playreview`).
 */
@Singleton
class MyRequests
    @Inject
    constructor(
        private val seerrService: SeerrService,
        private val seerrServerRepository: SeerrServerRepository,
        private val serverRepository: ServerRepository,
        @param:StandardOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        /** One of the user's requests: its id (to remove it), its card, and Media Adder's status */
        data class Entry(
            val requestId: Int,
            val item: DiscoverItem,
            /** Media Adder's wording, eg "Downloading", "Finding a copy…"; null from the Seerr fallback */
            val label: String? = null,
            /** Download progress, 0–100, while downloading */
            val progress: Double? = null,
        )

        /**
         * Cached for [CACHE_MS] per user. Making, cancelling or removing a request anywhere in the
         * app clears it ([invalidate]), and the next fetch then skips Scuffed's own short cache too.
         */
        suspend fun load(
            limit: Int,
            now: Long = android.os.SystemClock.elapsedRealtime(),
        ): List<Entry> {
            val user = serverRepository.currentUser ?: return emptyList()
            val key = user.id.toString()
            cached
                ?.takeIf { it.key == key && now - it.at < CACHE_MS }
                ?.let { return it.entries }
            val fresh = freshNext
            freshNext = false
            val entries = fromScuffed(user.accessToken, limit, fresh) ?: fromSeerr(limit)
            cached = Cached(key, now, entries)
            return entries
        }

        /** Takes the request off the user's list (Media Adder keeps the title), and off the row now */
        suspend fun remove(requestId: Int) {
            seerrService.api.requestApi.requestRequestIdDelete(requestId.toString())
            _removed.update { it + requestId }
            invalidate()
        }

        /**
         * The card with its backdrop, overview, year and logo, which Scuffed's cards leave out: one
         * Discover details call (through the Scuffed Seerr proxy) the first time a card is focused,
         * then kept in [details]. Null if the details can't be had; the card itself is unchanged.
         */
        suspend fun details(item: DiscoverItem): DiscoverItem? {
            if (item.backDropUrl != null) return item
            val key = detailsKey(item)
            _details.value[key]?.let { return it }
            val full =
                try {
                    when (item.type) {
                        SeerrItemType.MOVIE -> seerrService.createDiscoverItem(seerrService.api.moviesApi.movieMovieIdGet(movieId = item.id))
                        SeerrItemType.TV -> seerrService.createDiscoverItem(seerrService.api.tvApi.tvTvIdGet(tvId = item.id))
                        else -> null
                    }
                } catch (ex: kotlinx.coroutines.CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "My requests: no details for %s", key)
                    null
                } ?: return null
            // Keep the card's own title, poster and status; take what it lacks
            val merged =
                item.copy(
                    overview = item.overview ?: full.overview,
                    releaseDate = item.releaseDate ?: full.releaseDate,
                    backDropUrl = full.backDropUrl,
                    logoUrl = item.logoUrl ?: full.logoUrl,
                    posterUrl = item.posterUrl ?: full.posterUrl,
                )
            _details.update { it + (key to merged) }
            return merged
        }

        @Serializable
        private data class Card(
            val id: Int,
            val tmdbId: Int? = null,
            val mediaType: String? = null,
            val title: String = "",
            val posterUrl: String? = null,
            val state: String = "unknown",
            val label: String = "",
            val progress: Double? = null,
        )

        @Serializable
        private data class Cards(
            val results: List<Card> = emptyList(),
        )

        /** The cards from Scuffed, or null to fall back to Seerr (no such endpoint, or it failed) */
        private suspend fun fromScuffed(
            token: String?,
            limit: Int,
            fresh: Boolean,
        ): List<Entry>? {
            val url =
                BuildConfig.SEERR_PROXY_URL
                    .takeIf { it.endsWith("/seerr") && !token.isNullOrBlank() }
                    ?.removeSuffix("/seerr")
                    ?.let { "$it/my-requests".toHttpUrlOrNull() }
                    ?.newBuilder()
                    ?.addQueryParameter("limit", limit.toString())
                    ?.apply { if (fresh) addQueryParameter("fresh", "1") }
                    ?.build()
                    ?: return null
            return withContext(WholphinDispatchers.IO) {
                try {
                    okHttpClient
                        .newCall(Request.Builder().url(url).header("X-Api-Key", token!!).build())
                        .execute()
                        .use { response ->
                            when {
                                response.isSuccessful -> {
                                    json.decodeFromString<Cards>(response.body.string()).results.mapNotNull(::entry)
                                }
                                // Signed in, but not allowed requests: nothing to show
                                response.code == 401 || response.code == 403 -> {
                                    emptyList()
                                }
                                else -> {
                                    Timber.i("My requests: Scuffed answered %s, using Seerr", response.code)
                                    null
                                }
                            }
                        }
                } catch (ex: kotlinx.coroutines.CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "My requests: Scuffed failed, using Seerr")
                    null
                }
            }
        }

        private fun entry(card: Card): Entry? {
            val tmdbId = card.tmdbId ?: return null
            val type =
                when (card.mediaType) {
                    "movie" -> SeerrItemType.MOVIE
                    "tv" -> SeerrItemType.TV
                    else -> return null
                }
            val item =
                DiscoverItem(
                    id = tmdbId,
                    type = type,
                    title = card.title,
                    subtitle = null,
                    overview = null,
                    availability = availabilityFor(card.state),
                    releaseDate = null,
                    posterUrl = card.posterUrl,
                    backDropUrl = null,
                    logoUrl = null,
                    jellyfinItemId = null,
                )
            return Entry(card.id, item, card.label.takeIf { it.isNotBlank() }, card.progress)
        }

        /** The Seerr route: the request list, then each title's details (one call each) */
        private suspend fun fromSeerr(limit: Int): List<Entry> {
            if (seerrServerRepository.active.firstOrNull() != true) return emptyList()
            // Only the user's own, even for a Seerr account that may see everyone's
            val seerrUserId = seerrServerRepository.currentUserId.firstOrNull() ?: return emptyList()
            val requests =
                seerrService.api.requestApi
                    .requestGet(take = limit, requestedBy = seerrUserId)
                    .results
                    .orEmpty()
            return coroutineScope {
                requests
                    .mapNotNull { request ->
                        val tmdbId = request.media?.tmdbId ?: return@mapNotNull null
                        async {
                            try {
                                val item =
                                    when (SeerrItemType.fromString(request.type)) {
                                        SeerrItemType.MOVIE -> seerrService.createDiscoverItem(seerrService.api.moviesApi.movieMovieIdGet(movieId = tmdbId))
                                        SeerrItemType.TV -> seerrService.createDiscoverItem(seerrService.api.tvApi.tvTvIdGet(tvId = tmdbId))
                                        else -> null
                                    }
                                item?.let { Entry(request.id, it) }
                            } catch (ex: kotlinx.coroutines.CancellationException) {
                                throw ex
                            } catch (ex: Exception) {
                                Timber.w(ex, "My requests: could not load %s", tmdbId)
                                null
                            }
                        }
                    }.awaitAll()
                    .filterNotNull()
            }
        }

        companion object {
            const val CACHE_MS = 2 * 60 * 1000L

            private val json = Json { ignoreUnknownKeys = true }

            /** Media Adder's request state as the Discover card's availability badge */
            internal fun availabilityFor(state: String): SeerrAvailability =
                when (state) {
                    "available" -> SeerrAvailability.AVAILABLE
                    "downloading", "redownloading", "processing", "queued" -> SeerrAvailability.PROCESSING
                    else -> SeerrAvailability.PENDING
                }

            private class Cached(
                val key: String,
                val at: Long,
                val entries: List<Entry>,
            )

            @Volatile
            private var cached: Cached? = null

            @Volatile
            private var freshNext = false

            private val _removed = MutableStateFlow<Set<Int>>(emptySet())

            /** Requests removed while the home page is up, hidden from the row and its header */
            val removed: StateFlow<Set<Int>> = _removed

            private val _details = MutableStateFlow<Map<String, DiscoverItem>>(emptyMap())

            /** Cards filled in with their details ([details]), by [detailsKey] */
            val details: StateFlow<Map<String, DiscoverItem>> = _details

            fun detailsKey(item: DiscoverItem) = "${item.type}:${item.id}"

            /** Fetch afresh next time, eg after the user makes or cancels a request */
            fun invalidate() {
                cached = null
                freshNext = true
            }
        }
    }
