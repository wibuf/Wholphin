package com.github.damontecres.wholphin.ui.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import timber.log.Timber
import java.io.IOException

/**
 * Fork: a step-by-step log of starting playback, for chasing "it plays for a few seconds, then
 * pauses and starts over". Every line is tagged [TAG] and stamped with the milliseconds since
 * play was pressed, at INFO so release builds log it too:
 *
 *     adb logcat -v time -s GooseFlixPlay
 *
 * It records the playback decision and every later reload ([mark]), and from the player itself:
 * state changes, position jumps with their reason (a jump back to 0 is the "starts over"),
 * timeline and track changes, the first frame, load errors (which ExoPlayer retries quietly) and
 * errors. Nothing here changes playback.
 */
@UnstableApi
class PlaybackTimeline :
    Player.Listener,
    AnalyticsListener {
    private var startedAt = SystemClock.elapsedRealtime()

    /** Starts the clock again, for a new item */
    fun start(what: String) {
        startedAt = SystemClock.elapsedRealtime()
        log("START $what")
    }

    /** Something the view model did, eg the playback decision or a reload and why */
    fun mark(what: String) = log(what)

    private fun log(message: String) {
        Timber.tag(TAG).i("+%dms %s", SystemClock.elapsedRealtime() - startedAt, message)
    }

    override fun onPlaybackStateChanged(playbackState: Int) = log("state ${stateName(playbackState)}")

    override fun onIsPlayingChanged(isPlaying: Boolean) = log("isPlaying=$isPlaying")

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) = log("playWhenReady=$playWhenReady reason=${playWhenReadyReason(reason)}")

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) = log("position jump ${oldPosition.positionMs}ms -> ${newPosition.positionMs}ms reason=${discontinuityReason(reason)}")

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) = log("media item ${mediaItem?.mediaId} reason=$reason")

    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int,
    ) {
        if (timeline.isEmpty) return log("timeline empty reason=$reason")
        val window = timeline.getWindow(0, Timeline.Window())
        log(
            "timeline reason=${if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) "SOURCE_UPDATE" else "PLAYLIST_CHANGED"} " +
                "duration=${window.durationMs.takeIf { it != C.TIME_UNSET }}ms dynamic=${window.isDynamic} live=${window.isLive()} " +
                "defaultPosition=${window.defaultPositionMs}ms",
        )
    }

    override fun onTracksChanged(tracks: Tracks) {
        val selected =
            tracks.groups
                .filter { it.isSelected }
                .joinToString { group ->
                    val format = (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) }
                    "${trackType(group.type)}:${format?.sampleMimeType}/${format?.language}"
                }
        log("tracks ${tracks.groups.size} groups, selected [$selected]")
    }

    override fun onRenderedFirstFrame() = log("first frame")

    override fun onPlayerError(error: PlaybackException) = log("ERROR ${error.errorCodeName}: ${error.cause ?: error.message}")

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) = log("load error (player retries) ${loadEventInfo.uri.lastPathSegment}: ${error.javaClass.simpleName} ${error.message}")

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) = log("dropped $droppedFrames frames in ${elapsedMs}ms")

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) = log("audio underrun")

    companion object {
        const val TAG = "GooseFlixPlay"

        fun stateName(state: Int) =
            when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "?$state"
            }

        fun discontinuityReason(reason: Int) =
            when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
                Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
                Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
                Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
                Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
                Player.DISCONTINUITY_REASON_SILENCE_SKIP -> "SILENCE_SKIP"
                else -> "?$reason"
            }

        fun playWhenReadyReason(reason: Int) =
            when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
                else -> "?$reason"
            }

        fun trackType(type: Int) =
            when (type) {
                C.TRACK_TYPE_VIDEO -> "video"
                C.TRACK_TYPE_AUDIO -> "audio"
                C.TRACK_TYPE_TEXT -> "text"
                else -> "type$type"
            }
    }
}
