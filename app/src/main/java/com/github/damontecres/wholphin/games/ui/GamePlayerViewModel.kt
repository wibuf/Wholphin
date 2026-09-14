package com.github.damontecres.wholphin.games.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.games.CoreDownloadService
import com.github.damontecres.wholphin.games.GameCores
import com.github.damontecres.wholphin.games.GameStorage
import com.github.damontecres.wholphin.games.LibretroBridge
import com.github.damontecres.wholphin.games.MoonbaseGamesService
import com.github.damontecres.wholphin.games.model.sanitizeDownloadFileName
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.seconds

sealed interface GamePlayerStatus {
    data class Loading(
        val message: String,
        val progress: Float? = null,
    ) : GamePlayerStatus

    data object Running : GamePlayerStatus

    data class Error(
        val message: String,
    ) : GamePlayerStatus
}

data class GamePlayerState(
    val status: GamePlayerStatus = GamePlayerStatus.Loading("Preparing"),
    val aspect: Float = 4f / 3f,
    val overlayOpen: Boolean = false,
    val settingsOpen: Boolean = false,
    val confirmingExit: Boolean = false,
    val options: List<LibretroBridge.CoreOption> = emptyList(),
    val fastForward: Boolean = false,
    /** A transient notice, eg a core message, shown briefly over the game */
    val message: String? = null,
    val controllers: Int = 0,
    val navigationOnly: Boolean = false,
)

/**
 * Runs one native game session: fetches the ROM and BIOS files, boots the core, syncs save
 * states and emulator options with the server, and drives the pause menu.
 *
 * The launch sequence and save semantics mirror Moonfin's native player so a game saved there
 * resumes here.
 */
