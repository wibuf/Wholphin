package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrItemType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fork-only (GooseFlix #1636): the signed-in user's own requests for the home page's "My requests"
 * row, newest first, as Discover cards. The app asks the Seerr API, which Scuffed's proxy answers
 * from Media Adder (GooseFlix #1517), so it needs no server plugin. Nothing (an empty list) when
 * Seerr isn't connected, eg the `playreview` account.
 */
@Singleton
class MyRequests
    @Inject
    constructor(
        private val seerrService: SeerrService,
        private val seerrServerRepository: SeerrServerRepository,
    ) {
        /** One of the user's requests: its request id (to remove it) and its card */
        data class Entry(
            val requestId: Int,
            val item: DiscoverItem,
        )

        /**
         * Cached for [CACHE_MS] per Seerr user: the row costs a list call plus one details call per
         * request, so not every home load pays for it. Making, cancelling or removing a request
         * anywhere in the app clears it ([invalidate]).
         */
        suspend fun load(
            limit: Int,
            now: Long = android.os.SystemClock.elapsedRealtime(),
        ): List<Entry> {
            if (seerrServerRepository.active.firstOrNull() != true) return emptyList()
            // Only the user's own, even for a Seerr account that may see everyone's
            val seerrUserId = seerrServerRepository.currentUserId.firstOrNull() ?: return emptyList()
            cached
                ?.takeIf { it.seerrUserId == seerrUserId && now - it.at < CACHE_MS }
                ?.let { return it.entries }
            return fetch(seerrUserId, limit).also { cached = Cached(seerrUserId, now, it) }
        }

        /** Takes the request off the user's list (Media Adder keeps the title), and off the row now */
        suspend fun remove(requestId: Int) {
            seerrService.api.requestApi.requestRequestIdDelete(requestId.toString())
            _removed.update { it + requestId }
            invalidate()
        }

        private suspend fun fetch(
            seerrUserId: Int,
            limit: Int,
        ): List<Entry> {
            val requests =
                seerrService.api.requestApi
                    .requestGet(take = limit, requestedBy = seerrUserId)
                    .results
                    .orEmpty()
            // All the details at once: one round, not waves (each goes through Scuffed to Seerr)
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

            private class Cached(
                val seerrUserId: Int,
                val at: Long,
                val entries: List<Entry>,
            )

            @Volatile
            private var cached: Cached? = null

            private val _removed = MutableStateFlow<Set<Int>>(emptySet())

            /** Requests removed while the home page is up, hidden from the row and its header */
            val removed: StateFlow<Set<Int>> = _removed

            /** Fetch afresh next time, eg after the user makes or cancels a request */
            fun invalidate() {
                cached = null
            }
        }
    }
