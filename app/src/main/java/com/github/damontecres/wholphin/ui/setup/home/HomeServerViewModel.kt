package com.github.damontecres.wholphin.ui.setup.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Adds the [HomeServer] to the app and moves on to signing in, in place of the server list.
 */
@HiltViewModel
class HomeServerViewModel
    @Inject
    constructor(
        private val jellyfin: Jellyfin,
        private val serverRepository: ServerRepository,
        private val serverDao: JellyfinServerDao,
        private val setupNavigationManager: SetupNavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow<LoadingState>(LoadingState.Pending)
        val state: StateFlow<LoadingState> = _state

        fun connect() {
            if (_state.value == LoadingState.Loading) return
            _state.value = LoadingState.Loading
            viewModelScope.launchIO {
                try {
                    val info by
                        jellyfin
                            .createApi(
                                HomeServer.url,
                                httpClientOptions =
                                    HttpClientOptions(
                                        requestTimeout = 10.seconds,
                                        connectTimeout = 10.seconds,
                                        socketTimeout = 10.seconds,
                                    ),
                            ).systemApi
                            .getPublicSystemInfo()
                    val id =
                        info.id?.toUUIDOrNull()
                            ?: throw IllegalStateException("The server did not say who it is")
                    // A device that already knows this server, say by its LAN address, keeps that
                    // address and its signed-in users
                    val known = serverDao.getServer(id)?.server
                    val server =
                        known?.copy(name = info.serverName, version = info.version)
                            ?: JellyfinServer(
                                id = id,
                                name = info.serverName,
                                url = HomeServer.url,
                                version = info.version,
                            )
                    serverRepository.addAndChangeServer(server)
                    _state.value = LoadingState.Success
                    setupNavigationManager.navigateTo(SetupDestination.UserList(server))
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not reach the home server %s", HomeServer.url)
                    _state.value = LoadingState.Error(ex.localizedMessage, ex)
                }
            }
        }
    }
