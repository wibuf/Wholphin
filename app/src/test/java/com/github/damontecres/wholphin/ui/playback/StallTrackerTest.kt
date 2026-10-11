package com.github.damontecres.wholphin.ui.playback

import androidx.media3.common.Player
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StallTrackerTest {
    private var clock = 0L
    private val tracker = StallTracker { clock }

    private fun state(
        s: Int,
        at: Long,
    ) {
        clock = at
        tracker.onPlaybackStateChanged(s)
    }

    @Test
    fun startupBufferIsNotAStall() {
        tracker.newItem("a")
        state(Player.STATE_BUFFERING, 0)
        state(Player.STATE_READY, 3_000)
        assertEquals(0, tracker.snapshot().count)
    }

    @Test
    fun countsEachBufferAfterPlaybackStarted() {
        tracker.newItem("a")
        state(Player.STATE_BUFFERING, 0)
        state(Player.STATE_READY, 1_000)
        state(Player.STATE_BUFFERING, 10_000)
        state(Player.STATE_READY, 11_500)
        state(Player.STATE_BUFFERING, 20_000)
        state(Player.STATE_READY, 22_000)
        clock = 25_000
        val s = tracker.snapshot()
        assertEquals(2, s.count)
        assertEquals(3_500, s.totalMs)
        assertEquals(2_000, s.lastMs)
        assertEquals(3_000L, s.sinceLastMs)
        assertNull(s.currentMs)
    }

    @Test
    fun aSeekIsNotAStall() {
        tracker.newItem("a")
        state(Player.STATE_READY, 0)
        tracker.onPositionDiscontinuity(mockk(), mockk(), Player.DISCONTINUITY_REASON_SEEK)
        state(Player.STATE_BUFFERING, 5_000)
        state(Player.STATE_READY, 6_000)
        assertEquals(0, tracker.snapshot().count)
    }

    @Test
    fun showsAStallInProgress() {
        tracker.newItem("a")
        state(Player.STATE_READY, 0)
        state(Player.STATE_BUFFERING, 5_000)
        clock = 7_500
        assertEquals(2_500L, tracker.snapshot().currentMs)
    }

    @Test
    fun reconnectingTheSameChannelKeepsTheCounts() {
        tracker.newItem("ch")
        state(Player.STATE_READY, 0)
        state(Player.STATE_BUFFERING, 1_000)
        state(Player.STATE_READY, 2_000)
        tracker.onReconnect()
        tracker.newItem("ch")
        assertEquals(1, tracker.snapshot().count)
        assertEquals(1, tracker.snapshot().reconnects)
        tracker.newItem("other")
        assertEquals(0, tracker.snapshot().count)
        assertEquals(0, tracker.snapshot().reconnects)
    }
}
