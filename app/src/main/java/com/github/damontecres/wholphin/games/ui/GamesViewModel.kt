package com.github.damontecres.wholphin.games.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.games.MoonbaseGamesService
import com.github.damontecres.wholphin.games.model.GameLibrary
import com.github.damontecres.wholphin.games.model.GameSummary
import com.github.damontecres.wholphin.games.model.GameSystem
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class GamesPageState(
    val loading: LoadingState = LoadingState.Pending,
    val libraries: List<GameLibrary> = emptyList(),
    val library: GameLibrary? = null,
    val systems: List<GameSystem> = emptyList(),
    val selectedSystem: Int = 0,
    /** Games per system id, filled lazily as tabs are opened */
    val games: Map<String, List<GameSummary>> = emptyMap(),
    val loadingGames: Boolean = false,
)

/**
 * Browses a Moonbase games library: one tab per system, a grid of games under it
 */
@HiltViewModel
class GamesViewModel
    @Inject
    constructor(
        val games: MoonbaseGamesService,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow(GamesPageState())
        val state: StateFlow<GamesPageState> = _state

        fun init(libraryId: String?) {
            if (state.value.loading != LoadingState.Pending) return
            _state.update { it.copy(loading = LoadingState.Loading) }
            viewModelScope.launch {
                try {
                    val libraries = games.libraries(refresh = true)
                    val library = libraries.firstOrNull { it.id == libraryId } ?: libraries.firstOrNull()
                    if (library == null) {
                        _state.update {
                            it.copy(
                                loading =
                                    LoadingState.Error(
                                        "No game libraries found. " +
                                            "Enable retro games in the Moonbase plugin settings.",
                                    ),
                            )
                        }
                        return@launch
                    }
                    val systems = games.systems(library.id).filter { it.gameCount > 0 }
                    _state.update {
                        it.copy(loading = LoadingState.Success, libraries = libraries, library = library, systems = systems)
                    }
                    systems.firstOrNull()?.let { loadGames(library.id, it.id) }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Could not load games")
                    _state.update { it.copy(loading = LoadingState.Error("Could not load games", ex)) }
                }
            }
        }

        fun selectSystem(index: Int) {
            val s = state.value
            val system = s.systems.getOrNull(index) ?: return
            _state.update { it.copy(selectedSystem = index) }
            val library = s.library ?: return
            if (system.id !in s.games) loadGames(library.id, system.id)
        }

        private fun loadGames(
            libraryId: String,
            systemId: String,
        ) {
            _state.update { it.copy(loadingGames = true) }
            viewModelScope.launch {
                try {
                    val list = games.games(libraryId, systemId).sortedBy { it.title.lowercase() }
                    _state.update { it.copy(games = it.games + (systemId to list), loadingGames = false) }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Could not load games for %s", systemId)
                    _state.update { it.copy(games = it.games + (systemId to emptyList()), loadingGames = false) }
                }
            }
        }

        fun open(game: GameSummary) {
            val library = state.value.library ?: return
            navigationManager.navigateTo(Destination.GameDetail(library.id, game.id))
        }

        fun openCores() = navigationManager.navigateTo(Destination.GameCores)
    }
