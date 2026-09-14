package com.github.damontecres.wholphin.games.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.games.LibretroBridge
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * Full-screen native game playback: the core's video in a [SurfaceView] with a pause menu on top
 *
 * Gamepad input never reaches Compose while the game runs; the activity routes it straight to the
 * native pad. Only Back and the menu keys get here, and only to open the pause menu.
 */
@Composable
fun GamePlayerPage(
    destination: Destination.GamePlayer,
    modifier: Modifier = Modifier,
    viewModel: GamePlayerViewModel =
        hiltViewModel<GamePlayerViewModel, GamePlayerViewModel.Factory>(
            creationCallback = { it.create(destination) },
        ),
) {
    val state by viewModel.state.collectAsState()
    val bridge = viewModel.bridge

    BackHandler {
        when {
            state.overlayOpen -> viewModel.overlayBack()
            state.status is GamePlayerStatus.Running -> viewModel.openOverlay()
            else -> viewModel.exit(save = false)
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        GameSurface(
            bridge = bridge,
            modifier = Modifier.aspectRatio(state.aspect).fillMaxSize(),
        )

        when (val status = state.status) {
            is GamePlayerStatus.Loading -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                ) {
                    Box(Modifier.fillMaxHeight(0.4f))
                    CircularProgress(Modifier.size(48.dp))
                    Text(
                        text =
                            status.progress
                                ?.let { "${status.message} ${(it * 100).toInt()}%" }
                                ?: status.message,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                }
            }

            is GamePlayerStatus.Error -> {
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                    modifier = Modifier.fillMaxSize().background(Color.Black).padding(48.dp),
                ) {
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = { viewModel.exit(save = false) },
                        modifier = Modifier.focusRequester(focusRequester),
                    ) {
                        Text("Back")
                    }
                }
            }

            GamePlayerStatus.Running -> {
                state.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(24.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (state.overlayOpen) {
                    PauseMenu(state = state, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun GameSurface(
    bridge: LibretroBridge,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            bridge.onSurfaceReady(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            bridge.setSurface(holder.surface)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            bridge.onSurfaceLost()
                        }
                    },
                )
            }
        },
    )
    DisposableEffect(bridge) {
        onDispose { bridge.setSurface(null) }
    }
}

private data class MenuAction(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
private fun PauseMenu(
    state: GamePlayerState,
    viewModel: GamePlayerViewModel,
) {
    val focusRequester = remember { FocusRequester() }
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(480.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.confirmingExit -> {
                    Text(
                        text = "Exit game?",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = "Progress since the last save state will be lost unless it is saved now.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    val actions =
                        listOf(
                            MenuAction("Save and exit") { viewModel.exit(save = true) },
                            MenuAction("Exit without saving") { viewModel.exit(save = false) },
                            MenuAction("Keep playing") { viewModel.closeOverlay() },
                        )
                    MenuList(actions, focusRequester)
                }

                state.settingsOpen -> {
                    Text(
                        text = "Emulator settings",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxHeight(0.7f),
                    ) {
                        items(state.options, key = { it.id }) { option ->
                            val first = option.id == state.options.firstOrNull()?.id
                            ListItem(
                                selected = false,
                                onClick = { viewModel.cycleOption(option, 1) },
                                onLongClick = { viewModel.cycleOption(option, -1) },
                                headlineContent = { Text(option.label, maxLines = 1) },
                                supportingContent = { Text(option.current, maxLines = 1) },
                                modifier = if (first) Modifier.focusRequester(focusRequester) else Modifier,
                            )
                        }
                        item {
                            ListItem(
                                selected = false,
                                onClick = { viewModel.overlayBack() },
                                headlineContent = { Text("Back") },
                            )
                        }
                    }
                }

                else -> {
                    Text(
                        text = "Paused",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    val actions =
                        listOf(
                            MenuAction("Resume") { viewModel.closeOverlay() },
                            MenuAction("Press Start") { viewModel.pressButton(LibretroBridge.RETRO_START) },
                            MenuAction("Press Select") { viewModel.pressButton(LibretroBridge.RETRO_SELECT) },
                            MenuAction("Save state") { viewModel.saveState() },
                            MenuAction("Load state") { viewModel.loadState() },
                            MenuAction(if (state.fastForward) "Fast-forward: On" else "Fast-forward: Off") {
                                viewModel.toggleFastForward()
                            },
                            MenuAction("Restart") { viewModel.restart() },
                            MenuAction("Emulator settings") { viewModel.openSettings() },
                            MenuAction("Reset emulator settings") { viewModel.resetSettings() },
                            MenuAction("Exit") { viewModel.requestExit() },
                        )
                    MenuList(actions, focusRequester)
                }
            }
        }
    }
}

@Composable
private fun MenuList(
    actions: List<MenuAction>,
    focusRequester: FocusRequester,
) {
    LaunchedEffect(actions.size) { focusRequester.tryRequestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        actions.forEachIndexed { index, action ->
            ListItem(
                selected = false,
                onClick = action.onClick,
                headlineContent = { Text(action.label) },
                modifier = if (index == 0) Modifier.focusRequester(focusRequester) else Modifier,
            )
        }
    }
}
