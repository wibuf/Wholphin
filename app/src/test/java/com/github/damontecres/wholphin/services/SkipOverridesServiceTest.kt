package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.preferences.SkipSegmentBehavior
import com.github.damontecres.wholphin.ui.playback.SkipSettingsUi
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fork-only (GooseFlix): per-show skip choices */
class SkipOverridesServiceTest {
    @Test
    fun `choices are saved as one short value per show, and read back`() {
        val choices = mapOf(MediaSegmentType.OUTRO to SkipSegmentBehavior.IGNORE, MediaSegmentType.INTRO to SkipSegmentBehavior.AUTO_SKIP)
        val saved = SkipOverridesService.format(choices)
        assertEquals("INTRO=AUTO_SKIP,OUTRO=IGNORE", saved)
        assertEquals(choices, SkipOverridesService.parse(saved))
    }

    @Test
    fun `no choices removes the entry, and junk is ignored`() {
        assertNull(SkipOverridesService.format(emptyMap()))
        assertEquals(emptyMap<MediaSegmentType, SkipSegmentBehavior>(), SkipOverridesService.parse(null))
        assertEquals(
            mapOf(MediaSegmentType.RECAP to SkipSegmentBehavior.ASK_TO_SKIP),
            SkipOverridesService.parse("RECAP=ASK_TO_SKIP,NOPE=AUTO_SKIP,INTRO=MAYBE,garbage"),
        )
    }

    @Test
    fun `the show's choice wins over the default, and the summary says which`() {
        val ui =
            SkipSettingsUi(
                types = listOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO),
                defaults = mapOf(MediaSegmentType.INTRO to SkipSegmentBehavior.ASK_TO_SKIP, MediaSegmentType.OUTRO to SkipSegmentBehavior.ASK_TO_SKIP),
                overrides = mapOf(MediaSegmentType.INTRO to SkipSegmentBehavior.AUTO_SKIP),
                onChange = { _, _ -> },
            )
        assertEquals(SkipSegmentBehavior.AUTO_SKIP, ui.effective(MediaSegmentType.INTRO))
        assertEquals(SkipSegmentBehavior.ASK_TO_SKIP, ui.effective(MediaSegmentType.OUTRO))
        assertEquals("Intro: auto · Credits: ask", ui.summary)
    }
}
