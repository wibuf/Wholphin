package com.github.damontecres.wholphin.preferences

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class PassOutProtectionTest {
    private val pref = AppPreference.PassOutProtection

    private fun stored(ms: Long): AppPreferences =
        AppPreferences
            .newBuilder()
            .setPlaybackPreferences(PlaybackPreferences.newBuilder().setPassOutProtectionMs(ms))
            .build()

    @Test
    fun ninetyMinutesIsAnOption() {
        val steps = (pref.min..pref.max).map { AppPreference.passOutProtectionMs(it).let { ms -> ms / 60_000 } }
        assertEquals(listOf(0L, 30, 60, 90, 120, 150, 180), steps)
    }

    @Test
    fun defaultIsStillTwoHours() {
        assertEquals(2.hours.inWholeMilliseconds, AppPreference.passOutProtectionMs(pref.defaultValue))
    }

    @Test
    fun hourValuesSavedBeforeKeepTheirPosition() {
        assertEquals(2L, pref.getter(stored(1.hours.inWholeMilliseconds)))
        assertEquals(6L, pref.getter(stored(3.hours.inWholeMilliseconds)))
        assertEquals(0L, pref.getter(stored(0)))
    }

    @Test
    fun ninetyMinutesRoundTrips() {
        val saved = pref.setter(stored(0), 3)
        assertEquals(90.minutes.inWholeMilliseconds, saved.playbackPreferences.passOutProtectionMs)
        assertEquals(3L, pref.getter(saved))
    }
}
