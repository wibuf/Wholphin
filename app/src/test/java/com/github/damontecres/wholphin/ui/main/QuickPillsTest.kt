package com.github.damontecres.wholphin.ui.main

import com.github.damontecres.wholphin.services.LiveColors
import com.github.damontecres.wholphin.services.LiveEvent
import com.github.damontecres.wholphin.services.OnNow
import com.github.damontecres.wholphin.services.QuickPill
import com.github.damontecres.wholphin.services.QuickPillsService
import com.github.damontecres.wholphin.services.ResolvedPill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class QuickPillsTest {
    @Test
    fun `the config Scuffed writes is read`() {
        val config =
            QuickPillsService.parseConfig(
                """{"version":1,"pills":[{"type":"guide"},{"type":"channel","number":"807","label":"7 News"},""" +
                    """{"type":"resume"},{"type":"someday","extra":true}]}""",
            )
        // An unknown pill type from a newer Scuffed makes the whole config unreadable, not half-read
        assertNull(config)

        val ok =
            QuickPillsService.parseConfig(
                """{"pills":[{"type":"guide"},{"type":"channel","number":"807","label":"7 News","color":"x"},{"type":"resume"}]}""",
            )!!
        assertEquals(
            listOf(
                QuickPill(QuickPill.Type.GUIDE),
                QuickPill(QuickPill.Type.CHANNEL, number = "807", label = "7 News"),
                QuickPill(QuickPill.Type.RESUME),
            ),
            ok.pills,
        )
        assertNull(QuickPillsService.parseConfig("not json"))
    }

    @Test
    fun `what's on reads as one line with time left`() {
        val now = LocalDateTime.of(2026, 10, 3, 17, 18)
        val onNow =
            OnNow(
                title = "7 News at 5",
                episodeTitle = null,
                overview = null,
                start = now.withMinute(0),
                end = now.withMinute(30),
                imageUrl = null,
            )
        val pill = ResolvedPill.Channel("7 News", "807", UUID.randomUUID(), "WHDH", null, onNow)
        val meta = channelMeta(pill, onNow, now)
        assert(meta.startsWith("WHDH · ")) { meta }
        assert(meta.endsWith(" · 12 min left")) { meta }
        assertEquals(0.6f, progress(onNow, now)!!, 0.001f)
        assertNull(progress(onNow.copy(end = null), now))
    }

    @Test
    fun `the guide's New marker becomes a flag`() {
        assertEquals("7 News Today in New England" to true, QuickPillsService.cleanProgramTitle("7 News Today in New England  ᴺᵉʷ"))
        assertEquals("Chicago Fire" to false, QuickPillsService.cleanProgramTitle("Chicago Fire"))
        assertEquals("Patriots vs. Bills" to false, QuickPillsService.cleanProgramTitle("Patriots vs. Bills ᴸᶦᵛᵉ"))
        val now = LocalDateTime.of(2026, 10, 3, 17, 18)
        val onNow = OnNow("7 News", isNew = true, episodeTitle = null, overview = null, start = null, end = null, imageUrl = null)
        val pill = ResolvedPill.Channel("7 News", "807", UUID.randomUUID(), "WHDH", null, onNow)
        assertEquals("WHDH · New", channelMeta(pill, onNow, now))
    }

    private fun utc(text: String) = LocalDateTime.ofInstant(Instant.parse(text), ZoneId.systemDefault())

    private val bruins =
        LiveEvent(
            label = "Bruins",
            number = "1002",
            channelId = UUID(0, 1002).toString(),
            phase = "pregame",
            title = "Bruins at Wild",
            subtitle = "NHL Hockey · NESN",
            start = "2026-10-04T00:00:00Z",
            end = "2026-10-04T02:30:00Z",
            onNow = "Bruins Face-Off Live",
            colors = mapOf("bg" to "#111111", "bg2" to "#3A3A3A", "tile" to "#FFB81C"),
        )

    @Test
    fun `a kept live event shows only in its window, and turns live at the start`() {
        assertNull(QuickPillsService.liveWindow(bruins, utc("2026-10-03T22:30:00Z")), "over an hour early")
        assertEquals(false, QuickPillsService.liveWindow(bruins, utc("2026-10-03T23:30:00Z")))
        // Scuffed said pregame, but that was before the puck dropped
        assertEquals(true, QuickPillsService.liveWindow(bruins, utc("2026-10-04T00:05:00Z")))
        assertNull(QuickPillsService.liveWindow(bruins, utc("2026-10-04T02:30:00Z")), "over")
        assertNull(QuickPillsService.liveWindow(bruins.copy(start = "soon"), utc("2026-10-04T00:05:00Z")))
    }

    private fun assertNull(
        value: Any?,
        why: String,
    ) = org.junit.Assert.assertNull(why, value)

    @Test
    fun `team colours`() {
        assertEquals(0xFFFFB81C, LiveColors.parse("#FFB81C"))
        assertEquals(0xFFC60C30, LiveColors.parse(" c60c30 "))
        assertNull(LiveColors.parse("red"), "not hex")
        assertEquals(LiveColors(0xFF111111, 0xFF111111, LiveColors.DEFAULT.tile), LiveColors.of(mapOf("bg" to "#111111")))
    }

    @Test
    fun `live events and the per-user switch are read`() {
        val off = QuickPillsService.parseConfig("""{"version":1,"pills":[],"liveEvents":false}""")!!
        assertFalse(off.liveEvents)
        assertTrue(QuickPillsService.parseConfig("""{"version":1,"pills":[{"type":"guide"}]}""")!!.liveEvents)
    }

    private fun live(isLive: Boolean) =
        ResolvedPill.Live(
            label = "Bruins",
            number = "1002",
            channelId = UUID(0, 1002),
            isLive = isLive,
            title = "Bruins at Wild",
            subtitle = "NHL Hockey · NESN",
            start = LocalDateTime.of(2026, 10, 3, 20, 0),
            end = LocalDateTime.of(2026, 10, 3, 22, 30),
            onNow = "Bruins Face-Off Live",
            colors = LiveColors.DEFAULT,
            logoUrl = null,
            artUri = "",
        )

    @Test
    fun `live header lines`() {
        assertEquals("PREGAME · BRUINS · STARTS IN 25 MIN", liveEyebrow(live(false), LocalDateTime.of(2026, 10, 3, 19, 35)))
        assertEquals("LIVE NOW · BRUINS", liveEyebrow(live(true), LocalDateTime.of(2026, 10, 3, 20, 35)))
        val meta = liveMeta(live(true), LocalDateTime.of(2026, 10, 3, 21, 45))
        assertTrue(meta, meta.startsWith("NHL Hockey · NESN · Ch 1002 · "))
        assertTrue(meta, meta.endsWith(" · 45 min left"))
        assertFalse(liveMeta(live(false), LocalDateTime.of(2026, 10, 3, 19, 45)).contains("left"))
    }

    @Test
    fun `row keys stay put and are unique`() {
        val guide = ResolvedPill.Guide("Guide", UUID(0, 1), org.jellyfin.sdk.model.api.BaseItemKind.USER_VIEW, "")
        val row = listOf(live(true), guide, guide)
        assertEquals(listOf("live_${UUID(0, 1002)}", "guide", "guide#1"), row.mapIndexed { i, p -> rowKey(row, i, p) })
        assertEquals("guide", rowKey(listOf(guide), 0, guide), "same key with or without the live pill in front")
    }

    private fun assertEquals(
        expected: Any?,
        actual: Any?,
        why: String,
    ) = org.junit.Assert.assertEquals(why, expected, actual)
}
