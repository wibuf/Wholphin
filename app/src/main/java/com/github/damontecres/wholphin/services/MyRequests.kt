package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrItemType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fork-only (GooseFlix #1636): the signed-in user's own Seerr requests for the home page's "My
 * requests" row, newest first, as Discover cards. Goes through the same Seerr connection as the
 * Discover page (the Scuffed proxy, GooseFlix #1516), so it needs no server plugin. Nothing (an empty
 * list) when Seerr isn't connected, eg the `playreview` account.
 */
@Singleton
class MyRequests
    @Inject
    constructor(
        private val seerrService: SeerrService,
        private val seerrServerRepository: SeerrServerRepository,
    ) {
        private class Cached(
            val seerrUserId: Int,
            val at: Long,
            val items: List<DiscoverItem>,
        )

        @Volatile
        private var cached: Cached? = null

        /**
         * Cached for [CACHE_MS] per Seerr user: the row costs a list call plus one details call per
         * request (through Scuffed to Seerr), so only the first home load in a while pays for it.
         */
        suspend fun load(
            limit: Int,
            now: Long = android.os.SystemClock.elapsedRealtime(),
        ): List<DiscoverItem> {
            if (seerrServerRepository.active.firstOrNull() != true) return emptyList()
            // Only the user's own, even for a Seerr account that may see everyone's
            val seerrUserId = seerrServerRepository.currentUserId.firstOrNull() ?: return emptyList()
            cached
                ?.takeIf { it.seerrUserId == seerrUserId && now - it.at < CACHE_MS }
                ?.let { return it.items }
            return fetch(seerrUserId, limit).also { cached = Cached(seerrUserId, now, it) }
        }

        private suspend fun fetch(
            seerrUserId: Int,
            limit: Int,
        ): List<DiscoverItem> {
            val requests =
                seerrService.api.requestApi
                    .requestGet(take = limit, requestedBy = seerrUserId)
                    .results
                    .orEmpty()
            val semaphore = Semaphore(5)
            return coroutineScope {
                requests
                    .mapNotNull { request ->
                        val tmdbId = request.media?.tmdbId ?: return@mapNotNull null
                        async {
                            semaphore.withPermit {
                                try {
                                    when (SeerrItemType.fromString(request.type)) {
                                        SeerrItemType.MOVIE -> seerrService.createDiscoverItem(seerrService.api.moviesApi.movieMovieIdGet(movieId = tmdbId))
                                        SeerrItemType.TV -> seerrService.createDiscoverItem(seerrService.api.tvApi.tvTvIdGet(tvId = tmdbId))
                                        else -> null
                                    }
                                } catch (ex: kotlinx.coroutines.CancellationException) {
                                    throw ex
                                } catch (ex: Exception) {
                                    Timber.w(ex, "My requests: could not load %s", tmdbId)
                                    null
                                }
                            }
                        }
                    }.awaitAll()
                    .filterNotNull()
            }
        }

        companion object {
            const val CACHE_MS = 10 * 60 * 1000L
        }
    }
