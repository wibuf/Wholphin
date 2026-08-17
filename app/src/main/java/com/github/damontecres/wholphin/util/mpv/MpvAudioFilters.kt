package com.github.damontecres.wholphin.util.mpv

import com.github.damontecres.wholphin.mpv.MPVLib
import com.github.damontecres.wholphin.preferences.MpvOptions
import timber.log.Timber

// Audio filters for listening on TV speakers.
//
// Both address things a surround mix does badly on two small speakers: dialogue sits in the centre
// channel and a naive downmix buries it under everything else, and the gap between quiet dialogue
// and loud action is wider than the speakers can usefully cover.
//
// Doing this in mpv rather than asking the server to downmix matters for more than sound quality:
// the downmix preference is part of the device profile, so turning it on makes the server transcode
// the audio. Filtering locally leaves the audio to direct play.

/**
 * Downmix to stereo weighting the centre channel up, so dialogue stays above the rest of the mix
 *
 * The surround channels come in at -3dB (0.707) while the centre goes to both sides at 0.5, which
 * is louder relative to them than a plain downmix.
 */
private const val DIALOGUE_PAN =
    "lavfi=[pan=stereo|FL=0.5*FC+0.707*FL+0.707*BL+0.5*LFE|FR=0.5*FC+0.707*FR+0.707*BR+0.5*LFE]"

/** Even out the loud and quiet parts, so explosions do not tower over speech */
private const val NIGHT_MODE = "lavfi=[dynaudnorm=f=250:g=5:p=0.5:m=15]"

/**
 * Build the mpv `af` filter chain for the enabled options, or an empty string for none
 *
 * Exposed for testing; [applyAudioFiltersToMpv] is what playback uses.
 */
fun MpvOptions.audioFilterChain(): String =
    listOfNotNull(
        DIALOGUE_PAN.takeIf { boostDialogue },
        NIGHT_MODE.takeIf { compressLoudScenes },
    ).joinToString(",")

/**
 * Apply the audio filters to a running mpv instance
 *
 * Always sets `af`, including to an empty chain, so turning an option off takes effect on the next
 * playback rather than persisting until the app restarts.
 */
fun MpvOptions.applyAudioFiltersToMpv() {
    val chain = audioFilterChain()
    Timber.i("Applying mpv audio filters: af='%s'", chain)
    MPVLib.setPropertyString("af", chain)
    // The pan filter outputs stereo, but the audio output still has to be asked for stereo or mpv
    // may hand the receiver a surround stream and let it do its own downmix instead.
    MPVLib.setPropertyString("audio-channels", if (boostDialogue) "stereo" else "auto-safe")
}
