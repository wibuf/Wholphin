package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.games.model.GameLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fork-only: gives saved home screens the rows for libraries that arrived after they were saved.
 *
 * A home screen set up from scratch gets a "Recently added" row per library and per games
 * library, but saved home settings are a fixed list, so a library added later, like the seasonal
 * Halloween one or a games library, never shows up on them. Each library is offered once per
 * user: its row is added and remembered, so someone who then removes it in home settings doesn't
 * get it back.
 *
 * Libraries that already existed the first time this runs for a user are taken as decided, since
 * a row missing for one of those was most likely removed on purpose, unless the library is
 * recent ([NEW_LIBRARY_WINDOW_DAYS]).
 *
 * A row added here for a Jellyfin library is hidden, not deleted, while the library is missing
 * from the user's views: Scuffed's seasonal switch and the end of a season both take a library
 * out of the user's views, and switching it back on brings the row back.
 */
@Singleton
class NewHomeRows
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
        private val json = Json { ignoreUnknownKeys = true }

        fun record(userId: UUID): Record =
            prefs
                .getString(userId.toString(), null)
                ?.let { runCatching { json.decodeFromString<Record>(it) }.getOrNull() }
                ?: Record()

        fun save(
            userId: UUID,
            record: Record,
        ) {
            prefs.edit().putString(userId.toString(), json.encodeToString(Record.serializer(), record)).apply()
        }

        /** What this device has already done for one user */
        @Serializable
        data class Record(
            val baselined: Boolean = false,
            /** Libraries (Jellyfin ids and "game:" ids) that need no more offering */
            val offered: Set<String> = emptySet(),
            /** Jellyfin libraries whose row was added here, so it can hide while they are away */
            val added: Set<String> = emptySet(),
        )

        /** A Jellyfin library the user can see now, in the user's library order */
        data class LibraryView(
            val id: UUID,
            val createdAt: Instant?,
            val viewOptions: HomeRowViewOptions,
        )

        data class Plan(
            /** The settings rows to keep, with any new ones in place */
            val rows: List<HomeRowConfig>,
            /** Whether [rows] differs from what was saved */
            val changed: Boolean,
            /** The rows to show now: [rows] less added rows whose library is away */
            val visible: List<HomeRowConfig>,
            val record: Record,
        )

        companion object {
            private const val PREFS = "fork_new_home_rows"
            const val NEW_LIBRARY_WINDOW_DAYS = 30L

            private fun gameKey(id: String) = "game:$id"

            /**
             * Where a new library's row goes: after the row of the nearest library before it in
             * the user's library order (Jellyfin's OrderedViews, which is where Scuffed puts a
             * seasonal library), else before the nearest one after it, else at the end
             */
            private fun insertAt(
                rows: List<HomeRowConfig>,
                libraries: List<LibraryView>,
                id: UUID,
            ): Int {
                fun rowOf(libraryId: UUID) = rows.indexOfLast { (it as? HomeRowConfig.RecentlyAdded)?.parentId == libraryId }
                val position = libraries.indexOfFirst { it.id == id }
                libraries
                    .take(position)
                    .asReversed()
                    .firstNotNullOfOrNull { library -> rowOf(library.id).takeIf { it >= 0 } }
                    ?.let { return it + 1 }
                libraries
                    .drop(position + 1)
                    .firstNotNullOfOrNull { library -> rowOf(library.id).takeIf { it >= 0 } }
                    ?.let { return it }
                return rows.size
            }

            fun plan(
                rows: List<HomeRowConfig>,
                libraries: List<LibraryView>,
                gameLibraries: List<GameLibrary>,
                record: Record,
                now: Instant = Instant.now(),
            ): Plan {
                val offered = record.offered.toMutableSet()
                val added = record.added.toMutableSet()
                val result = rows.toMutableList()

                val withRows =
                    rows
                        .mapNotNull { (it as? HomeRowConfig.RecentlyAdded)?.parentId?.toString() }
                        .toSet()
                val newSince = now.minus(NEW_LIBRARY_WINDOW_DAYS, ChronoUnit.DAYS)
                for (library in libraries) {
                    val key = library.id.toString()
                    if (key in withRows) {
                        offered += key
                        continue
                    }
                    if (key in offered) continue
                    val recent = library.createdAt?.isAfter(newSince) == true
                    offered += key
                    if (!record.baselined && !recent) continue
                    result.add(insertAt(result, libraries, library.id), HomeRowConfig.RecentlyAdded(library.id, library.viewOptions))
                    added += key
                }

                val gameRows = rows.filterIsInstance<HomeRowConfig.Games>().map { it.libraryId }.toSet()
                for (library in gameLibraries) {
                    if (library.id.isBlank()) continue
                    val key = gameKey(library.id)
                    if (library.id in gameRows) {
                        offered += key
                        continue
                    }
                    if (key in offered) continue
                    offered += key
                    result.add(HomeRowConfig.Games(library.id, library.name))
                }

                val present = libraries.map { it.id.toString() }.toSet()
                val visible =
                    result.filterNot {
                        it is HomeRowConfig.RecentlyAdded &&
                            it.parentId.toString() in added &&
                            it.parentId.toString() !in present
                    }
                return Plan(
                    rows = result,
                    changed = result != rows,
                    visible = visible,
                    record = Record(baselined = true, offered = offered, added = added),
                )
            }
        }
    }
