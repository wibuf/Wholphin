package com.github.damontecres.wholphin.games.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.games.CoreDownloadService
import com.github.damontecres.wholphin.games.GameCore
import com.github.damontecres.wholphin.games.GameCores
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class CoreRow(
    val core: GameCore,
    val installed: Boolean,
    val available: Boolean,
    val progress: Float? = null,
    val error: String? = null,
)

/**
 * Lets the user download and remove libretro cores, so games play natively
 */
@HiltViewModel
class GameCoresViewModel
    @Inject
    constructor(
        private val cores: CoreDownloadService,
    ) : ViewModel() {
        private val _state = MutableStateFlow(rows())
        val state: StateFlow<List<CoreRow>> = _state

        private fun rows() =
            GameCores.catalog.map {
                CoreRow(core = it, installed = cores.isInstalled(it.coreId), available = cores.isAvailable(it))
            }

        private fun updateRow(
            coreId: String,
            block: (CoreRow) -> CoreRow,
        ) = _state.update { list -> list.map { if (it.core.coreId == coreId) block(it) else it } }

        fun toggle(row: CoreRow) {
            if (row.progress != null || !row.available) return
            if (row.installed) {
                cores.remove(row.core)
                updateRow(row.core.coreId) { it.copy(installed = false) }
                return
            }
            updateRow(row.core.coreId) { it.copy(progress = 0f, error = null) }
            viewModelScope.launch {
                try {
                    cores.download(row.core) { progress -> updateRow(row.core.coreId) { it.copy(progress = progress) } }
                    updateRow(row.core.coreId) { it.copy(progress = null, installed = true) }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Could not download core %s", row.core.coreId)
                    updateRow(row.core.coreId) { it.copy(progress = null, error = ex.message ?: "Download failed") }
                }
            }
        }
    }

@Composable
fun GameCoresPage(
    modifier: Modifier = Modifier,
    viewModel: GameCoresViewModel = hiltViewModel(),
) {
    val rows by viewModel.state.collectAsState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Text(
            text = stringResource(R.string.emulator_cores),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.emulator_cores_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(rows, key = { _, row -> row.core.coreId }) { index, row ->
                val status =
                    when {
                        row.progress != null -> stringResource(R.string.game_core_row_downloading, (row.progress * 100).toInt())
                        row.error != null -> row.error
                        !row.available -> stringResource(R.string.game_core_unavailable)
                        row.installed -> stringResource(R.string.game_core_installed)
                        else -> stringResource(R.string.game_core_not_installed, row.core.approxSizeMb)
                    }
                ListItem(
                    selected = row.installed,
                    onClick = { viewModel.toggle(row) },
                    enabled = row.available,
                    headlineContent = { Text(row.core.systemName) },
                    supportingContent = { Text(status) },
                    trailingContent = { Text(row.core.coreId) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                )
            }
        }
    }
}
