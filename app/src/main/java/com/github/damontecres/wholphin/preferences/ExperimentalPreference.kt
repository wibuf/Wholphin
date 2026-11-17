package com.github.damontecres.wholphin.preferences

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.preferences.PreferenceGroup
import com.github.damontecres.wholphin.ui.preferences.PreferenceScreenOption
import com.github.damontecres.wholphin.ui.preferences.PreferenceValidation

object ExperimentalPreference {
    val Enable =
        AppSwitchPreference<AppPreferences>(
            title = R.string.experimental_settings,
            defaultValue = false,
            getter = { it.experimentalPreferences.enabled },
            setter = { prefs, value ->
                prefs.updateExperimentalPreferences { enabled = value }
            },
            summaryOn = R.string.enabled,
            summaryOff = R.string.disabled,
        )

    val ExperimentalSettings =
        AppDestinationPreference<AppPreferences>(
            title = R.string.experimental_settings,
            destination = Destination.Settings(PreferenceScreenOption.EXPERIMENTAL),
        )

    val VideoTunneling =
        AppSwitchPreference<AppPreferences>(
            title = R.string.video_tunneling,
            defaultValue = false,
            getter = { it.experimentalPreferences.videoTunnelingEnabled },
            setter = { prefs, value ->
                prefs.updateExperimentalPreferences { videoTunnelingEnabled = value }
            },
            summaryOn = R.string.enabled,
            summaryOff = R.string.disabled,
        )

    val PreferDolbyVisionOverHdr10Plus =
        AppSwitchPreference<AppPreferences>(
            title = R.string.prefer_dovi_over_hdr10_plus,
            defaultValue = false,
            getter = { it.experimentalPreferences.preferDolbyVisionOverHdr10Plus },
            setter = { prefs, value ->
                prefs.updateExperimentalPreferences { preferDolbyVisionOverHdr10Plus = value }
            },
            summary = R.string.prefer_dovi_over_hdr10_plus_summary,
        )

    val ConvertDolbyVisionProfile7 =
        AppSwitchPreference<AppPreferences>(
            title = R.string.convert_dovi_profile_7,
            defaultValue = false,
            getter = { it.experimentalPreferences.convertDolbyVisionProfile7 },
            setter = { prefs, value ->
                prefs.updateExperimentalPreferences { convertDolbyVisionProfile7 = value }
            },
            summary = R.string.convert_dovi_profile_7_summary,
        )

    val PreferAc3ForSurround =
        AppSwitchPreference<AppPreferences>(
            title = R.string.prefer_ac3_for_surround,
            defaultValue = false,
            getter = { it.experimentalPreferences.preferAc3Surround },
            setter = { prefs, value ->
                prefs.updateExperimentalPreferences {
                    preferAc3Surround = value
                }
            },
            summary = R.string.prefer_ac3_for_surround_summary,
            validator = { prefs, value ->
                prefs.playbackPreferences.overrides.let {
                    if (value && !it.ac3Supported) {
                        PreferenceValidation.Invalid("AC3 support is not enabled")
                    } else if (value && it.downmixStereo) {
                        PreferenceValidation.Invalid("Always downmixing to stereo")
                    } else {
                        PreferenceValidation.Valid
                    }
                }
            },
        )

    /** Fork (GooseFlix): see ui/playback/NextEpisodePreload.kt */
    val PreloadNextEpisode =
        AppSwitchPreference<AppPreferences>(
            title = R.string.preload_next_episode,
            defaultValue = false,
            getter = { it.experimentalPreferences.preloadNextEpisode },
            setter = { prefs, value ->
                prefs.updateExperimentalPreferences { preloadNextEpisode = value }
            },
            summary = R.string.preload_next_episode_summary,
        )

    val DisableAudioOffload =
        AppSwitchPreference<AppPreferences>(
            title = R.string.disable_audio_offload,
            defaultValue = false,
            getter = { it.experimentalPreferences.disableAudioOffload },
            setter = { prefs, value ->
                prefs.updateExperimentalPreferences { disableAudioOffload = value }
            },
            summary = R.string.disable_audio_offload_summary,
        )
}

val experimentalPreferences =
    buildList {
        add(
            PreferenceGroup(
                title = R.string.experimental_settings,
                preferences =
                    buildList {
                        add(ExperimentalPreference.VideoTunneling)
                        add(ExperimentalPreference.PreferDolbyVisionOverHdr10Plus)
                        add(ExperimentalPreference.ConvertDolbyVisionProfile7)
                        add(ExperimentalPreference.PreferAc3ForSurround)
                        add(ExperimentalPreference.DisableAudioOffload)
                        if (com.github.damontecres.wholphin.BuildConfig.GOOSEFLIX) add(ExperimentalPreference.PreloadNextEpisode)
                    },
            ),
        )
    }

/**
 * Get a value from [ExperimentalPreference] or null if not enabled
 */
fun <T> ExperimentalPreferences.get(block: ExperimentalPreferences.() -> T): T? = if (enabled) block.invoke(this) else null

fun ExperimentalPreferences.enabled(block: ExperimentalPreferences.() -> Boolean): Boolean = enabled && block.invoke(this)
