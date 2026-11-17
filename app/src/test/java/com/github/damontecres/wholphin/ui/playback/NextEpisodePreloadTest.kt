package com.github.damontecres.wholphin.ui.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fork-only (GooseFlix): when the next episode is warmed, and where skips land */
class NextEpisodePreloadTest {
    private val min = 60_000L

    @Test
    fun `warms a minute before the outro, or before the end without one`() {
        assertEquals(20 * min, NextEpisodePreload.triggerAtMs(24 * min, outroStartMs = 21 * min))
        assertEquals(23 * min, NextEpisodePreload.triggerAtMs(24 * min, outroStartMs = null))
    }

    @Test
    fun `never in the first half, and not before the duration is known`() {
        // An outro marked early, or a short item
        assertEquals(12 * min, NextEpisodePreload.triggerAtMs(24 * min, outroStartMs = 2 * min))
        assertEquals(min / 2, NextEpisodePreload.triggerAtMs(min, outroStartMs = null))
        assertNull(NextEpisodePreload.triggerAtMs(-1, outroStartMs = null))
        // An outro "after" the end is ignored
        assertEquals(23 * min, NextEpisodePreload.triggerAtMs(24 * min, outroStartMs = 25 * min))
    }

    @Test
    fun `a skip lands at the segment's end when there's more to watch`() {
        // Credits end, then a 40 s post-credits scene
        assertEquals(22 * min + 20_000, NextEpisodePreload.skipTargetMs(22 * min + 20_000, 23 * min))
    }

    @Test
    fun `a skip that leaves only a few seconds goes to the end`() {
        assertEquals(24 * min, NextEpisodePreload.skipTargetMs(24 * min - 5_000, 24 * min))
    }

    @Test
    fun `auto-skipped segments right after are skipped too, then the end`() {
        // Credits to 22:30, a preview the user auto-skips from 22:31 to 23:55, 24:00 long
        val preview = (22 * min + 31_000) to (23 * min + 55_000)
        assertEquals(24 * min, NextEpisodePreload.skipTargetMs(22 * min + 30_000, 24 * min, listOf(preview)))
        // A preview further away isn't chained
        val later = (23 * min) to (23 * min + 55_000)
        assertEquals(22 * min + 30_000, NextEpisodePreload.skipTargetMs(22 * min + 30_000, 24 * min, listOf(later)))
    }
}
