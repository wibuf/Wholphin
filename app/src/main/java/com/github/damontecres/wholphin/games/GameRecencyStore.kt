package com.github.damontecres.wholphin.games

import com.github.damontecres.wholphin.games.model.GameSummary
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers when each game was first seen on this device
 *
 * Moonbase serves ROMs straight off the disk and does not track when a file arrived, so a
 * "recently added" row has nothing on the server to sort by. The next best thing is to notice
 * when a game first shows up in the listing here: the first load stamps everything at once (and
 * keeps the server's alphabetical order), and anything that appears later sorts to the front.
 */
@Singleton
class GameRecencyStore
    @Inject
    constructor(
        private val storage: GameStorage,
    ) {
        private val json = Json { ignoreUnknownKeys = true }
        private val lock = Any()
        private var seen: MutableMap<String, Long>? = null

        private fun file(): File = File(storage.systemDir().parentFile, "first_seen.json")

        private fun load(): MutableMap<String, Long> =
            seen ?: run {
                val loaded =
                    try {
                        file().takeIf { it.exists() }?.let { json.decodeFromString<Map<String, Long>>(it.readText()) }
                    } catch (ex: Exception) {
                        Timber.w(ex, "Could not read first-seen games")
                        null
                    }.orEmpty().toMutableMap()
                seen = loaded
                loaded
            }

        /**
         * The games ordered newest first, stamping any that have not been seen before
         *
         * Games that arrived in the same batch keep their relative order, which for the first
         * batch is the server's alphabetical one.
         */
        suspend fun newestFirst(games: List<GameSummary>): List<GameSummary> =
            withContext(WholphinDispatchers.IO) {
                synchronized(lock) {
                    val seen = load()
                    val now = System.currentTimeMillis()
                    var changed = false
                    games.forEach { game ->
                        if (game.id !in seen) {
                            seen[game.id] = now
                            changed = true
                        }
                    }
                    if (changed) {
                        try {
                            file().writeText(json.encodeToString(seen.toMap()))
                        } catch (ex: Exception) {
                            Timber.w(ex, "Could not save first-seen games")
                        }
                    }
                    games
                        .withIndex()
                        .sortedWith(
                            compareByDescending<IndexedValue<GameSummary>> { seen[it.value.id] ?: 0L }.thenBy { it.index },
                        ).map { it.value }
                }
            }
    }
