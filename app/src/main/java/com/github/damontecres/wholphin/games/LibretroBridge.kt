package com.github.damontecres.wholphin.games

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Surface
import androidx.annotation.Keep
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native retro-game playback
 *
 * Drives the shared libretro host (app/src/main/cpp) through JNI, renders into whatever
 * [Surface] the player page hands over, plays audio through an [AudioTrack], and takes RetroPad
 * input from [com.github.damontecres.wholphin.games.input.NativePadInput] via the activity's key
 * dispatch.
 *
 * libretro allows one session per process, so this is a singleton and the native side keeps a
 * single global context. Ported from Moonfin's LibretroBridge with the Flutter channels replaced
 * by a [Listener].
 *
 * Kept whole so minification does not rename the JNI entry points or the callbacks the native
 * side looks up by name (onGeometry, onError, onCoreMessage, onCoreShutdown).
 */
@Keep
@Singleton
class LibretroBridge
    @Inject
    constructor() {
        /** What the player page and input layer hear about the session. All calls on the main thread. */
        interface Listener {
            fun onGeometry(
                width: Int,
                height: Int,
                aspect: Double,
            ) {}

            /** The emulation stopped with an unrecoverable error; the session is already gone */
            fun onError(message: String) {}

            /** Something the core wants shown, such as a missing-BIOS warning */
            fun onCoreMessage(message: String) {}

            fun onControllersChanged(
                count: Int,
                navigationOnly: Boolean,
            ) {}

            /** The user pressed a menu button, or held Start, on the given port */
            fun onMenu(port: Int) {}
        }

        /** Where the video goes and how the core wants it shaped */
        data class AvInfo(
            val width: Int,
            val height: Int,
            val aspect: Double,
            val fps: Double,
            val sampleRate: Int,
        )

        data class CoreOption(
            val id: String,
            val label: String,
            val current: String,
            val choices: List<String>,
        )

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        var listener: Listener? = null

        // Hooks for the input layer, wired by the activity that owns it
        var onActiveChanged: (Boolean) -> Unit = {}
        var onBeforeResume: () -> Unit = {}
        var onControllerTypeChanged: () -> Unit = {}

        private var audioTrack: AudioTrack? = null
        private var audioThread: Thread? = null

        @Volatile
        private var audioRunning = false

        // Physical native input, UI-generated pulses, and method masks compose independently for
        // each libretro port. Fixed arrays avoid any allocation in the steady-state event path.
        private val physicalMasks = IntArray(MAX_PORTS)
        private val pulseMasks = IntArray(MAX_PORTS)
        private val methodMasks = IntArray(MAX_PORTS)
        private val publishedMasks = IntArray(MAX_PORTS)
        private var physicalControllerCount = 0
        private var navigationOnly = false

        private var advertisedControllerTypes: List<NativeControllerType> = emptyList()
        private var advertisedInputDescriptors: List<NativeInputDescriptor> = emptyList()
        private var loadedCore: String? = null

        /**
         * Whether the pause menu is up. The input layer consults this to route pad presses to the
         * menu instead of the game.
         */
        @Volatile
        var overlayOpen = false

        @Volatile
        var isActive = false
            private set

        // Whether the user paused the game, so a background/foreground round trip does not
        // resume a game they left paused
        @Volatile
        private var userPaused = false

        private var surface: Surface? = null

        // The most recent message from the core, used as the reason if it then quits
        @Volatile
        private var lastCoreMessage: String? = null

        /**
         * Load a core and content. Any prior session is torn down first.
         *
         * @return the core's AV geometry, or null when the load failed
         */
        fun load(
            core: String,
            corePath: String,
            romPath: String,
            systemDir: String,
            saveDir: String,
            gameId: String,
            options: Map<String, String>,
            hardwareRenderingEnabled: Boolean,
        ): AvInfo? {
            stop()
            val keys = options.keys.toTypedArray()
            val values = keys.map { options.getValue(it) }.toTypedArray()
            val av =
                nativeLoad(
                    core,
                    corePath,
                    romPath,
                    systemDir,
                    saveDir,
                    gameId,
                    keys,
                    values,
                    hardwareRenderingEnabled,
                )
            if (av == null) {
                advertisedControllerTypes = emptyList()
                advertisedInputDescriptors = emptyList()
                loadedCore = null
                return null
            }
            advertisedControllerTypes = NativeControllerTypeParser.parse(nativeControllerTypes())
            advertisedInputDescriptors = NativeInputDescriptorParser.parse(nativeInputDescriptors())
            loadedCore = core

            surface?.let { nativeSetSurface(it) }
            startAudio(av[4].toInt())
            isActive = true
            // The input layer lives on the main thread; load may run on a worker
            mainHandler.post { onActiveChanged(true) }
            return AvInfo(av[0].toInt(), av[1].toInt(), av[2], av[3], av[4].toInt())
        }

        /** Begin emulation. False when the render thread could not be started. */
        fun start(): Boolean = nativeStart() == 0

        fun pause() {
            userPaused = true
            nativePause()
        }

        fun resume() {
            // Physical masks reach the core even while the overlay is up, so whatever dismissed
            // the menu is still held here. Dropping it BEFORE nativeResume means the core never
            // runs a frame seeing it.
            onBeforeResume()
            userPaused = false
            nativeResume()
        }

        /** Restart the core. False when no core is running. */
        fun restart(): Boolean {
            if (!nativeReset()) return false
            // A restart re-runs core init, which re-sends both controller capabilities and input
            // descriptors
            refreshControllerTypes()
            refreshInputDescriptors()
            return true
        }

        /**
         * The surface to render into, or null to stop rendering
         *
         * Held across sessions so a surface handed over before load is picked up by it.
         */
        fun setSurface(surface: Surface?) {
            this.surface = surface
            nativeSetSurface(surface)
        }

        /** Pause the core while the surface is gone, eg the app went to the background */
        fun onSurfaceLost() {
            if (!isActive) return
            nativePause()
            setSurface(null)
        }

        /** Pick up again once a surface is back, unless the user paused */
        fun onSurfaceReady(surface: Surface) {
            setSurface(surface)
            if (isActive && !userPaused) nativeResume()
        }

        /**
         * End the session and free the native host. Safe to call repeatedly.
         *
         * The audio thread is joined before the host is destroyed: it is the only caller of
         * nativeReadAudio, so this ordering is what guarantees no thread is inside the ring buffer
         * when it is freed.
         */
        fun stop() {
            val hadActiveSession = isActive
            isActive = false
            userPaused = false
            lastCoreMessage = null
            advertisedControllerTypes = emptyList()
            advertisedInputDescriptors = emptyList()
            loadedCore = null
            stopAudio()
            if (hadActiveSession) resetAllMasks() else clearMaskArrays()
            nativeStop()
            overlayOpen = false
            mainHandler.post { onActiveChanged(false) }
        }

        /** Stop emulating while the app is in the background */
        fun onHostPause() {
            if (isActive) nativePause()
        }

        /** Pick up again on return, unless the user paused */
        fun onHostResume() {
            if (isActive && !userPaused) nativeResume()
        }

        fun saveState(): ByteArray? = nativeSaveState()

        fun loadState(data: ByteArray): Boolean = nativeLoadState(data)

        fun setFastForward(factor: Int) = nativeSetFastForward(factor)

        /** Tap a RetroPad button for the UI, eg "Press Start" from the pause menu */
        fun pulseButton(
            port: Int,
            index: Int,
            durationMs: Long = 150,
        ) {
            if (!isValidPort(port) || index < 0 || index >= 16) return
            val bit = 1 shl index
            pulseMasks[port] = pulseMasks[port] or bit
            applyMask(port)
            mainHandler.postDelayed({
                pulseMasks[port] = pulseMasks[port] and bit.inv()
                applyMask(port)
            }, durationMs)
        }

        fun options(): List<CoreOption> = decodeOptions(nativeOptions())

        fun currentOptions(): Map<String, String> = options().associate { it.id to it.current }

        fun setOption(
            id: String,
            value: String,
        ) = nativeSetOption(id, value)

        /** The options a core exposes, read from a throwaway host. Empty while a session is live. */
        fun probeOptions(
            corePath: String,
            systemDir: String,
        ): List<CoreOption> = decodeOptions(nativeProbeOptions(corePath, systemDir))

        fun controllerCount(): Int = physicalControllerCount

        fun controllerTypes(): List<NativeControllerType> = refreshControllerTypes()

        fun inputDescriptors(): List<NativeInputDescriptor> = refreshInputDescriptors()

        /** Pick a core-advertised device type for a port. False when the core rejected it. */
        fun setControllerType(
            port: Int,
            deviceType: Long,
        ): Boolean {
            if (!isValidPort(port)) return false
            val isDefault = deviceType == RETRO_DEVICE_JOYPAD
            val isAdvertised = refreshControllerTypes().any { it.port == port && it.id == deviceType }
            if (!isDefault && !isAdvertised) return false
            if (nativeSetControllerType(port, deviceType) < 0) return false
            refreshInputDescriptors()
            onControllerTypeChanged()
            return true
        }

        // ---- Input layer entry points ------------------------------------------------------

        /** Zero just the physical-pad contribution */
        fun resetPadMasks() {
            for (port in 0 until MAX_PORTS) {
                physicalMasks[port] = 0
                applyMask(port)
            }
        }

        fun setControllerCount(
            count: Int,
            navigationOnly: Boolean = false,
            force: Boolean = false,
        ) {
            val clamped = count.coerceIn(0, MAX_PORTS)
            if (physicalControllerCount == clamped && this.navigationOnly == navigationOnly && !force) return
            physicalControllerCount = clamped
            this.navigationOnly = navigationOnly
            if (isActive) {
                mainHandler.post { listener?.onControllersChanged(clamped, navigationOnly) }
            }
        }

        /**
         * The whole RetroPad state for a port at once, so one input event costs one JNI call no
         * matter how many bits it moved
         */
        fun onPad(
            port: Int,
            mask: Int,
        ) {
            if (!isValidPort(port)) return
            if (physicalMasks[port] == mask) return
            physicalMasks[port] = mask
            applyMask(port)
        }

        /** Sibling of [onPad] that also carries the analog axes and trigger pressures */
        fun onPadState(
            port: Int,
            mask: Int,
            lx: Int,
            ly: Int,
            rx: Int,
            ry: Int,
            l2: Int,
            r2: Int,
        ) {
            if (!isValidPort(port)) return
            physicalMasks[port] = mask
            val desired = physicalMasks[port] or pulseMasks[port] or methodMasks[port]
            publishedMasks[port] = desired
            nativeSetPadState(port, desired, lx, ly, rx, ry, l2, r2)
        }

        fun onMenu(port: Int = 0) {
            mainHandler.post { listener?.onMenu(port) }
        }

        /**
         * Bitmask of ports whose left stick is passed through as analog instead of being converted
         * to d-pad bits (bit N = port N)
         */
        fun analogStickPorts(): Int {
            if (!isActive || loadedCore == null) return 0
            return nativeAnalogStickPorts()
        }

        // ---- Called from JNI ----------------------------------------------------------------

        @Keep
        fun onError(message: String) {
            mainHandler.post { listener?.onError(message) }
        }

        @Keep
        fun onCoreMessage(message: String) {
            lastCoreMessage = message
            mainHandler.post { listener?.onCoreMessage(message) }
        }

        // The core asked to quit, which cores do when a boot fails. The emulation is already
        // gone, so tear the rest down and report the reason instead of leaving a frozen picture.
        @Keep
        fun onCoreShutdown() {
            mainHandler.post {
                val detail = lastCoreMessage
                stop()
                listener?.onError(detail ?: "The emulator core stopped unexpectedly.")
            }
        }

        @Keep
        fun onGeometry(
            width: Int,
            height: Int,
            aspect: Double,
        ) {
            mainHandler.post { listener?.onGeometry(width, height, aspect) }
        }

        // ---- Audio --------------------------------------------------------------------------

        private fun startAudio(sampleRate: Int) {
            val track = buildAudioTrack(sampleRate)
            audioTrack = track
            track.play()
            audioRunning = true
            val thread = Thread { runAudioLoop(track) }
            thread.name = "wholphin.game.audio"
            audioThread = thread
            thread.start()
        }

        private fun buildAudioTrack(sampleRate: Int): AudioTrack {
            // A small device buffer keeps input-to-sound lag low, while still holding several of
            // the AUDIO_CHUNK_FRAMES writes the loop below issues
            val bytesPerFrame = 2 * BYTES_PER_SAMPLE
            val bufferBytes =
                AudioTrack
                    .getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                    .coerceAtLeast(2 * AUDIO_CHUNK_FRAMES * bytesPerFrame)
            val builder =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    ).setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            return builder.build()
        }

        // Runs on the audio thread for the life of one session. It is the only caller of
        // nativeReadAudio, and stopAudio() joins it before the native host is destroyed.
        private fun runAudioLoop(track: AudioTrack) {
            // The emulation and blit threads are native pthreads; this Java thread starts at the
            // default priority and would otherwise be the first descheduled under load, which
            // shows up as underrun crackle.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(AUDIO_CHUNK_FRAMES * 2)
            try {
                while (audioRunning) {
                    val read = nativeReadAudio(buffer, AUDIO_CHUNK_FRAMES)
                    // Write only what the ring had, since padding silence would pop. On a short
                    // read the emulator is priming or paused, so give it a moment.
                    if (read > 0) track.write(buffer, 0, read * 2)
                    if (read < AUDIO_CHUNK_FRAMES) Thread.sleep(2)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: IllegalStateException) {
                Timber.w(e, "audio loop stopped: track no longer usable")
            }
        }

        private fun stopAudio() {
            audioRunning = false
            // Pause + flush drops the queued buffer so a blocking write can return immediately,
            // while stop alone only drains it. The join stays unbounded so the thread cannot
            // reach lh_read_audio after nativeStop frees the buffer.
            audioTrack?.let { runCatching { it.pause() } }
            audioTrack?.let { runCatching { it.flush() } }
            audioTrack?.let { runCatching { it.stop() } }
            audioThread?.join()
            audioThread = null
            audioTrack?.let { runCatching { it.release() } }
            audioTrack = null
        }

        // ---- Masks --------------------------------------------------------------------------

        private fun resetAllMasks() {
            for (port in 0 until MAX_PORTS) {
                physicalMasks[port] = 0
                pulseMasks[port] = 0
                methodMasks[port] = 0
                applyMask(port)
            }
        }

        private fun clearMaskArrays() {
            physicalMasks.fill(0)
            pulseMasks.fill(0)
            methodMasks.fill(0)
            publishedMasks.fill(0)
        }

        private fun isValidPort(port: Int): Boolean = port in 0 until MAX_PORTS

        private fun applyMask(port: Int) {
            if (!isValidPort(port)) return
            val desired = physicalMasks[port] or pulseMasks[port] or methodMasks[port]
            if (publishedMasks[port] == desired) return
            publishedMasks[port] = desired
            nativeSetMask(port, desired)
        }

        // ---- Control-plane parsing ----------------------------------------------------------

        /** Decodes the tab-joined option entries the JNI layer packs */
        private fun decodeOptions(entries: Array<String>): List<CoreOption> =
            entries.mapNotNull { entry ->
                val parts = entry.split("\t")
                if (parts.size < 3) return@mapNotNull null
                CoreOption(parts[0], parts[1], parts[2], parts.drop(3))
            }

        private fun refreshControllerTypes(): List<NativeControllerType> {
            if (!isActive || loadedCore == null) return advertisedControllerTypes
            advertisedControllerTypes = NativeControllerTypeParser.parse(nativeControllerTypes())
            return advertisedControllerTypes
        }

        private fun refreshInputDescriptors(): List<NativeInputDescriptor> {
            if (!isActive || loadedCore == null) return advertisedInputDescriptors
            advertisedInputDescriptors = NativeInputDescriptorParser.parse(nativeInputDescriptors())
            return advertisedInputDescriptors
        }

        // ---- JNI ----------------------------------------------------------------------------

        private external fun nativeAnalogStickPorts(): Int

        private external fun nativeLoad(
            core: String,
            corePath: String,
            romPath: String,
            systemDir: String,
            saveDir: String,
            gameId: String,
            optKeys: Array<String>,
            optVals: Array<String>,
            hardwareRenderingEnabled: Boolean,
        ): DoubleArray?

        private external fun nativeSetSurface(surface: Surface?)

        private external fun nativeHwRenderSize(): IntArray?

        private external fun nativeStart(): Int

        private external fun nativePause()

        private external fun nativeResume()

        private external fun nativeReset(): Boolean

        private external fun nativeStop()

        private external fun nativeSetFastForward(factor: Int)

        private external fun nativeSetMask(
            port: Int,
            mask: Int,
        )

        private external fun nativeSetPadState(
            port: Int,
            mask: Int,
            lx: Int,
            ly: Int,
            rx: Int,
            ry: Int,
            l2: Int,
            r2: Int,
        )

        private external fun nativeReadAudio(
            buffer: ShortArray,
            frames: Int,
        ): Int

        private external fun nativeSaveState(): ByteArray?

        private external fun nativeLoadState(data: ByteArray): Boolean

        private external fun nativeOptions(): Array<String>

        private external fun nativeControllerTypes(): Array<String>

        private external fun nativeInputDescriptors(): Array<String>

        private external fun nativeSetControllerType(
            port: Int,
            deviceType: Long,
        ): Int

        private external fun nativeProbeOptions(
            corePath: String,
            systemDir: String,
        ): Array<String>

        private external fun nativeSetOption(
            id: String,
            value: String,
        )

        companion object {
            const val MAX_PORTS = 4

            // Frames pulled from the native ring per write. Stereo, so the short buffer is twice
            // this. Kept near one device period so the blocking write applies back pressure
            // several times per video frame.
            private const val AUDIO_CHUNK_FRAMES = 256
            private const val BYTES_PER_SAMPLE = 2
            private const val RETRO_DEVICE_JOYPAD = 1L

            // RetroPad button indices
            const val RETRO_SELECT = 2
            const val RETRO_START = 3

            init {
                System.loadLibrary("wholphin_libretro")
            }
        }
    }

