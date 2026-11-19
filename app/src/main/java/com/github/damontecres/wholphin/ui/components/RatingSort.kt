package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.PluginRatings
import com.github.damontecres.wholphin.services.RatingScore
import com.github.damontecres.wholphin.services.RatingSortSource
import com.github.damontecres.wholphin.ui.FontAwesome
import java.util.UUID

/**
 * Fork-only (GooseFlix #1671): the library's "Rating" sort, when the GooseFlix plugin serves ratings.
 *
 * Provided by the library screen through [LocalRatingSort]. When present, the sort menu shows one
 * "Rating" entry in place of Community and Critic rating; picking it shows [RatingSourceButton] to
 * choose the source, and each card shows its score ([RatingBadge]) while sorted by rating.
 */
data class RatingSortUi(
    val source: RatingSortSource,
    /** Sorted by rating right now (else the badges stay hidden) */
    val active: Boolean,
    val scores: Map<UUID, RatingScore>,
    val onSourceChange: (RatingSortSource) -> Unit,
)

val LocalRatingSort = compositionLocalOf<RatingSortUi?> { null }

/** The library's rating sort as the view model keeps it: the chosen source and the loaded scores */
data class RatingSortState(
    val source: RatingSortSource,
    val scores: Map<UUID, RatingScore> = emptyMap(),
)

/** The rating source picker next to the sort button */
@Composable
fun RatingSourceButton(
    ui: RatingSortUi,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(onClick = { expanded = true }) {
            Text(text = ui.source.label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            RatingSortSource.entries.forEach { source ->
                TvDropdownMenuItem(
                    leadingIcon = {
                        if (source == ui.source) {
                            Text(text = stringResource(R.string.fa_check), fontFamily = FontAwesome)
                        }
                    },
                    text = { Text(text = source.label) },
                    onClick = {
                        expanded = false
                        if (source != ui.source) ui.onSourceChange(source)
                    },
                )
            }
        }
    }
}

/** A card's score, at the bottom left of its image, while the library is sorted by rating */
@Composable
fun BoxScope.RatingBadge(itemId: UUID?) {
    val ui = LocalRatingSort.current ?: return
    if (!ui.active || itemId == null) return
    val score = ui.scores[itemId] ?: return
    Text(
        text = PluginRatings.label(ui.source, score),
        style = MaterialTheme.typography.labelSmall,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
