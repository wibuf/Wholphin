package com.github.damontecres.wholphin.ui.detail.livetv

import kotlin.math.max

/**
 * Fork-only: moving around the guide like a cable box.
 *
 * Up and down go to whatever is on the next channel at the time you're looking at (the anchor),
 * and only scroll the guide vertically. Left and right move along a channel, set the anchor to
 * where you land, and only scroll horizontally. Upstream instead let Android pick the nearest box
 * in the next row and centred every focused programme both ways, so a long programme dragged the
 * guide sideways as you went down the channels.
 */
object GuideFocus {
    /** Where a programme sits: its channel and its start and end, in hours from the guide's start */
    data class Slot(
        val channelId: Any,
        val startHours: Float,
        val endHours: Float,
    )

    fun programAt(
        programs: List<TvProgram>,
        channelId: Any,
        anchorHours: Float,
    ): Int? = slotAt(programs.map { Slot(it.channelId, it.startHours, it.endHours) }, channelId, anchorHours)

    /**
     * Index into [slots] of the one on [channelId] airing at [anchorHours]; failing that the first
     * to start after it, failing that the last one before. Null if the channel has none.
     */
    fun slotAt(
        slots: List<Slot>,
        channelId: Any,
        anchorHours: Float,
    ): Int? {
        var after: Int? = null
        var before: Int? = null
        slots.forEachIndexed { index, p ->
            if (p.channelId != channelId) return@forEachIndexed
            if (p.startHours <= anchorHours && anchorHours < p.endHours) return index
            if (p.startHours > anchorHours) {
                if (after == null || p.startHours < slots[after!!].startHours) after = index
            } else if (before == null || p.startHours > slots[before!!].startHours) {
                before = index
            }
        }
        return after ?: before
    }

    /** The anchor after landing on a programme sideways: its start, or now if it's already on */
    fun anchorFor(
        programStartHours: Float,
        nowHours: Float,
    ): Float = max(programStartHours, nowHours)

    /** Index of the timeline slot containing [anchorHours], given each slot's start */
    fun slotAt(
        slotStartHours: List<Float>,
        anchorHours: Float,
    ): Int = slotStartHours.indexOfLast { it <= anchorHours }.coerceAtLeast(0)
}
