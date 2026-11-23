package com.github.damontecres.wholphin.ui.nav

import android.view.KeyEvent
import org.junit.Assert
import org.junit.Test
import java.util.UUID

class HoldBackToHomeTest {
    private var homes = 0

    private fun key(
        down: Boolean = true,
        repeatCount: Int = 0,
        heldMs: Long = 0,
        current: Destination? = Destination.Favorites,
        listening: Boolean = true,
        keyCode: Int = KeyEvent.KEYCODE_BACK,
    ): Boolean =
        HoldBackToHome.onKey(
            keyCode = keyCode,
            down = down,
            up = !down,
            repeatCount = repeatCount,
            heldMs = heldMs,
            current = current,
            listening = listening,
            goHome = { homes++ },
        )

    @Test
    fun `a short press is Back as usual`() {
        Assert.assertFalse(key())
        Assert.assertFalse(key(down = false, heldMs = 120))
        Assert.assertEquals(0, homes)
    }

    @Test
    fun `holding goes Home once and the release isn't a Back press`() {
        Assert.assertFalse(key())
        Assert.assertTrue(key(repeatCount = 1, heldMs = 500))
        Assert.assertEquals(0, homes)
        Assert.assertTrue(key(repeatCount = 10, heldMs = 1_000))
        Assert.assertTrue(key(repeatCount = 11, heldMs = 1_050))
        Assert.assertEquals(1, homes)
        Assert.assertTrue(key(down = false, heldMs = 1_200))
        // The next press is an ordinary one again
        Assert.assertFalse(key())
        Assert.assertFalse(key(down = false, heldMs = 100))
        Assert.assertEquals(1, homes)
    }

    @Test
    fun `let go before a second is Back, without the grid's own hold`() {
        Assert.assertFalse(key())
        // Repeats (where a grid's hold would act) don't reach the page
        Assert.assertTrue(key(repeatCount = 5, heldMs = 700))
        Assert.assertFalse(key(down = false, heldMs = 800))
        Assert.assertEquals(0, homes)
    }

    @Test
    fun `players keep Back`() {
        val playing = Destination.Playback(UUID.randomUUID(), 0L)
        Assert.assertFalse(key(current = playing))
        Assert.assertFalse(key(current = playing, repeatCount = 20, heldMs = 2_000))
        Assert.assertFalse(key(current = playing, down = false, heldMs = 2_100))
        Assert.assertFalse(key(current = Destination.NowPlaying, repeatCount = 20, heldMs = 2_000))
        Assert.assertEquals(0, homes)
    }

    @Test
    fun `nothing happens before sign-in or for other keys`() {
        Assert.assertFalse(key(listening = false, repeatCount = 20, heldMs = 2_000))
        Assert.assertFalse(key(keyCode = KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 20, heldMs = 2_000))
        Assert.assertEquals(0, homes)
    }

    @Test
    fun `Home itself refreshes`() {
        Assert.assertFalse(key(current = Destination.Home()))
        Assert.assertTrue(key(current = Destination.Home(), repeatCount = 12, heldMs = 1_100))
        Assert.assertTrue(key(current = Destination.Home(), down = false, heldMs = 1_200))
        Assert.assertEquals(1, homes)
    }
}
