package com.github.damontecres.wholphin.ui.playback

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fork-only (GooseFlix): getting the next episode ready before this one ends, and where skips land.
 *
 * **Preload next episode** (experimental, off by default): about a minute before the outro starts
 * (or before the end, without an outro), the player asks Jellyfin how it would play the next
 * episode and reads the start of its file. The server does its probing then, and a sleeping disk
 * or a streaming (`.strm`) source wakes up, so the next episode starts sooner. The way the episode
 * then starts is unchanged; this only warms things up.
 *
 * **Skips that reach the end:** skipping the outro (or anything else) when only a few seconds, or
 * only segments the user skips anyway (eg a preview after the credits), would be left goes to the
 * end of the episode, so the next one starts at once instead of playing the tail.
 */
@Singleton
class NextEpisodePreload
    @Inject
    constructor(
        @param:AuthOkHttpClient private val authClient: OkHttpClient,
        @param:StandardOkHttpClient private val plainClient: OkHttpClient,
    ) {
        /**
         * Reads the first [bytes] of [url] and throws them away, to wake whatever serves it. A
         * Jellyfin URL goes through the signed-in client; a remote one (a `.strm`'s) without
         * Jellyfin's credentials. Returns the bytes read, or -1 if it failed.
         */
        suspend fun readAhead(
            url: String,
            jellyfin: Boolean,
            bytes: Long = READ_AHEAD_BYTES,
        ): Long =
            withContext(WholphinDispatchers.IO) {
                val client = (if (jellyfin) authClient else plainClient).newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
                try {
                    client
                        .newCall(Request.Builder().url(url).header("Range", "bytes=0-${bytes - 1}").build())
                        .execute()
                        .use { response ->
                            if (!response.isSuccessful) return@withContext -1L
                            val source = response.body.source()
                            val sink = Buffer()
                            var total = 0L
                            while (total < bytes) {
                                val read = source.read(sink, minOf(CHUNK, bytes - total))
                                if (read == -1L) break
                                total += read
                                sink.clear()
                            }
                            total
                        }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Preload: could not read ahead")
                    -1L
                }
            }

        companion object {
            /** How long before the outro (or the end) the next episode is warmed up */
            const val LEAD_MS = 60_000L

            /** How much of the next file is read ahead */
            const val READ_AHEAD_BYTES = 4L * 1024 * 1024

            /** A skip that would leave less than this goes to the end instead */
            const val NEAR_END_MS = 10_000L

            private const val CHUNK = 64L * 1024

            /**
             * When to warm the next episode: [LEAD_MS] before the outro starts, or before the end
             * without one, but never in the first half (a short episode, or an outro marked early).
             * Null while the duration isn't known.
             */
            fun triggerAtMs(
                durationMs: Long,
                outroStartMs: Long?,
            ): Long? {
                if (durationMs <= 0) return null
                val end = outroStartMs?.takeIf { it in 1 until durationMs } ?: durationMs
                return maxOf(end - LEAD_MS, durationMs / 2)
            }

            /**
             * Where a skip of a segment ending at [segmentEndMs] should land. Segments the user
             * auto-skips that start where it ends ([autoSkipped], start and end in ms) are skipped
             * with it; then, if less than [NEAR_END_MS] would be left, the end of the episode.
             */
            fun skipTargetMs(
                segmentEndMs: Long,
                durationMs: Long,
                autoSkipped: List<Pair<Long, Long>> = emptyList(),
            ): Long {
                var end = segmentEndMs
                for ((start, stop) in autoSkipped.sortedBy { it.first }) {
                    if (start <= end + 2_000 && stop > end) end = stop
                }
                return if (durationMs > 0 && durationMs - end <= NEAR_END_MS) durationMs else end
            }
        }
    }

/** A segment drawn on the progress bar */
data class SeekBarSegment(
    val startMs: Long,
    val endMs: Long,
    val color: Color,
)

/** The colour of each kind of segment on the progress bar */
fun MediaSegmentType.seekBarColor(): Color? =
    when (this) {
        MediaSegmentType.INTRO -> Color(0xFFFFB300)
        MediaSegmentType.RECAP -> Color(0xFFAB47BC)
        MediaSegmentType.OUTRO -> Color(0xFF42A5F5)
        MediaSegmentType.PREVIEW -> Color(0xFF26A69A)
        MediaSegmentType.COMMERCIAL -> Color(0xFFEF5350)
        MediaSegmentType.UNKNOWN -> null
    }

fun List<MediaSegmentDto>.toSeekBarSegments(): List<SeekBarSegment> =
    mapNotNull { segment ->
        segment.type.seekBarColor()?.let { color ->
            SeekBarSegment(
                startMs = segment.startTicks.ticks.inWholeMilliseconds,
                endMs = segment.endTicks.ticks.inWholeMilliseconds,
                color = color,
            )
        }
    }

/** The current item's segments for the progress bar; empty when the setting is off */
val LocalSeekBarSegments = compositionLocalOf<List<SeekBarSegment>> { emptyList() }

/** Where the Skip button in the controls should seek to for a segment (see skipTargetMs) */
val LocalSegmentSkipTarget = staticCompositionLocalOf<(MediaSegmentDto) -> Long> { { it.endTicks.ticks.inWholeMilliseconds } }
