package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.MdbListRatingsResponse
import com.github.damontecres.wholphin.data.model.MdbListSource
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.model.UUID
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads cached ratings from the MDBList Ratings server plugin
 *
 * Jellyfin only carries two ratings of its own, so the extra sources (IMDb, Rotten Tomatoes
 * critics & audience, TMDB) come from a plugin that already runs server side and caches them. That
 * keeps API keys and rate limits on the server rather than putting them in a TV app.
 *
 * Every failure here is expected rather than exceptional: most servers do not have the plugin
 * installed, in which case the endpoint 404s and there is simply nothing extra to show. Nothing is
 * surfaced to the user and the normal Jellyfin ratings render as before.
 */
@Singleton
class MdbListRatingsService
    @Inject
    constructor(
        private val api: ApiClient,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        // One request per opened item, so revisiting a page does not re-hit the server. Bounded
        // because a long browsing session would otherwise hold every item ever opened.
        private val cache =
            object : LinkedHashMap<UUID, MdbListRatingsResponse?>(0, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<UUID, MdbListRatingsResponse?>) = size > CACHE_SIZE
            }

        /**
         * Cached ratings for an item, or null when the plugin is absent, has nothing for it, or
         * the request fails for any reason
         */
        suspend fun getRatings(itemId: UUID): MdbListRatingsResponse? {
            synchronized(cache) {
                if (cache.containsKey(itemId)) {
                    return cache[itemId]
                }
            }
            val result = fetch(itemId)
            synchronized(cache) { cache[itemId] = result }
            return result
        }

        private suspend fun fetch(itemId: UUID): MdbListRatingsResponse? =
            try {
                val response =
                    api.request(
                        method = HttpMethod.GET,
                        pathTemplate = PATH,
                        queryParameters = mapOf("itemId" to itemId.toString()),
                    )
                if (response.status != 200) {
                    // 404 is the normal answer when the plugin is not installed
                    Timber.d("MdbList ratings for %s: HTTP %d", itemId, response.status)
                    null
                } else {
                    json
                        .decodeFromString<MdbListRatingsResponse>(response.body.decodeToString())
                        .takeIf { it.hasCache && it.ratings.isNotEmpty() }
                }
            } catch (ex: Exception) {
                Timber.d(ex, "Could not read MdbList ratings for %s", itemId)
                null
            }

        /**
         * URL for a rating source's icon, served by the plugin itself
         *
         * Using the plugin's own artwork rather than bundling any keeps the brand marks where they
         * came from, and gives Rotten Tomatoes its separate fresh/rotten and positive/negative
         * audience variants for free.
         *
         * No credentials go in the URL: the OkHttp client behind Coil already adds the
         * Authorization header for this server, so the icon loads like any other server image.
         */
        fun iconUrl(
            source: MdbListSource,
            value: Double,
        ): String {
            val file =
                when (source) {
                    MdbListSource.IMDB -> {
                        "IMDb.png"
                    }

                    MdbListSource.TMDB -> {
                        "TMDB.png"
                    }

                    MdbListSource.TOMATOES -> {
                        if (value >= FRESH_THRESHOLD) {
                            "Rotten_Tomatoes.png"
                        } else {
                            "Rotten_Tomatoes_rotten.png"
                        }
                    }

                    MdbListSource.POPCORN -> {
                        if (value >= FRESH_THRESHOLD) {
                            "Rotten_Tomatoes_positive_audience.png"
                        } else {
                            "Rotten_Tomatoes_negative_audience.png"
                        }
                    }
                }
            return api.createUrl(
                pathTemplate = "/Plugins/MdbListRatings/Assets/{file}",
                pathParameters = mapOf("file" to file),
            )
        }

        /** Forget everything, eg on sign out or when switching servers */
        fun clear() {
            synchronized(cache) { cache.clear() }
        }

        companion object {
            private const val PATH = "/Plugins/MdbListRatings/CachedByItemId"
            private const val CACHE_SIZE = 200

            /** Rotten Tomatoes calls 60% and above fresh, for both critics and audience */
            const val FRESH_THRESHOLD = 60.0
        }
    }
