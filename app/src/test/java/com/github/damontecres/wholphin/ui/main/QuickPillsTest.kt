package com.github.damontecres.wholphin.ui.main

import com.github.damontecres.wholphin.services.OnNow
import com.github.damontecres.wholphin.services.QuickPill
import com.github.damontecres.wholphin.services.QuickPillsService
import com.github.damontecres.wholphin.services.ResolvedPill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
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
}
