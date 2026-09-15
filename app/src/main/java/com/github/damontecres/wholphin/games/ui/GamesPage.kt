package com.github.damontecres.wholphin.games.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.games.GameArtworkService
import com.github.damontecres.wholphin.games.model.GameSummary
import com.github.damontecres.wholphin.games.rememberArt
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.OneTimeLaunchedEffect
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.cards.ViewMoreCard
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.enableMarquee
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import java.io.File

@Composable
fun GamesPage(
    destination: com.github.damontecres.wholphin.ui.nav.Destination.Games,
    modifier: Modifier = Modifier,
    viewModel: GamesViewModel = hiltViewModel(),
) {
    OneTimeLaunchedEffect { viewModel.init(destination.libraryId) }
    val state by viewModel.state.collectAsState()

    when (val loading = state.loading) {
        is LoadingState.Error -> {
            ErrorMessage(loading, modifier)
        }

        LoadingState.Pending, LoadingState.Loading -> {
            LoadingPage(modifier)
        }

        LoadingState.Success -> {
            Column(modifier = modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = state.library?.name ?: stringResource(R.string.games),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = viewModel::openCores) {
                        Text(stringResource(R.string.emulator_cores))
                    }
                }
                if (state.systems.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_games_found), color = MaterialTheme.colorScheme.onBackground)
                    }
                    return@Column
                }
                // Coming back up from the grid lands on the tab that was selected, not the first
                // one, and the page opens with the tab row focused so the grid never steals it
                val tabFocusRequesters = remember(state.systems) { List(state.systems.size) { FocusRequester() } }
                LaunchedEffect(Unit) { tabFocusRequesters.firstOrNull()?.tryRequestFocus() }
                TabRow(
                    selectedTabIndex = state.selectedSystem,
                    modifier =
                        Modifier
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                            .focusRestorer(tabFocusRequesters[state.selectedSystem.coerceIn(tabFocusRequesters.indices)]),
                ) {
                    state.systems.forEachIndexed { index, system ->
                        Tab(
                            selected = index == state.selectedSystem,
                            onFocus = { viewModel.selectSystem(index) },
                            modifier = Modifier.focusRequester(tabFocusRequesters[index]),
                        ) {
                            Text(
                                text = "${system.name} (${system.gameCount})",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                val system = state.systems[state.selectedSystem]
                val games = state.games[system.id]
                if (games == null) {
                    LoadingPage(Modifier.fillMaxSize(), focusEnabled = false)
                } else {
                    GameGrid(
                        libraryId = state.library?.id ?: "",
                        games = games,
                        artwork = viewModel.artwork,
                        onClick = viewModel::open,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun GameGrid(
    libraryId: String,
    games: List<GameSummary>,
    artwork: GameArtworkService,
    onClick: (GameSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        modifier = modifier.focusRestorer(),
    ) {
        items(games, key = { it.id }) { game ->
            val art by artwork.rememberArt(libraryId, game.id)
            GameCard(
                title = game.title,
                art = art,
                onClick = { onClick(game) },
            )
        }
    }
}

/**
 * A games row for the home page: box art at the row's height, newest first
 */
@Composable
fun GamesHomeRow(
    row: HomeRowLoadingState.Games,
    onFocusPosition: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GamesRowViewModel = hiltViewModel(),
) {
    val height = row.viewOptions.heightDp.dp
    ItemRow(
        title = row.title.getString(),
        items = row.games,
        onClickItem = { _, game -> viewModel.open(row.libraryId, game) },
        onLongClickItem = { _, _ -> },
        modifier = modifier,
        horizontalPadding = row.viewOptions.spacing.dp,
        cardContent = { index, game, cardModifier, onClick, _ ->
            if (game != null) {
                val art by viewModel.artwork.rememberArt(row.libraryId, game.id)
                GameCard(
                    title = game.title,
                    art = art,
                    onClick = onClick,
                    showTitle = row.viewOptions.showTitles,
                    modifier =
                        cardModifier
                            .width(height * AspectRatios.TALL)
                            .onFocusChanged { if (it.isFocused) onFocusPosition(index) },
                )
            }
        },
        showViewMore = row.showViewMore,
        viewMoreCardContent = { mod ->
            ViewMoreCard(
                onClick = { viewModel.openLibrary(row.libraryId) },
                onLongClick = {},
                size = DpSize(width = height * AspectRatios.TALL, height = height),
                showTitle = row.viewOptions.showTitles,
                modifier = mod,
            )
        },
    )
}

/** Box art with the title under it, in the shape of the library grid cards */
@Composable
fun GameCard(
    title: String,
    art: File?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focused by interactionSource.collectIsFocusedAsState()
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        Card(
            onClick = onClick,
            interactionSource = interactionSource,
            colors = CardDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(AspectRatios.TALL)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // Art is still on its way from the server, so the title stands in
                    Text(
                        text = title,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        if (showTitle) {
            Text(
                text = title,
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .enableMarquee(focused),
            )
            Spacer(Modifier.padding(2.dp))
        }
    }
}
