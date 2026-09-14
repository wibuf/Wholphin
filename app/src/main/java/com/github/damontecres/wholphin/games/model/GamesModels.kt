package com.github.damontecres.wholphin.games.model

import kotlinx.serialization.Serializable

// Models for the Moonbase plugin's retro-games API under /Moonfin/Games. The plugin emits
// camelCase JSON and adds fields over time, so every decoder ignores unknown keys and every
// field has a default.

@Serializable
data class GameLibrary(
    val id: String = "",
    val name: String = "Games",
)

@Serializable
data class GameSystem(
    val id: String = "",
    val name: String = "",
    /** The server's core name for the system, eg "snes" or "arcade"; see [com.github.damontecres.wholphin.games.GameCores] */
    val core: String = "",
    val gameCount: Int = 0,
)

@Serializable
data class GameSummary(
    val id: String = "",
    val title: String = "",
    val system: String = "",
    val core: String = "",
    val fileName: String = "",
)

@Serializable
data class GameBios(
    val id: String = "",
    val fileName: String = "",
    val sizeBytes: Long = 0,
)

@Serializable
data class GameDetail(
    val id: String = "",
    val title: String = "",
    val system: String = "",
    val core: String = "",
    /** Server-selected core before any per-user override, present for arcade games */
    val recommendedCore: String? = null,
    /** Compatible arcade cores the user may pick; empty for other systems */
    val availableCores: List<String> = emptyList(),
    val userCoreOverride: String? = null,
    val coreCompatibilityReason: String? = null,
    val fileName: String = "",
    val sizeBytes: Long = 0,
    val bios: List<GameBios> = emptyList(),
    // Optional libretro-database metadata; coverage is uneven
    val genre: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val franchise: String? = null,
    val region: String? = null,
    val year: Int? = null,
    val players: Int? = null,
    val overview: String? = null,
    val rating: Double? = null,
)

/**
 * A download target must be a single path segment
 *
 * The server names these files, so a traversal or absolute path here means the server is
 * hostile or compromised: reject rather than sanitize, matching the native host's own guard
 * on game ids.
 */
fun sanitizeDownloadFileName(raw: String): String {
    val name = raw.trim()
    require(name.isNotEmpty() && name != "." && name != "..") { "Unusable download file name: \"$raw\"" }
    require(!name.contains('/') && !name.contains('\\') && !name.contains(Char(0))) {
        "Download file name must be a single segment: \"$raw\""
    }
    return name
}
