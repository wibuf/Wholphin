package com.github.damontecres.wholphin.ui.playback

import android.os.SystemClock
import androidx.media3.common.Player

/**
 * Fork-only: counts the times playback stops to buffer once it has started, for the debug overlay.
 *
 * A seek's buffer is the viewer's doing, not a stall, so it does not count. Reconnects (the live TV
 * watchdog restarting a stream) are counted separately and survive the restart, since a reconnect
 * plays the same channel again.
 */
class StallTracker(
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {
    private var itemId: Any? = null
    private var started = false
    private var seeking = false
    private var stallStart: Long? = null
    private var count = 0
    private var totalMs = 0L
    private var lastMs = 0L
    private var lastEnded: Long? = null
    private var reconnects = 0

    /** Playback of [id] is starting; a different item clears the counts */
    fun newItem(id: Any?) {
        if (id != itemId) {
            itemId = id
            count = 0
            totalMs = 0L
            lastMs = 0L
            lastEnded = null
            reconnects = 0
        }
        started = false
        seeking = false
        stallStart = null
    }

    fun onReconnect() {
        reconnects++
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) seeking = true
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                if (started && !seeking && stallStart == null) stallStart = now()
            }

            Player.STATE_READY -> {
                val at = now()
                stallStart?.let {
                    count++
                    lastMs = at - it
                    totalMs += lastMs
                    lastEnded = at
                }
                stallStart = null
                started = true
                seeking = false
            }

            else -> {
                stallStart = null
            }
        }
    }

    fun snapshot(): StallSnapshot {
        val at = now()
        return StallSnapshot(
            count = count,
            totalMs = totalMs,
            lastMs = lastMs,
            sinceLastMs = lastEnded?.let { at - it },
            currentMs = stallStart?.let { at - it },
            reconnects = reconnects,
        )
    }
}

data class StallSnapshot(
    val count: Int,
    val totalMs: Long,
    val lastMs: Long,
    /** Time since the last stall ended, or null if there has been none */
    val sinceLastMs: Long?,
    /** How long the current stall has lasted, or null if playing */
    val currentMs: Long?,
    val reconnects: Int,
)

/** [StallSnapshot] plus what the player has buffered, for the debug overlay */
data class StallDebugInfo(
    val stalls: StallSnapshot,
    val bufferAheadMs: Long,
    /** How far behind the live edge playback is, or null if not live or unknown */
    val liveOffsetMs: Long?,
)
