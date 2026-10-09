package com.github.damontecres.wholphin.preferences

import com.github.damontecres.wholphin.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class GooseFlixDefaultsTest {
    private val all = DisplayToggle.entries.filterNot { it == DisplayToggle.UNRECOGNIZED }

    @Test
    fun hidesServerCriticAndCommunityRatings() {
        assumeTrue(BuildConfig.GOOSEFLIX)
        val shown = AppPreference.DisplayTogglesPref.defaultValue
        assertFalse(DisplayToggle.CRITIC_RATING in shown)
        assertFalse(DisplayToggle.COMMUNITY_RATING in shown)
        assertEquals(all - GooseFlixDefaults.hiddenDisplayToggles, shown)
    }

    @Test
    fun freshInstallGetsTheForkDefaults() {
        assumeTrue(BuildConfig.GOOSEFLIX)
        val prefs = AppPreferencesSerializer().defaultValue
        assertEquals(AppThemeColors.OLED_BLACK, prefs.interfacePreferences.appThemeColors)
        assertEquals(true, prefs.playbackPreferences.oneClickPause)
        assertEquals(50, prefs.homePagePreferences.maxItemsPerRow)
        assertEquals(all - GooseFlixDefaults.hiddenDisplayToggles, prefs.interfacePreferences.displayTogglesList)
    }
}
