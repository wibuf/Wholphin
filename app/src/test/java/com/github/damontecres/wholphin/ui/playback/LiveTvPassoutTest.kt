package com.github.damontecres.wholphin.ui.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvPassoutTest {
    private val ninetyMinutes = 90 * 60_000L

    @Test
    fun `asks once the passout time has gone by with no button pressed`() {
        assertFalse(LiveTvPassout.isDue(nowMs = ninetyMinutes - 1, lastInteractionMs = 0, passOutMs = ninetyMinutes))
        assertTrue(LiveTvPassout.isDue(nowMs = ninetyMinutes, lastInteractionMs = 0, passOutMs = ninetyMinutes))
    }

    @Test
    fun `a button press starts the wait again`() {
        assertFalse(LiveTvPassout.isDue(nowMs = ninetyMinutes + 5, lastInteractionMs = 10, passOutMs = ninetyMinutes))
    }

    @Test
    fun `never asks when passout protection is off`() {
        assertFalse(LiveTvPassout.isDue(nowMs = Long.MAX_VALUE, lastInteractionMs = 0, passOutMs = 0))
    }
}
