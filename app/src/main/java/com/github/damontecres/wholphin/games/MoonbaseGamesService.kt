package com.github.damontecres.wholphin.games

import com.github.damontecres.wholphin.games.model.GameDetail
import com.github.damontecres.wholphin.games.model.GameLibrary
import com.github.damontecres.wholphin.games.model.GameSummary
import com.github.damontecres.wholphin.games.model.GameSystem
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to the Moonbase server plugin's retro-games endpoints under /Moonfin/Games
 *
 * Moonbase indexes ROM folders on the server, serves the files, and stores save states per user,
 * so this client is the whole server side of game playback. Small JSON responses go through the
 * SDK's [ApiClient]; ROMs and saves stream through OkHttp so a large image never has to fit in
 * memory.
 */
@Singleton
class MoonbaseGamesService
    @Inject
    constructor(
        private val api: ApiClient,
        @param:AuthOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        // Whether the current server has the plugin at all, so the nav drawer can ask cheaply
        @Volatile
        private var librariesCache: List<GameLibrary>? = null

        /**
         * The game libraries the server exposes, or an empty list when the plugin is absent, has
         * games disabled, or the request fails
         */
        suspend fun libraries(refresh: Boolean = false): List<GameLibrary> {
            if (!refresh) librariesCache?.let { return it }
            val result =
                try {
                    getJson<List<GameLibrary>>("/Moonfin/Games/Libraries") ?: emptyList()
                } catch (ex: Exception) {
                    Timber.d(ex, "Could not list game libraries")
                    emptyList()
                }
            librariesCache = result
            return result
        }

        fun clear() {
            librariesCache = null
        }

        suspend fun systems(libraryId: String): List<GameSystem> =
            getJson<List<GameSystem>>("/Moonfin/Games/{libraryId}/Systems", mapOf("libraryId" to libraryId))
                ?: emptyList()

        suspend fun games(
            libraryId: String,
            systemId: String? = null,
        ): List<GameSummary> =
            getJson<List<GameSummary>>(
                "/Moonfin/Games/{libraryId}/Games",
                mapOf("libraryId" to libraryId),
                if (systemId.isNullOrBlank()) emptyMap() else mapOf("system" to systemId),
            ) ?: emptyList()

        suspend fun game(
            libraryId: String,
            gameId: String,
        ): GameDetail? =
            getJson<GameDetail>(
                "/Moonfin/Games/{libraryId}/Games/{gameId}",
                mapOf("libraryId" to libraryId, "gameId" to gameId),
            )

        /**
         * Artwork for a game
         *
         * No credentials go in the URL: the OkHttp client behind Coil already adds the
         * Authorization header for this server.
         */
        fun thumbUrl(
            libraryId: String,
            gameId: String,
            kind: ThumbKind = ThumbKind.BOXART,
        ): String =
            api.createUrl(
                pathTemplate = "/Moonfin/Games/{libraryId}/Thumb/{gameId}",
                pathParameters = mapOf("libraryId" to libraryId, "gameId" to gameId),
                queryParameters = mapOf("type" to kind.key),
            )

        /** Stream the ROM to [dest], reporting progress as 0..1 when the server sends a length */
        suspend fun downloadRom(
            libraryId: String,
            gameId: String,
            dest: File,
            onProgress: (Float) -> Unit = {},
        ) = download(
            api.createUrl(
                "/Moonfin/Games/{libraryId}/Rom/{gameId}",
                mapOf("libraryId" to libraryId, "gameId" to gameId),
            ),
            dest,
            onProgress,
        )

        suspend fun downloadBios(
            libraryId: String,
            biosId: String,
            dest: File,
        ) = download(
            api.createUrl(
                "/Moonfin/Games/{libraryId}/Bios/{biosId}",
                mapOf("libraryId" to libraryId, "biosId" to biosId),
            ),
            dest,
        )

        /**
         * A stored save, or null when there is none
         *
         * @param kind "state" for a save state, "settings" for emulator options
         */
        suspend fun getSave(
            key: String,
            kind: String = KIND_STATE,
        ): ByteArray? =
            withContext(WholphinDispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url(saveUrl(key, kind))
                        .get()
                        .build()
                okHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> null
                        !response.isSuccessful -> throw IOException("HTTP ${response.code} reading save $key")
                        else -> response.body.bytes().takeIf { it.isNotEmpty() }
                    }
                }
            }

        suspend fun putSave(
            key: String,
            data: ByteArray,
            kind: String = KIND_STATE,
        ) = withContext(WholphinDispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(saveUrl(key, kind))
                    .put(data.toRequestBody(OCTET_STREAM))
                    .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} writing save $key")
            }
        }

        private fun saveUrl(
            key: String,
            kind: String,
        ) = api.createUrl(
            pathTemplate = "/Moonfin/Games/Saves/{key}",
            pathParameters = mapOf("key" to key),
            queryParameters = mapOf("kind" to kind),
        )

        private suspend inline fun <reified T> getJson(
            pathTemplate: String,
            pathParameters: Map<String, Any?> = emptyMap(),
            queryParameters: Map<String, Any?> = emptyMap(),
        ): T? {
            val response =
                api.request(
                    method = HttpMethod.GET,
                    pathTemplate = pathTemplate,
                    pathParameters = pathParameters,
                    queryParameters = queryParameters,
                )
            if (response.status == 404) return null
            if (response.status !in 200..299) throw IOException("HTTP ${response.status} for $pathTemplate")
            return json.decodeFromString<T>(response.body.decodeToString())
        }

        private suspend fun download(
            url: String,
            dest: File,
            onProgress: (Float) -> Unit = {},
        ) = withContext(WholphinDispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build()
            // Land in a scratch file so a torn download never looks like a finished one
            val partial = File(dest.parentFile, dest.name + ".part")
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading $url")
                    val body = response.body
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var received = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                received += read
                                if (total > 0) onProgress(received.toFloat() / total)
                            }
                        }
                    }
                }
                if (!partial.renameTo(dest)) throw IOException("Could not move ${partial.name} into place")
            } finally {
                partial.delete()
            }
        }

        enum class ThumbKind(
            val key: String,
        ) {
            BOXART("boxart"),
            SNAP("snap"),
            TITLE("title"),
        }

        companion object {
            const val KIND_STATE = "state"
            const val KIND_SETTINGS = "settings"
            private val OCTET_STREAM = "application/octet-stream".toMediaType()
        }
    }
