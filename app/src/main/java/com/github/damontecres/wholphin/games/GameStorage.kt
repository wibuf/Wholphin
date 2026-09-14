package com.github.damontecres.wholphin.games

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk layout for native game playback
 *
 * ROMs can always be fetched from the server again, so they live in the cache directory the OS
 * is free to reclaim. Cores, saves and BIOS files go to internal storage instead, out of reach of
 * a cache clear.
 */
@Singleton
class GameStorage
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private fun dir(
            parent: File,
            name: String,
        ): File = File(parent, name).also { it.mkdirs() }

        /** One folder per cached game, keyed by library and a fixed-length digest of the game id */
        fun romsRoot(): File = dir(context.cacheDir, "games/cache")

        fun romDir(
            libraryId: String,
            gameId: String,
        ): File = dir(romsRoot(), "$libraryId/${gameDirectoryKey(gameId)}")

        /** Where cores look for BIOS and support files */
        fun systemDir(): File = dir(context.filesDir, "games/system")

        /** SRAM and other per-game files the core writes itself */
        fun saveDir(): File = dir(context.filesDir, "games/saves")

        /** Downloaded cores, tagged by ABI so a shared directory stays correct across architectures */
        fun coresDir(abi: String): File = dir(context.filesDir, "games/cores/$abi")

        /** Total bytes held by cached ROMs */
        fun cachedRomBytes(): Long = romsRoot().walkBottomUp().filter { it.isFile }.sumOf { it.length() }

        fun deleteAllCachedGames() {
            romsRoot().deleteRecursively()
        }

        companion object {
            /**
             * Keeping this component bounded avoids exceeding path-length limits in libretro cores
             */
            fun gameDirectoryKey(gameId: String): String =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(gameId.toByteArray())
                    .joinToString("") { "%02x".format(it) }
        }
    }
