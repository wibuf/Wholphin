package com.github.damontecres.wholphin.preferences

import com.github.damontecres.wholphin.BuildConfig

/**
 * Fork-only: GooseFlix's defaults for a fresh install, where they differ from upstream's. A setting
 * someone has already changed keeps their value; these only fill in what has never been set.
 */
object GooseFlixDefaults {
    val themeColors: AppThemeColors = AppThemeColors.OLED_BLACK

    const val ONE_CLICK_PAUSE = true

    const val MAX_ITEMS_PER_ROW = 50

    /** The server's critic and community scores are hidden: the MDBList ratings replace them */
    val hiddenDisplayToggles = setOf(DisplayToggle.CRITIC_RATING, DisplayToggle.COMMUNITY_RATING)

    fun displayToggles(all: List<DisplayToggle>): List<DisplayToggle> =
        if (BuildConfig.GOOSEFLIX) all.filterNot { it in hiddenDisplayToggles } else all
}
