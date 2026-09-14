package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.tv.material3.MaterialTheme
import com.github.damontecres.wholphin.data.model.MdbListRatingsResponse
import com.github.damontecres.wholphin.data.model.MdbListSource
import com.github.damontecres.wholphin.preferences.DisplayToggle
import com.github.damontecres.wholphin.ui.LocalMdbListRatingsService
import com.github.damontecres.wholphin.ui.dot
import com.github.damontecres.wholphin.ui.util.LocalInterfaceCustomization
import org.jellyfin.sdk.model.UUID
import java.util.Locale

/** Which toggle turns each source on, and therefore which sources can appear at all */
private val SOURCE_TOGGLES =
    listOf(
        MdbListSource.IMDB to DisplayToggle.MDBLIST_IMDB,
        MdbListSource.TOMATOES to DisplayToggle.MDBLIST_TOMATOES,
        MdbListSource.POPCORN to DisplayToggle.MDBLIST_POPCORN,
        MdbListSource.TMDB to DisplayToggle.MDBLIST_TMDB,
    )

/**
 * Format one source's rating the way the existing Jellyfin ratings are formatted
 *
 * IMDb is out of ten and keeps a decimal; the rest are percentages. Rotten Tomatoes gets the fresh
 * or rotten icon on the same 60% boundary the critic rating already uses, so the two cannot
 * disagree about what counts as fresh.
 *
 * Returns null when the plugin has no value for that source, so nothing renders rather than an
 * empty gap.
 */
fun MdbListRatingsResponse.annotatedFor(source: MdbListSource): AnnotatedString? {
    val rating = forSource(source) ?: return null
    // The plugin returns some sources with no numbers at all, eg {"source":"letterboxd"}, and
    // others with votes but no rating. Both mean there is nothing to show.
    //
    // Where only the normalised score is present it has to be converted, because score is always
    // 0-100 while IMDb's own scale is out of ten. Taking score as-is would render 8.9 as 89.
    val value =
        rating.value
            ?: rating.score?.let { if (source == MdbListSource.IMDB) it / 10.0 else it }
            ?: return null
    return buildAnnotatedString {
        dot()
        when (source) {
            MdbListSource.IMDB -> {
                append(String.format(Locale.getDefault(), "%.1f", value))
                appendInlineContent(id = "star")
            }

            MdbListSource.TOMATOES, MdbListSource.POPCORN -> {
                append("${value.toInt()}%")
                if (value >= FRESH_THRESHOLD) {
                    appendInlineContent(id = "fresh")
                } else {
                    appendInlineContent(id = "rotten")
                }
            }

            MdbListSource.TMDB -> {
                append("${value.toInt()}%")
                appendInlineContent(id = "star")
            }
        }
    }
}

/** Rotten Tomatoes calls 60% and above fresh, matching the existing critic rating */
private const val FRESH_THRESHOLD = 60.0

/**
 * Extra ratings for an item, from the MDBList Ratings server plugin
 *
 * Renders nothing at all when the plugin is not installed, when it has nothing cached for this
 * item, or when none of the sources are enabled, so a server without the plugin looks exactly as
 * it did before.
 */
@Composable
fun MdbListRatings(
    itemId: UUID?,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.titleSmall,
) {
    val enabled = LocalInterfaceCustomization.current.enabledDisplayToggles
    val wanted = remember(enabled) { SOURCE_TOGGLES.filter { it.second in enabled } }
    if (itemId == null || wanted.isEmpty()) {
        return
    }
    val service = LocalMdbListRatingsService.current
    var ratings by remember(itemId) { mutableStateOf<MdbListRatingsResponse?>(null) }
    LaunchedEffect(itemId) {
        ratings = service.getRatings(itemId)
    }
    val inlineContentMap = rememberQuickDetailsContentMap(textStyle)
    ratings?.let { response ->
        wanted.forEach { (source, _) ->
            QuickDetailsText(
                response.annotatedFor(source),
                modifier,
                textStyle,
                inlineContentMap,
            )
        }
    }
}