/** Control-plane description of one core-advertised port/device pair */
data class NativeControllerType(
    val port: Int,
    val id: Long,
    val label: String,
)

object NativeControllerTypeParser {
    fun parse(entries: Array<String>): List<NativeControllerType> =
        entries.mapNotNull { entry ->
            val fields = entry.split('\t', limit = 3)
            if (fields.size != 3) return@mapNotNull null
            val port = fields[0].toIntOrNull() ?: return@mapNotNull null
            val id = fields[1].toLongOrNull() ?: return@mapNotNull null
            if (port < 0) return@mapNotNull null
            NativeControllerType(port, id, fields[2])
        }
}

/**
 * One core-advertised RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS entry: which (port, device, index,
 * id) a human-readable label such as "Coin" or "Fire" applies to
 */
data class NativeInputDescriptor(
    val port: Int,
    val device: Long,
    val index: Int,
    val id: Long,
    val description: String,
)

object NativeInputDescriptorParser {
    fun parse(entries: Array<String>): List<NativeInputDescriptor> =
        entries.mapNotNull { entry ->
            // limit = 5 keeps a description that itself contains a tab intact
            val fields = entry.split('\t', limit = 5)
            if (fields.size != 5) return@mapNotNull null
            val port = fields[0].toIntOrNull() ?: return@mapNotNull null
            val device = fields[1].toLongOrNull() ?: return@mapNotNull null
            val index = fields[2].toIntOrNull() ?: return@mapNotNull null
            val id = fields[3].toLongOrNull() ?: return@mapNotNull null
            if (port < 0 || device < 0 || index < 0 || id < 0) return@mapNotNull null
            NativeInputDescriptor(port, device, index, id, fields[4])
        }
}