@HiltViewModel(assistedFactory = GamePlayerViewModel.Factory::class)
class GamePlayerViewModel
    @AssistedInject
    constructor(
        @param:Assisted private val destination: Destination.GamePlayer,
        val bridge: LibretroBridge,
        private val games: MoonbaseGamesService,
        private val storage: GameStorage,
        private val cores: CoreDownloadService,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(destination: Destination.GamePlayer): GamePlayerViewModel
        }

        private val _state = MutableStateFlow(GamePlayerState())
        val state: StateFlow<GamePlayerState> = _state

        private val core = GameCores.forServerCore(destination.serverCore)
        private val stateKey = GameCores.stateKey(destination.gameId, destination.serverCore)

        // Whether emulator options could be READ this session. Starting on defaults is
        // recoverable; persisting those defaults over settings we never read is not.
        private var optionsReadable = true
        private var exiting = false
        private var messageJob: kotlinx.coroutines.Job? = null

        private val listener =
            object : LibretroBridge.Listener {
                override fun onGeometry(
                    width: Int,
                    height: Int,
                    aspect: Double,
                ) {
                    if (aspect > 0) _state.update { it.copy(aspect = aspect.toFloat()) }
                }

                override fun onError(message: String) {
                    Timber.e("Emulator error: %s", message)
                    _state.update { it.copy(status = GamePlayerStatus.Error(message), overlayOpen = false) }
                }

                override fun onCoreMessage(message: String) {
                    showMessage(message)
                }

                override fun onControllersChanged(
                    count: Int,
                    navigationOnly: Boolean,
                ) {
                    _state.update { it.copy(controllers = count, navigationOnly = navigationOnly) }
                }

                override fun onMenu(port: Int) {
                    toggleOverlay()
                }
            }

        init {
            bridge.listener = listener
            viewModelScope.launch { prepare() }
        }

        private suspend fun prepare() {
            val core = core
            if (core == null) {
                fail("This system (${destination.serverCore}) is not supported yet.")
                return
            }
            try {
                val corePath = cores.installedCorePath(core.coreId)
                if (corePath == null) {
                    fail("The ${core.systemName} core is not installed.")
                    return
                }
                val detail = games.game(destination.libraryId, destination.gameId)
                if (detail == null) {
                    fail("Game not found.")
                    return
                }

                setLoading("Preparing")
                cores.installSupportFiles(core) { setLoading("Downloading support files", it) }
                val systemDir = storage.systemDir()
                val saveDir = storage.saveDir()
                val cacheDir = storage.romDir(destination.libraryId, destination.gameId)

                setLoading("Downloading")
                val romFile = File(cacheDir, sanitizeDownloadFileName(detail.fileName))
                if (!romFile.exists()) {
                    games.downloadRom(destination.libraryId, destination.gameId, romFile) {
                        setLoading("Downloading", it)
                    }
                }
                for (bios in detail.bios) {
                    val biosFile = File(systemDir, sanitizeDownloadFileName(bios.fileName))
                    if (!biosFile.exists()) {
                        games.downloadBios(destination.libraryId, bios.id, biosFile)
                    }
                }

                setLoading("Starting")
                val contentPath =
                    withContext(WholphinDispatchers.IO) {
                        extractIfArchive(romFile, cacheDir, preserveArchive = GameCores.isArcadeFamily(destination.serverCore))
                    }
                if (contentPath == null) {
                    fail("This archive format is not supported.")
                    return
                }

                val options =
                    try {
                        loadOptions(core.coreId).also { optionsReadable = true }
                    } catch (ex: Exception) {
                        Timber.w(ex, "Could not read emulator options")
                        optionsReadable = false
                        emptyMap()
                    }

                val av =
                    withContext(WholphinDispatchers.IO) {
                        bridge.load(
                            core = core.coreId,
                            corePath = corePath.path,
                            romPath = contentPath.path,
                            systemDir = systemDir.path,
                            saveDir = saveDir.path,
                            gameId = destination.gameId,
                            options = GameCores.withOptionDefaults(core.coreId, options),
                            hardwareRenderingEnabled = true,
                        )
                    }
                if (av == null) {
                    fail("The emulator core for this game is not starting.")
                    return
                }
                _state.update { it.copy(aspect = if (av.aspect > 0) av.aspect.toFloat() else 4f / 3f) }
                if (!bridge.start()) {
                    bridge.stop()
                    fail("The emulator could not be started.")
                    return
                }
                if (!destination.startFresh) {
                    try {
                        loadStateFromServer()?.let { bridge.loadState(it) }
                    } catch (ex: Exception) {
                        Timber.w(ex, "Could not load the saved state")
                    }
                }
                _state.update { it.copy(status = GamePlayerStatus.Running, controllers = bridge.controllerCount()) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "Could not start the game")
                bridge.stop()
                fail("Could not start this game. (${ex.message})")
            }
        }

        private fun setLoading(
            message: String,
            progress: Float? = null,
        ) {
            _state.update { it.copy(status = GamePlayerStatus.Loading(message, progress)) }
        }

        private fun fail(message: String) {
            _state.update { it.copy(status = GamePlayerStatus.Error(message)) }
        }

        private fun showMessage(message: String) {
            messageJob?.cancel()
            _state.update { it.copy(message = message) }
            messageJob =
                viewModelScope.launch {
                    delay(4.seconds)
                    _state.update { it.copy(message = null) }
                }
        }

        // ---- Pause menu -----------------------------------------------------------------------

        fun toggleOverlay() {
            if (state.value.status !is GamePlayerStatus.Running) return
            if (state.value.overlayOpen) closeOverlay() else openOverlay()
        }

        fun openOverlay() {
            if (state.value.status !is GamePlayerStatus.Running || state.value.overlayOpen) return
            bridge.pause()
            bridge.overlayOpen = true
            _state.update { it.copy(overlayOpen = true, settingsOpen = false, confirmingExit = false) }
        }

        fun closeOverlay() {
            bridge.overlayOpen = false
            _state.update { it.copy(overlayOpen = false, settingsOpen = false, confirmingExit = false) }
            if (state.value.status is GamePlayerStatus.Running) bridge.resume()
        }

        /** Back from a submenu, or close the menu entirely */
        fun overlayBack() {
            val s = state.value
            when {
                s.confirmingExit -> _state.update { it.copy(confirmingExit = false) }
                s.settingsOpen -> _state.update { it.copy(settingsOpen = false) }
                else -> closeOverlay()
            }
        }

        /** Resume first so the running core samples the pulse, then send the button */
        fun pressButton(index: Int) {
            closeOverlay()
            bridge.pulseButton(0, index)
        }

        fun toggleFastForward() {
            val on = !state.value.fastForward
            bridge.setFastForward(if (on) 2 else 1)
            _state.update { it.copy(fastForward = on) }
        }

        fun saveState() {
            viewModelScope.launch {
                try {
                    if (!persistState()) showMessage("Nothing to save.")
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not save state")
                    showMessage("Could not save state.")
                } finally {
                    closeOverlay()
                }
            }
        }

        fun loadState() {
            viewModelScope.launch {
                try {
                    val save = loadStateFromServer()
                    if (save == null || !bridge.loadState(save)) showMessage("No saved state.")
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not load state")
                    showMessage("Could not load state.")
                } finally {
                    closeOverlay()
                }
            }
        }

        fun restart() {
            viewModelScope.launch {
                try {
                    persistOptions()
                    if (!bridge.restart()) showMessage("Restart is not available for this core.")
                } finally {
                    closeOverlay()
                }
            }
        }

        fun openSettings() {
            val options = bridge.options()
            if (options.isEmpty()) {
                showMessage("This core has no settings.")
                return
            }
            _state.update { it.copy(settingsOpen = true, options = options) }
        }

        /** Cycle an option to its next choice */
        fun cycleOption(
            option: LibretroBridge.CoreOption,
            delta: Int,
        ) {
            if (option.choices.isEmpty()) return
            val index = option.choices.indexOf(option.current).coerceAtLeast(0)
            val next = option.choices[(index + delta).mod(option.choices.size)]
            bridge.setOption(option.id, next)
            _state.update { s ->
                s.copy(options = s.options.map { if (it.id == option.id) it.copy(current = next) else it })
            }
            viewModelScope.launch {
                try {
                    persistOptions()
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not persist options")
                    showMessage("Setting applied for now, but not saved.")
                }
            }
        }

        // The first value in a libretro option is its core-defined default. Restart immediately
        // because many cores only read these during initialization.
        fun resetSettings() {
            viewModelScope.launch {
                try {
                    val options = bridge.options()
                    options.forEach { option -> option.choices.firstOrNull()?.let { bridge.setOption(option.id, it) } }
                    persistOptions()
                    bridge.restart()
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not reset settings")
                    showMessage("Could not reset emulator settings.")
                } finally {
                    closeOverlay()
                }
            }
        }

        fun requestExit() {
            if (state.value.status !is GamePlayerStatus.Running) {
                exit(save = false)
            } else {
                _state.update { it.copy(confirmingExit = true) }
            }
        }

        /**
         * Leave the game. A running game's state and options are pushed to the server first, but
         * a sync failure must never trap the user on the page.
         */
        fun exit(save: Boolean) {
            if (exiting) return
            exiting = true
            viewModelScope.launch {
                if (save && state.value.status is GamePlayerStatus.Running) {
                    try {
                        withTimeoutOrNull(5.seconds) {
                            persistState()
                            persistOptions()
                        }
                    } catch (ex: Exception) {
                        Timber.w(ex, "Could not sync on exit")
                    }
                }
                withContext(WholphinDispatchers.IO) { bridge.stop() }
                navigationManager.goBack()
            }
        }

        // ---- Server sync ----------------------------------------------------------------------

        /** True only when the state actually reached the server */
        private suspend fun persistState(): Boolean {
            val bytes = bridge.saveState() ?: return false
            if (bytes.isEmpty()) return false
            games.putSave(stateKey, bytes)
            return true
        }

        /** Tries the current key, then the one the native backend used before cores were isolated */
        private suspend fun loadStateFromServer(): ByteArray? =
            games.getSave(stateKey) ?: games.getSave(GameCores.legacyStateKey(destination.gameId))

        private suspend fun loadOptions(coreId: String): Map<String, String> =
            readOptions(GameCores.optionsKey(coreId, destination.gameId))
                ?: readOptions(GameCores.legacyOptionsKey(coreId))
                ?: emptyMap()

        private suspend fun readOptions(key: String): Map<String, String>? {
            val blob = games.getSave(key, MoonbaseGamesService.KIND_SETTINGS) ?: return null
            return blob
                .decodeToString()
                .lineSequence()
                .mapNotNull { line ->
                    val eq = line.indexOf('=')
                    if (eq > 0) line.substring(0, eq) to line.substring(eq + 1) else null
                }.toMap()
        }

        private suspend fun persistOptions() {
            val coreId = core?.coreId ?: return
            if (!optionsReadable || !bridge.isActive) return
            val current = bridge.currentOptions()
            if (current.isEmpty()) return
            val blob = current.entries.joinToString("\n") { "${it.key}=${it.value}" }
            games.putSave(GameCores.optionsKey(coreId, destination.gameId), blob.toByteArray(), MoonbaseGamesService.KIND_SETTINGS)
        }

        // ---- Archives -------------------------------------------------------------------------

        /**
         * The playable content path: the file itself, the ROM extracted from a zip next to it,
         * or the zip untouched when [preserveArchive]. Arcade cores identify a machine by the
         * zip's own name and expect every chip inside it, so extracting "the largest file" like
         * every other system does would destroy the set. The file's signature decides, not its
         * name: a server unpacks a single-ROM archive itself and sends the raw ROM under the
         * archive's own name.
         */
        private fun extractIfArchive(
            file: File,
            cacheDir: File,
            preserveArchive: Boolean,
        ): File? {
            if (preserveArchive) return file
            val signature = ByteArray(6)
            val read = RandomAccessFile(file, "r").use { it.read(signature) }
            if (read >= 6 && signature.contentEquals(SEVEN_ZIP_SIGNATURE)) return null
            if (read < 4 || !signature.copyOf(4).contentEquals(ZIP_SIGNATURE)) return file

            val marker = File(cacheDir, ".extracted")
            if (marker.exists()) {
                val existing = File(marker.readText())
                if (existing.exists()) return existing
            }
            ZipFile(file).use { zip ->
                val best =
                    zip
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory }
                        .maxByOrNull { it.size } ?: return null
                // The archive came from the server, so its entry names are as untrusted as the
                // download file names
                val entryName = sanitizeDownloadFileName(best.name.split('/', '\\').last())
                val out = File(cacheDir, entryName)
                zip.getInputStream(best).use { input -> out.outputStream().use { input.copyTo(it) } }
                marker.writeText(out.path)
                return out
            }
        }

        override fun onCleared() {
            super.onCleared()
            if (bridge.listener === listener) bridge.listener = null
            // The page is gone, so the session must not outlive it
            if (bridge.isActive) {
                try {
                    bridge.stop()
                } catch (ex: IOException) {
                    Timber.w(ex, "Error stopping emulator")
                }
            }
        }

        companion object {
            private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
            private val SEVEN_ZIP_SIGNATURE = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
        }
    }
