package com.github.damontecres.wholphin.games

import android.os.Build

/**
 * A libretro core the app can download from the libretro buildbot
 *
 * @param coreId the libretro core id, which is also the buildbot file name
 * @param systemName the system shown in the download list
 * @param approxSizeMb rough download size, for the list
 * @param serverCores the Moonbase core names that route to this libretro core
 * @param buildbotName the buildbot file name when it differs from the id
 */
data class GameCore(
    val coreId: String,
    val systemName: String,
    val approxSizeMb: Int,
    val serverCores: Set<String>,
    val buildbotName: String = coreId,
    val supportFiles: CoreSupportFiles? = null,
)

/**
 * Runtime files a core needs in the system directory before it can boot
 *
 * @param url a zip whose entries all sit under a single top-level [folder]
 * @param folder the folder the core looks for inside the system directory
 * @param markerFile a file that only exists once the payload is unpacked
 */
data class CoreSupportFiles(
    val url: String,
    val folder: String,
    val markerFile: String,
)

/**
 * The catalog of cores, and how the server's system names map onto them
 *
 * Mirrors Moonfin's catalog so the same ROM library plays the same way in both clients. MAME
 * deliberately has no entry: the server's "arcade" core maps to FBNeo.
 */
object GameCores {
    private val ppssppSupport =
        CoreSupportFiles(
            url = "https://buildbot.libretro.com/assets/system/PPSSPP.zip",
            folder = "PPSSPP",
            markerFile = "compat.ini",
        )

    val catalog: List<GameCore> =
        listOf(
            GameCore("fceumm", "Nintendo Entertainment System", 1, setOf("nes")),
            GameCore("snes9x", "Super Nintendo", 3, setOf("snes")),
            GameCore("gambatte", "Game Boy and Game Boy Color", 1, setOf("gb")),
            GameCore("mgba", "Game Boy Advance", 3, setOf("gba")),
            GameCore("genesis_plus_gx", "Sega Genesis, Master System, and Game Gear", 2, setOf("segaMD", "segaMS", "segaGG")),
            GameCore("pcsx_rearmed", "PlayStation", 2, setOf("psx")),
            GameCore("fbneo", "Arcade (FBNeo)", 16, setOf("arcade")),
            // Android only publishes Nintendo 64 as the GLES build
            GameCore("mupen64plus_next", "Nintendo 64", 6, setOf("n64"), buildbotName = "mupen64plus_next_gles3"),
            GameCore("ppsspp", "PlayStation Portable", 18, setOf("psp"), supportFiles = ppssppSupport),
            GameCore("melonds", "Nintendo DS", 4, setOf("nds")),
            GameCore("mednafen_pce_fast", "PC Engine and TurboGrafx-16", 2, setOf("pce")),
            GameCore("stella", "Atari 2600", 2, setOf("atari2600")),
            GameCore("prosystem", "Atari 7800", 1, setOf("atari7800")),
            GameCore("handy", "Atari Lynx", 1, setOf("lynx")),
            GameCore("mednafen_wswan", "WonderSwan", 2, setOf("ws")),
            GameCore("mednafen_ngp", "Neo Geo Pocket", 1, setOf("ngp")),
            GameCore("mednafen_vb", "Virtual Boy", 2, setOf("vb")),
        )

    private val byServerCore: Map<String, GameCore> =
        buildMap {
            catalog.forEach { core -> core.serverCores.forEach { put(it, core) } }
        }

    private val byId: Map<String, GameCore> = catalog.associateBy { it.coreId }

    /** The core that plays a server system, or null when nothing here can */
    fun forServerCore(serverCore: String): GameCore? = byServerCore[serverCore]

    fun byId(coreId: String): GameCore? = byId[coreId]

    /**
     * Whether the server core is an arcade family
     *
     * Arcade ROMs are multi-file ZIPs the core must receive intact, unlike every other system's
     * single-file ROMs.
     */
    fun isArcadeFamily(serverCore: String): Boolean = serverCore == "arcade" || serverCore == "mame"

    /**
     * Evidence-backed defaults applied below stored user settings
     */
    val optionDefaults: Map<String, Map<String, String>> =
        mapOf(
            "mupen64plus_next" to
                mapOf(
                    // 8000 caused unbounded GPU-memory growth on Shield and Fire TV Cube;
                    // 1500 reached a stable plateau in the measured N64 runs.
                    "mupen64plus-MaxTxCacheSize" to "1500",
                ),
        )

    fun withOptionDefaults(
        coreId: String,
        settings: Map<String, String>,
    ): Map<String, String> = optionDefaults[coreId].orEmpty() + settings

    /**
     * The save-state key for a game, isolated by core: a state produced by one core cannot be
     * loaded by another. The prefix matches Moonfin so a state saved there loads here.
     */
    fun stateKey(
        gameId: String,
        serverCore: String,
    ): String = "lr-$serverCore-$gameId"

    /** The key the native backend used before the core segment was added */
    fun legacyStateKey(gameId: String): String = "lr-$gameId"

    /** Per-game emulator options, shared across devices */
    fun optionsKey(
        coreId: String,
        gameId: String,
    ): String = "moonfin-native-$coreId-$gameId"

    /** Core-wide emulator options, read when a game has none of its own */
    fun legacyOptionsKey(coreId: String): String = "moonfin-native-$coreId"

    /** The installed core file name */
    fun coreFileName(coreId: String): String = "${coreId}_libretro.so"

    /** The buildbot ABI directory for this device, or null when libretro has no build for it */
    fun buildbotAbi(): String? = Build.SUPPORTED_ABIS.firstOrNull { it in BUILDBOT_ABIS }

    /**
     * The buildbot download URL for a core, or null when there is no build for the architecture.
     * The zip holds one core file, which the downloader extracts regardless of its name inside.
     */
    fun downloadUrl(core: GameCore): String? {
        val abi = buildbotAbi() ?: return null
        return "https://buildbot.libretro.com/nightly/android/latest/$abi/${core.buildbotName}_libretro_android.so.zip"
    }

    private val BUILDBOT_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
}
