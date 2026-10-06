package com.github.damontecres.wholphin.games

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The Google Play build bundles the cores listed in src/appstore/libretro-cores.txt */
class BundledCoresListTest {
    private val listed: Map<String, String> =
        File("src/appstore/libretro-cores.txt")
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line -> line.split(Regex("\\s+")).let { it[0] to it[1] } }

    @Test
    fun `every core in the catalog is bundled, under its buildbot name`() {
        assertEquals(GameCores.catalog.associate { it.coreId to it.buildbotName }, listed)
    }

    @Test
    fun `bundled cores load by the library name they are packaged under`() {
        assertEquals("libmupen64plus_next_libretro.so", CoreDownloadService.bundledLibraryName("mupen64plus_next"))
    }
}
