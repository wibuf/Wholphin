package com.github.damontecres.wholphin.games

import com.github.damontecres.wholphin.games.model.GameDetail
import com.github.damontecres.wholphin.games.model.GameSystem
import com.github.damontecres.wholphin.games.model.sanitizeDownloadFileName
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GamesModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `detail decodes the Moonbase shape and tolerates missing metadata`() {
        val detail =
            json.decodeFromString<GameDetail>(
                """
                {"id":"g1","title":"Chrono Trigger","system":"Super Nintendo","core":"snes",
                 "fileName":"Chrono Trigger (USA).sfc","sizeBytes":4194304,
                 "bios":[{"id":"b1","fileName":"scph1001.bin","sizeBytes":524288}],
                 "year":1995,"players":1,"backendOverrideSupported":true,"somethingNew":42}
                """.trimIndent(),
            )
        assertEquals("snes", detail.core)
        assertEquals(1995, detail.year)
        assertEquals("scph1001.bin", detail.bios.single().fileName)
        assertNull(detail.overview)
        assertEquals(emptyList<String>(), detail.availableCores)
    }

    @Test
    fun `system decodes with defaults`() {
        val system = json.decodeFromString<GameSystem>("""{"id":"snes","name":"Super Nintendo","core":"snes","gameCount":12}""")
        assertEquals(12, system.gameCount)
        assertEquals(0, json.decodeFromString<GameSystem>("""{"id":"x"}""").gameCount)
    }

    @Test
    fun `download names must be a single path segment`() {
        assertEquals("game.sfc", sanitizeDownloadFileName("  game.sfc "))
        assertThrows(IllegalArgumentException::class.java) { sanitizeDownloadFileName("../etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { sanitizeDownloadFileName("a\\b") }
        assertThrows(IllegalArgumentException::class.java) { sanitizeDownloadFileName("") }
        assertThrows(IllegalArgumentException::class.java) { sanitizeDownloadFileName(".") }
    }
}
