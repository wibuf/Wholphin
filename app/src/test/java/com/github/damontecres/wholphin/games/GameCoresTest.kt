package com.github.damontecres.wholphin.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCoresTest {
    @Test
    fun `every server core maps to exactly one catalog entry`() {
        val seen = mutableMapOf<String, String>()
        GameCores.catalog.forEach { core ->
            core.serverCores.forEach { server ->
                val previous = seen.put(server, core.coreId)
                assertNull("Server core $server claimed by both $previous and ${core.coreId}", previous)
            }
        }
        assertEquals("snes9x", GameCores.forServerCore("snes")?.coreId)
        assertEquals("genesis_plus_gx", GameCores.forServerCore("segaGG")?.coreId)
        assertEquals("fbneo", GameCores.forServerCore("arcade")?.coreId)
    }

    @Test
    fun `mame is server-only and unknown systems are rejected`() {
        assertNull(GameCores.forServerCore("mame"))
        assertNull(GameCores.forServerCore("dreamcast"))
        assertTrue(GameCores.isArcadeFamily("mame"))
        assertTrue(GameCores.isArcadeFamily("arcade"))
        assertTrue(!GameCores.isArcadeFamily("snes"))
    }

    @Test
    fun `nintendo 64 downloads the gles build under the plain id`() {
        val n64 = GameCores.byId("mupen64plus_next")!!
        assertEquals("mupen64plus_next_gles3", n64.buildbotName)
        assertEquals("mupen64plus_next_libretro.so", GameCores.coreFileName(n64.coreId))
    }

    @Test
    fun `save keys match Moonfin so states round trip between clients`() {
        assertEquals("lr-snes-abc", GameCores.stateKey("abc", "snes"))
        assertEquals("lr-abc", GameCores.legacyStateKey("abc"))
        assertEquals("moonfin-native-snes9x-abc", GameCores.optionsKey("snes9x", "abc"))
        assertEquals("moonfin-native-snes9x", GameCores.legacyOptionsKey("snes9x"))
    }

    @Test
    fun `stored options win over app defaults`() {
        val merged = GameCores.withOptionDefaults("mupen64plus_next", mapOf("mupen64plus-MaxTxCacheSize" to "8000", "x" to "1"))
        assertEquals("8000", merged["mupen64plus-MaxTxCacheSize"])
        assertEquals("1", merged["x"])
        assertEquals("1500", GameCores.withOptionDefaults("mupen64plus_next", emptyMap())["mupen64plus-MaxTxCacheSize"])
        assertEquals(emptyMap<String, String>(), GameCores.withOptionDefaults("snes9x", emptyMap()))
    }
}
