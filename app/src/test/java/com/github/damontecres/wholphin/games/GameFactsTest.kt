package com.github.damontecres.wholphin.games

import com.github.damontecres.wholphin.games.model.GameDetail
import com.github.damontecres.wholphin.games.model.GameSummary
import com.github.damontecres.wholphin.games.ui.gameFacts
import org.junit.Assert.assertEquals
import org.junit.Test

class GameFactsTest {
    private val game = GameSummary(id = "1", title = "Super Mario World", system = "SNES")

    @Test
    fun `everything known goes in one line`() {
        val detail = GameDetail(system = "Super Nintendo", year = 1990, genre = "Platformer", players = 2)
        assertEquals("1990 · Super Nintendo · Platformer · 1-2 players", gameFacts(game, detail))
    }

    @Test
    fun `missing facts are left out`() {
        assertEquals("before the details arrive", "SNES", gameFacts(game, null))
        assertEquals("SNES · 1 player", gameFacts(game, GameDetail(players = 1)))
        assertEquals("", gameFacts(GameSummary(), GameDetail(year = 0)))
    }
}
