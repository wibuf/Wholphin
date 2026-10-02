package com.github.damontecres.wholphin.ui.playback

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Live TV channels that would not start without a server transcode
 *
 * Kept for as long as the app runs, so flipping back to a channel that already failed to play
 * directly goes straight to the transcode instead of failing the same way first. It lives outside
 * the playback view model because changing channel can create a new one. Channel ids belong to a
 * server, so an entry left over from another server can only ever cost an unneeded transcode.
 */
object LiveTvTranscodeMemory {
    private val channels: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun needsTranscode(channelId: UUID): Boolean = channelId in channels

    fun markNeedsTranscode(channelId: UUID) {
        channels.add(channelId)
    }

    fun clear() {
        channels.clear()
    }
}
