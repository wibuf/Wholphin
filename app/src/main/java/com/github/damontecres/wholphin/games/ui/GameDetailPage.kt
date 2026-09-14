package com.github.damontecres.wholphin.games.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.TitleValueText
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.LoadingState

@Composable
fun GameDetailPage(
    destination: Destination.GameDetail,
    modifier: Modifier = Modifier,
    viewModel: GameDetailViewModel =
        hiltViewModel<GameDetailViewModel, GameDetailViewModel.Factory>(
            creationCallback = { it.create(destination) },
        ),
) {
    val state by viewModel.state.collectAsState()
    when (val loading = state.loading) {
        is LoadingState.Error -> {
            ErrorMessage(loading, modifier)
        }

        LoadingState.Pending, LoadingState.Loading -> {
            LoadingPage(modifier)
        }

        LoadingState.Success -> {
            val detail = state.detail ?: return
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }

            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                modifier = modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
            ) {
                AsyncImage(
                    model = viewModel.games.thumbUrl(viewModel.libraryId, detail.id),
                    contentDescription = detail.title,
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier
                            .width(260.dp)
                            .aspectRatio(AspectRatios.TALL)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = detail.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta =
                        listOfNotNull(
                            state.core?.systemName ?: detail.system,
                            detail.year?.toString(),
                            detail.genre,
                            detail.players?.let { "$it players" },
                            detail.region,
                        )
                    Text(
                        text = meta.joinToString("  •  "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )

                    val core = state.core
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            core == null -> {
                                Text(
                                    text = stringResource(R.string.game_system_unsupported, detail.core),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }

                            state.coreInstalled -> {
                                Button(
                                    onClick = { viewModel.play(startFresh = false) },
                                    modifier = Modifier.focusRequester(focusRequester),
                                ) {
                                    Text(stringResource(R.string.play))
                                }
                                Button(onClick = { viewModel.play(startFresh = true) }) {
                                    Text(stringResource(R.string.game_start_fresh))
                                }
                            }

                            state.downloadProgress != null -> {
                                Text(
                                    text =
                                        stringResource(
                                            R.string.game_core_downloading,
                                            core.systemName,
                                            (state.downloadProgress!! * 100).toInt(),
                                        ),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }

                            state.coreAvailable -> {
                                Button(
                                    onClick = viewModel::downloadCore,
                                    modifier = Modifier.focusRequester(focusRequester),
                                ) {
                                    Text(stringResource(R.string.game_download_core, core.systemName, core.approxSizeMb))
                                }
                            }

                            else -> {
                                Text(
                                    text = stringResource(R.string.game_core_unavailable),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    }
                    state.downloadError?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                    }

                    detail.overview?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    detail.developer?.let { TitleValueText(stringResource(R.string.game_developer), it) }
                    detail.publisher?.let { TitleValueText(stringResource(R.string.game_publisher), it) }
                    detail.franchise?.let { TitleValueText(stringResource(R.string.game_franchise), it) }
                    TitleValueText(stringResource(R.string.game_file), detail.fileName)
                    if (detail.bios.isNotEmpty()) {
                        TitleValueText(stringResource(R.string.game_bios), detail.bios.joinToString { it.fileName })
                    }
                }
            }
        }
    }
}
