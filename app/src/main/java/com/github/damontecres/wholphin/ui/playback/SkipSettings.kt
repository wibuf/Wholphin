package com.github.damontecres.wholphin.ui.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.damontecres.wholphin.preferences.SkipSegmentBehavior
import com.github.damontecres.wholphin.ui.playback.overlay.BottomDialog
import com.github.damontecres.wholphin.ui.playback.overlay.BottomDialogItem
import org.jellyfin.sdk.model.api.MediaSegmentType

/**
 * Fork-only (GooseFlix): the player's Settings → Skipping, choosing per show whether intros,
 * recaps, credits, previews and ads are skipped automatically, offered with a Skip button, or left.
 */
data class SkipSettingsUi(
    /** The segment types this item has (all of them when it has none marked) */
    val types: List<MediaSegmentType>,
    /** The global Skip settings */
    val defaults: Map<MediaSegmentType, SkipSegmentBehavior>,
    /** This show's own choices */
    val overrides: Map<MediaSegmentType, SkipSegmentBehavior>,
    /** Null clears the show's choice for that type, back to the global setting */
    val onChange: (MediaSegmentType, SkipSegmentBehavior?) -> Unit,
) {
    fun effective(type: MediaSegmentType): SkipSegmentBehavior = overrides[type] ?: defaults[type] ?: SkipSegmentBehavior.IGNORE

    /** "Intro: auto · Credits: ask", for the Settings menu */
    val summary: String
        get() = types.joinToString(" · ") { "${label(it)}: ${shortLabel(effective(it))}" }
}

val LocalSkipSettings = compositionLocalOf<SkipSettingsUi?> { null }

val SKIPPABLE_TYPES =
    listOf(
        MediaSegmentType.INTRO,
        MediaSegmentType.RECAP,
        MediaSegmentType.OUTRO,
        MediaSegmentType.PREVIEW,
        MediaSegmentType.COMMERCIAL,
    )

fun label(type: MediaSegmentType): String =
    when (type) {
        MediaSegmentType.INTRO -> "Intro"
        MediaSegmentType.RECAP -> "Recap"
        MediaSegmentType.OUTRO -> "Credits"
        MediaSegmentType.PREVIEW -> "Preview"
        MediaSegmentType.COMMERCIAL -> "Ads"
        MediaSegmentType.UNKNOWN -> "Other"
    }

fun label(behavior: SkipSegmentBehavior): String =
    when (behavior) {
        SkipSegmentBehavior.AUTO_SKIP -> "Auto skip"
        SkipSegmentBehavior.ASK_TO_SKIP -> "Ask (Skip button)"
        else -> "Don't skip"
    }

private fun shortLabel(behavior: SkipSegmentBehavior): String =
    when (behavior) {
        SkipSegmentBehavior.AUTO_SKIP -> "auto"
        SkipSegmentBehavior.ASK_TO_SKIP -> "ask"
        else -> "off"
    }

/**
 * The Skipping dialog: the item's segment types with their current choice; picking one offers
 * auto skip, ask, don't skip, or the global setting. Back from the choices returns to the list.
 */
@Composable
fun SkipSettingsDialog(
    ui: SkipSettingsUi,
    onDismissRequest: () -> Unit,
    gravity: Int,
) {
    var picking by remember { mutableStateOf<MediaSegmentType?>(null) }
    // BottomDialog closes itself before reporting a selection. Picking a type must open its choices,
    // not close the menu, so a close only counts if no selection followed it in the same click
    // (back, or pressing outside)
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(closing) {
        if (closing) onDismissRequest()
    }
    val type = picking
    if (type == null) {
        val choices =
            ui.types.map {
                val own = ui.overrides[it]
                BottomDialogItem(
                    data = it,
                    headline = label(it),
                    supporting = if (own != null) "${label(own)} · this show" else "${label(ui.effective(it))} · your default",
                )
            }
        BottomDialog(
            choices = choices,
            onDismissRequest = { closing = true },
            onSelectChoice = { _, choice ->
                closing = false
                picking = choice.data
            },
            gravity = gravity,
        )
    } else {
        val default = ui.defaults[type] ?: SkipSegmentBehavior.IGNORE
        val choices =
            listOf(SkipSegmentBehavior.AUTO_SKIP, SkipSegmentBehavior.ASK_TO_SKIP, SkipSegmentBehavior.IGNORE).map {
                BottomDialogItem<SkipSegmentBehavior?>(data = it, headline = label(it), supporting = "${label(type)}, for this show")
            } + BottomDialogItem<SkipSegmentBehavior?>(data = null, headline = "Use my default", supporting = label(default))
        BottomDialog(
            choices = choices,
            currentChoice = choices.firstOrNull { it.data == ui.overrides[type] },
            onDismissRequest = { picking = null },
            onSelectChoice = { _, choice ->
                ui.onChange(type, choice.data)
                picking = null
            },
            gravity = gravity,
        )
    }
}
