package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.data.model.MdbListRatingsResponse
import com.github.damontecres.wholphin.data.model.MdbListSource
import com.github.damontecres.wholphin.preferences.DisplayToggle
import com.github.damontecres.wholphin.ui.LocalMdbListRatingsService
import com.github.damontecres.wholphin.ui.util.LocalInterfaceCustomization
import org.jellyfin.sdk.model.UUID
import java.util.Locale

/** Which toggle turns each source on, in the order they are shown */
private val SOURCE_TOGGLES =
    listOf(
        MdbListSource.IMDB to DisplayToggle.MDBLIST_IMDB,
        MdbListSource.TOMATOES to DisplayToggle.MDBLIST_TOMATOES,
        MdbListSource.POPCORN to DisplayToggle.MDBLIST_POPCORN,
        MdbListSource.TMDB to DisplayToggle.MDBLIST_TMDB,
    )

/** Sized to sit on the metadata line without pushing its height around */
private val ICON_HEIGHT = 18.dp

/** The same spacing and bullet the rest of the metadata line uses */
private const val SEPARATOR = "  \u2022  "

/**
 * The rating for a source in that source's own scale, or null when there is nothing to show
 *
 * The plugin returns some sources bare, eg {"source":"letterboxd"} with no numbers at all, and
 * others with votes but no rating. Where only the normalised score is present it has to be
 * converted for IMDb, because score is always 0-100 while IMDb's own scale is out of ten: taking
 * the score as-is would render 8.9 as 89.
 */
fun MdbListRatingsResponse.valueFor(source: MdbListSource): Double? {
    val rating = forSource(source) ?: return null
    return rating.value
        ?: rating.score?.let { if (source == MdbListSource.IMDB) it / 10.0 else it }
}

/** How a source writes its own numbers: IMDb and TMDB out of ten, Rotten Tomatoes as percentages */
fun formatRating(
    source: MdbListSource,
    value: Double,
): String =
    when (source) {
        MdbListSource.IMDB -> String.format(Locale.getDefault(), "%.1f", value)
        MdbListSource.TMDB -> String.format(Locale.getDefault(), "%.1f", value / 10.0)
        MdbListSource.TOMATOES, MdbListSource.POPCORN -> "${value.toInt()}%"
    }

/**
 * Ratings from the MDBList Ratings server plugin, continuing the metadata line
 *
 * Emitted as children of the caller's Row so they sit alongside year, runtime and age rating,
 * separated the same way, rather than forming a row of their own.
 *
 * Renders nothing at all when the plugin is absent, when it has nothing cached for this item, or
 * when no sources are enabled, so a server without the plugin looks exactly as it did before.
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
    val response = ratings ?: return
    val shown = wanted.mapNotNull { (source, _) -> response.valueFor(source)?.let { source to it } }
    if (shown.isEmpty()) {
        return
    }
    shown.forEach { (source, value) ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier,
        ) {
            // Matches the separator the rest of the line uses
            Text(
                text = SEPARATOR,
                color = MaterialTheme.colorScheme.onSurface,
                style = textStyle,
                maxLines = 1,
            )
            AsyncImage(
                model = service.iconUrl(source, value),
                contentDescription = source.key,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(ICON_HEIGHT),
            )
            Text(
                text = formatRating(source, value),
                color = MaterialTheme.colorScheme.onSurface,
                style = textStyle,
                maxLines = 1,
            )
        }
    }
}
