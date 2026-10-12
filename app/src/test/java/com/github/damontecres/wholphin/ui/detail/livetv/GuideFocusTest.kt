package com.github.damontecres.wholphin.ui.detail.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class GuideFocusTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val empty = UUID.randomUUID()

    private fun prog(
        channel: UUID,
        start: Float,
        end: Float,
    ) = GuideFocus.Slot(channel, start, end)

    // Channel A: a 3-hour movie that started an hour ago, then news. Channel B: half-hour shows.
    private val programs =
        listOf(
            prog(a, -1f, 2f),
            prog(a, 2f, 3f),
            prog(b, 0f, 0.5f),
            prog(b, 0.5f, 1f),
            prog(b, 1f, 1.5f),
        )

    @Test
    fun downGoesToWhatIsOnAtTheAnchorTime() {
        assertEquals(3, GuideFocus.slotAt(programs, b, 0.75f))
        assertEquals(0, GuideFocus.slotAt(programs, a, 0.75f))
    }

    @Test
    fun aLongProgrammeDoesNotDragTheAnchor() {
        // Down from the movie with the anchor still at now (0.2): the first half-hour, not the
        // show nearest the movie's middle
        assertEquals(2, GuideFocus.slotAt(programs, b, 0.2f))
    }

    @Test
    fun aGapFallsBackToTheNextThenThePreviousProgramme() {
        assertEquals(1, GuideFocus.slotAt(programs, a, 2.5f)) // airing
        assertEquals(4, GuideFocus.slotAt(programs, b, 5f)) // nothing after: last before
        assertEquals(0, GuideFocus.slotAt(listOf(prog(b, 0f, 0.5f)), b, -1f)) // nothing on: next
    }

    @Test
    fun aChannelWithNoProgrammesHasNoTarget() {
        assertNull(GuideFocus.slotAt(programs, empty, 0.5f))
    }

    @Test
    fun sidewaysOntoWhatIsAiringAnchorsAtNow() {
        assertEquals(0.2f, GuideFocus.anchorFor(-1f, 0.2f))
        assertEquals(2f, GuideFocus.anchorFor(2f, 0.2f))
    }

    @Test
    fun slotForTheAnchor() {
        val slots = listOf(0f, 0.5f, 1f, 1.5f)
        assertEquals(0, GuideFocus.slotAt(slots, 0.2f))
        assertEquals(2, GuideFocus.slotAt(slots, 1f))
        assertEquals(3, GuideFocus.slotAt(slots, 9f))
        assertEquals(0, GuideFocus.slotAt(slots, -1f))
    }
}
