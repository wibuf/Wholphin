package com.github.damontecres.wholphin.games.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.games.model.GameDetail
import com.github.damontecres.wholphin.games.model.GameSummary
import com.github.damontecres.wholphin.ui.components.TitleOrLogo

/**
 * The home page header for a focused game, in place of the movie/show details: title, a line of
 * facts (year, system, genre, players) and the description, as far as the server knows them.
 * The title shows straight away; the rest fills in when the game's details arrive.
 */
@Composable
fun GameHomeHeader(
    libraryId: String,
    game: GameSummary,
    modifier: Modifier = Modifier,
    viewModel: GamesRowViewModel = hiltViewModel(),
) {
    val detail by produceState<GameDetail?>(null, libraryId, game.id) {
        value = viewModel.detail(libraryId, game.id)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        TitleOrLogo(
            title = detail?.title?.takeIf { it.isNotBlank() } ?: game.title,
            logoImageUrl = null,
            showLogo = false,
            modifier = Modifier.fillMaxWidth(.75f),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(.6f),
        ) {
            Text(
                text = gameFacts(game, detail),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val overviewModifier = Modifier.height(60.dp).width(400.dp)
            val overview = detail?.overview
            if (!overview.isNullOrBlank()) {
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = overviewModifier,
                )
            } else {
                Spacer(overviewModifier)
            }
        }
    }
}

/** "1992 · SNES · Platformer · 1-2 players", leaving out whatever isn't known */
fun gameFacts(
    game: GameSummary,
    detail: GameDetail?,
): String =
    listOfNotNull(
        detail?.year?.takeIf { it > 0 }?.toString(),
        (detail?.system?.takeIf { it.isNotBlank() } ?: game.system).takeIf { it.isNotBlank() },
        detail?.genre?.takeIf { it.isNotBlank() },
        detail?.players?.takeIf { it > 0 }?.let { if (it == 1) "1 player" else "1-$it players" },
    ).joinToString(" · ")
