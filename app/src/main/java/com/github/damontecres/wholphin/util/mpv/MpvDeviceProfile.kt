package com.github.damontecres.wholphin.util.mpv

import com.github.damontecres.wholphin.util.profile.Codec
import com.github.damontecres.wholphin.util.profile.subtitleProfile
import com.github.damontecres.wholphin.util.profile.supportedAudioCodecs
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile

/**
 * The profile sent to the server when MPV plays
 *
 * @param supportsAVCHigh10 whether the device decodes 10-bit H.264 (Hi10P) in hardware. Fork
 *   (GooseFlix #1512): when it doesn't, Hi10P is left for the server to transcode, as the ExoPlayer
 *   profile does, instead of MPV direct-playing it in software and stuttering. Upstream sends no
 *   H.264 conditions, which is what passing true gives.
 */
fun mpvDeviceProfile(supportsAVCHigh10: Boolean = true) =
    buildDeviceProfile {
        name = "mpv"

        // TODO
//    maxStaticBitrate = maxBitrate
//    maxStreamingBitrate = maxBitrate
        transcodingProfile {
            type = DlnaProfileType.VIDEO
            context = EncodingContext.STREAMING

            container = Codec.Container.TS
            protocol = MediaStreamProtocol.HLS

            videoCodec(Codec.Video.HEVC)
            videoCodec(Codec.Video.H264)

            audioCodec(*supportedAudioCodecs)

            copyTimestamps = false
            enableSubtitlesInManifest = true
        }
        directPlayProfile {
            type = DlnaProfileType.VIDEO

            container(
                Codec.Container.ASF,
                Codec.Container.AVI,
                Codec.Container.HLS,
                Codec.Container.M4V,
                Codec.Container.MPEG,
                Codec.Container.MPEGTS,
                Codec.Container.MKV,
                Codec.Container.MOV,
                Codec.Container.MP4,
                Codec.Container.MPG,
                Codec.Container.OGM,
                Codec.Container.OGV,
                Codec.Container.TS,
                Codec.Container.VOB,
                Codec.Container.WEBM,
                Codec.Container.WMV,
                Codec.Container.XVID,
            )

            videoCodec(
                Codec.Video.AV1,
                Codec.Video.H264,
                Codec.Video.HEVC,
                Codec.Video.MPEG,
                Codec.Video.MPEG2VIDEO,
                Codec.Video.VP8,
                Codec.Video.VP9,
            )

            audioCodec(*supportedAudioCodecs, Codec.Audio.WAV, Codec.Audio.OGG)
        }

        subtitleProfile(Codec.Subtitle.VTT, embedded = true, hls = true, external = true)
        subtitleProfile(Codec.Subtitle.WEBVTT, embedded = true, hls = true, external = true)
        subtitleProfile(Codec.Subtitle.SRT, embedded = true, external = true)
        subtitleProfile(Codec.Subtitle.SUBRIP, embedded = true, external = true)
        subtitleProfile(Codec.Subtitle.TTML, embedded = true, external = true)
        subtitleProfile(Codec.Subtitle.DVBSUB, embedded = true, encode = true)
        subtitleProfile(Codec.Subtitle.DVDSUB, embedded = true, encode = true)
        subtitleProfile(Codec.Subtitle.IDX, embedded = true, encode = true)
        subtitleProfile(Codec.Subtitle.PGS, embedded = true, encode = true)
        subtitleProfile(Codec.Subtitle.PGSSUB, embedded = true, encode = true)
        subtitleProfile(Codec.Subtitle.ASS, encode = true, embedded = true, external = true)
        subtitleProfile(Codec.Subtitle.SSA, encode = true, embedded = true, external = true)

        if (!supportsAVCHigh10) {
            codecProfile {
                type = CodecType.VIDEO
                codec = Codec.Video.H264
                conditions {
                    ProfileConditionValue.VIDEO_PROFILE inCollection MPV_H264_PROFILES_WITHOUT_HIGH10
                }
            }
        }
    }

/** H.264 profiles MPV direct-plays on a device without a Hi10P decoder: everything but "high 10" */
internal val MPV_H264_PROFILES_WITHOUT_HIGH10 = listOf("high", "main", "baseline", "constrained baseline")
