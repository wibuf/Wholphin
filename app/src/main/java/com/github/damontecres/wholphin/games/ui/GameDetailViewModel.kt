package com.github.damontecres.wholphin.games.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.games.CoreDownloadService
import com.github.damontecres.wholphin.games.GameCore
import com.github.damontecres.wholphin.games.GameCores
import com.github.damontecres.wholphin.games.MoonbaseGamesService
import com.github.damontecres.wholphin.games.model.GameDetail
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.LoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class GameDetailState(
    val loading: LoadingState = LoadingState.Pending,
    val detail: GameDetail? = null,
    /** The libretro core that plays this game, or null when nothing here can */
    val core: GameCore? = null,
    val coreInstalled: Boolean = false,
    val coreAvailable: Boolean = false,
    /** 0..1 while the core downloads */
    val downloadProgress: Float? = null,
    val downloadError: String? = null,
)

@HiltViewModel(assistedFactory = GameDetailViewModel.Factory::class)
class GameDetailViewModel
    @AssistedInject
    constructor(
        @param:Assisted private val destination: Destination.GameDetail,
        val games: MoonbaseGamesService,
        private val cores: CoreDownloadService,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(destination: Destination.GameDetail): GameDetailViewModel
        }

        private val _state = MutableStateFlow(GameDetailState())
        val state: StateFlow<GameDetailState> = _state

        val libraryId get() = destination.libraryId

        init {
            _state.update { it.copy(loading = LoadingState.Loading) }
            viewModelScope.launch {
                try {
                    val detail = games.game(destination.libraryId, destination.gameId)
                    if (detail == null) {
                        _state.update { it.copy(loading = LoadingState.Error("Game not found")) }
                        return@launch
                    }
                    val core = GameCores.forServerCore(detail.core)
                    _state.update {
                        it.copy(
                            loading = LoadingState.Success,
                            detail = detail,
                            core = core,
                            coreInstalled = core != null && cores.isInstalled(core.coreId),
                            coreAvailable = core != null && cores.isAvailable(core),
                        )
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Could not load game")
                    _state.update { it.copy(loading = LoadingState.Error("Could not load game", ex)) }
                }
            }
        }

        fun play(startFresh: Boolean) {
            val detail = state.value.detail ?: return
            navigationManager.navigateTo(
                Destination.GamePlayer(
                    libraryId = destination.libraryId,
                    gameId = detail.id,
                    serverCore = detail.core,
                    startFresh = startFresh,
                ),
            )
        }

        fun downloadCore() {
            val core = state.value.core ?: return
            if (state.value.downloadProgress != null) return
            _state.update { it.copy(downloadProgress = 0f, downloadError = null) }
            viewModelScope.launch {
                try {
                    cores.download(core) { progress -> _state.update { it.copy(downloadProgress = progress) } }
                    _state.update { it.copy(downloadProgress = null, coreInstalled = true) }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Could not download core %s", core.coreId)
                    _state.update { it.copy(downloadProgress = null, downloadError = ex.message ?: "Download failed") }
                }
            }
        }
    }
