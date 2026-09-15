package com.github.damontecres.wholphin.games

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.github.damontecres.wholphin.games.model.GameSummary
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import com.github.damontecres.wholphin.services.hilt.IoCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Fetches and caches game artwork from the Moonbase plugin
 *
 * The plugin does not hand back art on demand: the thumb endpoint answers 503 or 504 with a
 * Retry-After while art is still being looked up or generated on the server, and it also caps
 * how many images one user can pull at once. An image loader that treats the first failure as
 * final therefore leaves cards blank until something recomposes them, which is what happened
 * on the Shield.
 *
 * So art is fetched here instead, a couple at a time, with every retry the server asks for, and
 * written to disk. Cards observe [artwork] and get a file as soon as it lands, whether they are
 * on screen or not, and every game in a library is queued as soon as the library is opened so
 * scrolling never waits on the network.
 */
@Singleton
class GameArtworkService
    @Inject
    constructor(
        private val games: MoonbaseGamesService,
        private val storage: GameStorage,
        @param:AuthOkHttpClient private val okHttpClient: OkHttpClient,
        @param:IoCoroutineScope private val scope: CoroutineScope,
    ) {
        private data class Key(
            val libraryId: String,
            val gameId: String,
            val kind: MoonbaseGamesService.ThumbKind,
        )

        private val _artwork = MutableStateFlow<Map<String, File>>(emptyMap())

        /** Art on disk, keyed by [artKey]. Only games that have been asked about appear. */
        val artwork: StateFlow<Map<String, File>> = _artwork

        private val lock = Any()
        private val queue = ArrayDeque<Key>()
        private val queued = HashSet<Key>()
        private val inFlight = HashSet<Key>()
        private val attempts = HashMap<Key, Int>()

        // When the server said there is no art, so it is not asked again for a while
        private val missingAt = HashMap<Key, Long>()
        // One token per queued item so both workers wake for a bulk prefetch; a worker drains
        // the queue past its own token anyway, so surplus tokens are harmless
        private val signal = Channel<Unit>(Channel.UNLIMITED)

        init {
            repeat(WORKERS) { scope.launch { worker() } }
        }

        /** The cached file for a game, or null when it is not on disk yet. Does not fetch. */
        fun cached(
            libraryId: String,
            gameId: String,
            kind: MoonbaseGamesService.ThumbKind = MoonbaseGamesService.ThumbKind.BOXART,
        ): File? {
            val key = Key(libraryId, gameId, kind)
            _artwork.value[artKey(libraryId, gameId, kind)]?.let { return it }
            val file = fileFor(key).takeIf { it.isFile && it.length() > 0 } ?: return null
            publish(key, file)
            return file
        }

        /** Make sure a game's art is on disk, fetching it ahead of anything queued in bulk */
        fun ensure(
            libraryId: String,
            gameId: String,
            kind: MoonbaseGamesService.ThumbKind = MoonbaseGamesService.ThumbKind.BOXART,
        ) {
            if (cached(libraryId, gameId, kind) != null) return
            enqueue(Key(libraryId, gameId, kind), front = true)
        }

        /** Queue every game's art, so browsing never waits on the network */
        fun prefetch(
            libraryId: String,
            games: List<GameSummary>,
        ) {
            games.forEach { game ->
                if (cached(libraryId, game.id) == null) {
                    enqueue(Key(libraryId, game.id, MoonbaseGamesService.ThumbKind.BOXART), front = false)
                }
            }
        }

        /** Queue the art for every game in a library */
        fun prefetchLibrary(libraryId: String) {
            scope.launch {
                try {
                    prefetch(libraryId, games.games(libraryId))
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not list games for artwork prefetch")
                }
            }
        }

        private fun enqueue(
            key: Key,
            front: Boolean,
        ) {
            synchronized(lock) {
                if (key in inFlight) return
                val missing = missingAt[key]
                if (missing != null && System.currentTimeMillis() - missing < MISSING_RECHECK.inWholeMilliseconds) return
                if (key in queued) {
                    if (front) {
                        queue.remove(key)
                        queue.addFirst(key)
                    }
                    return
                }
                queued.add(key)
                if (front) queue.addFirst(key) else queue.addLast(key)
            }
            signal.trySend(Unit)
        }

        private fun next(): Key? =
            synchronized(lock) {
                val key = queue.removeFirstOrNull() ?: return null
                queued.remove(key)
                inFlight.add(key)
                key
            }

        private suspend fun worker() {
            while (true) {
                signal.receive()
                while (true) {
                    val key = next() ?: break
                    val retryAfter =
                        try {
                            fetch(key)
                        } catch (ex: Exception) {
                            Timber.d(ex, "Artwork fetch failed for %s", key.gameId)
                            10.seconds
                        }
                    synchronized(lock) { inFlight.remove(key) }
                    if (retryAfter != null) scheduleRetry(key, retryAfter)
                }
            }
        }

        private fun scheduleRetry(
            key: Key,
            after: kotlin.time.Duration,
        ) {
            val attempt =
                synchronized(lock) {
                    val next = (attempts[key] ?: 0) + 1
                    attempts[key] = next
                    next
                }
            if (attempt > MAX_ATTEMPTS) {
                Timber.d("Giving up on artwork for %s after %d attempts", key.gameId, attempt)
                synchronized(lock) { attempts.remove(key) }
                return
            }
            scope.launch {
                delay(after.coerceIn(2.seconds, 30.seconds))
                enqueue(key, front = false)
            }
        }

        /**
         * Fetch one image. Returns how long to wait before trying again, or null when done, which
         * covers both success and a confirmed "no art for this game".
         */
        private fun fetch(key: Key): kotlin.time.Duration? {
            val url = games.thumbUrl(key.libraryId, key.gameId, key.kind)
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build()
            okHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val dest = fileFor(key)
                        dest.parentFile?.mkdirs()
                        val partial = File(dest.parentFile, dest.name + ".part")
                        response.body.byteStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                        if (partial.length() == 0L || !partial.renameTo(dest)) {
                            partial.delete()
                            throw IOException("Could not store artwork for ${key.gameId}")
                        }
                        publish(key, dest)
                        synchronized(lock) { attempts.remove(key) }
                        return null
                    }

                    404 -> {
                        // The plugin distinguishes "known missing" (cacheable) from "not looked
                        // yet" (no-store); the latter is worth another try
                        val confirmed = response.header("Cache-Control")?.contains("max-age") == true
                        if (confirmed) {
                            synchronized(lock) { missingAt[key] = System.currentTimeMillis() }
                            return null
                        }
                        return 5.seconds
                    }

                    503, 504, 429 -> {
                        val seconds = response.header("Retry-After")?.trim()?.toLongOrNull() ?: 5L
                        return seconds.seconds
                    }

                    else -> {
                        throw IOException("HTTP ${response.code} fetching artwork for ${key.gameId}")
                    }
                }
            }
        }

        private fun publish(
            key: Key,
            file: File,
        ) {
            _artwork.update { it + (artKey(key.libraryId, key.gameId, key.kind) to file) }
        }

        private fun fileFor(key: Key): File =
            File(storage.artDir(key.libraryId), "${GameStorage.gameDirectoryKey(key.gameId)}-${key.kind.key}")

        companion object {
            private const val WORKERS = 2
            private const val MAX_ATTEMPTS = 20
            private val MISSING_RECHECK = 10.minutes

            fun artKey(
                libraryId: String,
                gameId: String,
                kind: MoonbaseGamesService.ThumbKind = MoonbaseGamesService.ThumbKind.BOXART,
            ): String = "$libraryId/$gameId/${kind.key}"
        }
    }

/**
 * The art file for a game, requesting it if it is not cached yet, and updating when it lands
 */
@Composable
fun GameArtworkService.rememberArt(
    libraryId: String,
    gameId: String,
    kind: MoonbaseGamesService.ThumbKind = MoonbaseGamesService.ThumbKind.BOXART,
): State<File?> {
    val key = remember(libraryId, gameId, kind) { GameArtworkService.artKey(libraryId, gameId, kind) }
    LaunchedEffect(key) { ensure(libraryId, gameId, kind) }
    val flow = remember(key) { artwork.map { it[key] }.distinctUntilChanged() }
    return flow.collectAsState(initial = cached(libraryId, gameId, kind))
}
