package com.github.damontecres.wholphin.games

import com.github.damontecres.wholphin.games.model.GameLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleGameLibrariesTest {
    private val videoGames = GameLibrary("94ee7062f188aab7461b954a93065970", "Video Games")

    @Test
    fun `a user with the library sees its games`() {
        assertEquals(listOf(videoGames), visibleGameLibraries(listOf(videoGames), listOf("94ee7062-f188-aab7-461b-954a93065970")))
    }

    @Test
    fun `a user without the library sees none, whatever the plugin lists`() {
        // The Google Play review account: only the "Review" movie library
        assertEquals(emptyList<GameLibrary>(), visibleGameLibraries(listOf(videoGames), listOf("041f7732-50ad-dc64-a217-dd4877a8545d")))
    }
}
