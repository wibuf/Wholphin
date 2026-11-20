package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.preferences.SkipSegmentBehavior
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.model.api.MediaSegmentType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fork-only (GooseFlix): per-show skip choices, set from the player's Settings → Skipping.
 *
 * A show (or a movie) can override the global Skip settings per segment type: auto skip, ask, or
 * don't skip. They're kept in the user's Jellyfin display preferences (the "default" record the
 * home layout also lives in, one key per show), so they follow the user to every TV.
 */
@Singleton
class SkipOverridesService
    @Inject
    constructor(
        private val displayPreferencesService: DisplayPreferencesService,
        private val serverRepository: ServerRepository,
    ) {
        private val mutex = Mutex()
        private val cache = mutableMapOf<String, Map<MediaSegmentType, SkipSegmentBehavior>>()

        /** A show's (or movie's) overrides; empty when there are none, or they can't be read */
        suspend fun load(key: String): Map<MediaSegmentType, SkipSegmentBehavior> {
            mutex.withLock { cache[key] }?.let { return it }
            val userId = serverRepository.currentUser?.id ?: return emptyMap()
            val overrides =
                try {
                    parse(displayPreferencesService.getDisplayPreferences(userId).customPrefs[PREFIX + key])
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not read skip choices for %s", key)
                    return emptyMap()
                }
            mutex.withLock { cache[key] = overrides }
            return overrides
        }

        /** Saves a show's overrides (none removes its entry) */
        suspend fun save(
            key: String,
            overrides: Map<MediaSegmentType, SkipSegmentBehavior>,
        ) {
            mutex.withLock { cache[key] = overrides }
            val userId = serverRepository.currentUser?.id ?: return
            displayPreferencesService.updateDisplayPreferences(userId) {
                val value = format(overrides)
                if (value == null) remove(PREFIX + key) else put(PREFIX + key, value)
            }
        }

        companion object {
            const val PREFIX = "gooseflix_skip_"

            /** "INTRO=AUTO_SKIP,OUTRO=IGNORE" */
            fun parse(value: String?): Map<MediaSegmentType, SkipSegmentBehavior> =
                value
                    .orEmpty()
                    .split(',')
                    .mapNotNull { pair ->
                        val (type, behavior) = pair.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                        val segment = MediaSegmentType.entries.firstOrNull { it.name == type.trim() } ?: return@mapNotNull null
                        val choice =
                            SkipSegmentBehavior.entries
                                .firstOrNull { it.name == behavior.trim() && it != SkipSegmentBehavior.UNRECOGNIZED }
                                ?: return@mapNotNull null
                        segment to choice
                    }.toMap()

            fun format(overrides: Map<MediaSegmentType, SkipSegmentBehavior>): String? =
                overrides.entries
                    .sortedBy { it.key.name }
                    .joinToString(",") { "${it.key.name}=${it.value.name}" }
                    .takeIf { it.isNotEmpty() }
        }
    }
