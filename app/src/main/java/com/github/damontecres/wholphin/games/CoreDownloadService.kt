package com.github.damontecres.wholphin.games

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads and installs libretro cores from the libretro buildbot
 *
 * Cores are not bundled: they are large, GPL'd separately, and updated nightly, so the user
 * picks which systems to add and the app fetches them on demand. Installed means the core file
 * is on disk, so there is no separate registry to fall out of sync.
 *
 * Fork: except in the Google Play build, where Play doesn't allow downloading native code. There
 * the cores ship inside the app ([BUNDLED]), are loaded by library name from the app itself, and
 * nothing is ever downloaded ([DOWNLOADS] is off).
 */
@Singleton
class CoreDownloadService
    @Inject
    constructor(
        private val storage: GameStorage,
        @param:StandardOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        /**
         * What to load for a core: a bundled core's library name, which the loader finds inside
         * the app, or the path of a downloaded one. Null when it isn't on the device.
         */
        fun installedCorePath(coreId: String): String? {
            if (isBundled(coreId)) return bundledLibraryName(coreId)
            val abi = GameCores.buildbotAbi() ?: return null
            return File(storage.coresDir(abi), GameCores.coreFileName(coreId)).takeIf { it.isFile }?.path
        }

        fun isInstalled(coreId: String): Boolean = installedCorePath(coreId) != null

        /** Shipped inside the app (Google Play build), so never downloaded or removed */
        fun isBundled(coreId: String): Boolean = coreId in BUNDLED

        /** Whether the core can be had on this device: bundled, or a download libretro publishes */
        fun isAvailable(core: GameCore): Boolean = isBundled(core.coreId) || (DOWNLOADS && GameCores.downloadUrl(core) != null)

        /**
         * Download the core, extract it into the cores directory, and install any support files
         * it needs. [onProgress] reports 0..1 across the whole install.
         */
        suspend fun download(
            core: GameCore,
            onProgress: (Float) -> Unit = {},
        ) = withContext(WholphinDispatchers.IO) {
            if (!DOWNLOADS) throw IOException("${core.systemName} isn't included in this version of the app")
            val url = GameCores.downloadUrl(core) ?: throw IOException("No core build for this device")
            val abi = GameCores.buildbotAbi()!!
            val dest = File(storage.coresDir(abi), GameCores.coreFileName(core.coreId))
            val coreShare = if (core.supportFiles == null) 1f else 0.4f

            Timber.i("Downloading core %s from %s", core.coreId, url)
            val zip = fetch(url) { onProgress(it * coreShare) }
            try {
                extractCore(zip, dest)
            } finally {
                zip.delete()
            }
            if (core.supportFiles != null) {
                installSupportFiles(core) { onProgress(coreShare + it * (1 - coreShare)) }
            }
            onProgress(1f)
        }

        /** Whether the core has everything it needs to boot */
        fun supportFilesInstalled(core: GameCore): Boolean {
            val support = core.supportFiles ?: return true
            return File(File(storage.systemDir(), support.folder), support.markerFile).exists()
        }

        /**
         * Fetch the support files the core needs and unpack them into the system directory. Does
         * nothing when they are already there, so it is safe to call before every launch.
         */
        suspend fun installSupportFiles(
            core: GameCore,
            onProgress: (Float) -> Unit = {},
        ) = withContext(WholphinDispatchers.IO) {
            val support = core.supportFiles ?: return@withContext
            if (supportFilesInstalled(core)) return@withContext

            val zip = fetch(support.url, onProgress)
            // A partial unpack would look installed, so the payload lands in a scratch folder and
            // only takes the real name once it is complete
            val target = File(storage.systemDir(), support.folder)
            val staging = File(storage.systemDir(), support.folder + ".part")
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                unpackSupportArchive(zip, support.folder, staging)
                if (!File(staging, support.markerFile).exists()) throw IOException("Support files incomplete")
                target.deleteRecursively()
                if (!staging.renameTo(target)) throw IOException("Could not move support files into place")
            } catch (ex: Exception) {
                staging.deleteRecursively()
                throw ex
            } finally {
                zip.delete()
            }
        }

        /** Delete the installed core file and its support files */
        fun remove(core: GameCore) {
            if (isBundled(core.coreId)) return
            installedCorePath(core.coreId)?.let(::File)?.delete()
            core.supportFiles?.let { File(storage.systemDir(), it.folder).deleteRecursively() }
        }

        private fun fetch(
            url: String,
            onProgress: (Float) -> Unit,
        ): File {
            val tmp = File.createTempFile("core", ".zip", storage.romsRoot())
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading $url")
                val body = response.body
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var received = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            received += read
                            if (total > 0) onProgress(received.toFloat() / total)
                        }
                    }
                }
            }
            return tmp
        }

        /** The single core binary inside a buildbot zip, whatever it is named */
        private fun extractCore(
            zip: File,
            dest: File,
        ) {
            val partial = File(dest.parentFile, dest.name + ".part")
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".so")) {
                        partial.outputStream().use { input.copyTo(it) }
                        if (!partial.renameTo(dest)) throw IOException("Could not move core into place")
                        return
                    }
                    input.closeEntry()
                }
            }
            throw IOException("Core file missing from download")
        }

        companion object {
            /**
             * Write the entries under [folder] into [destination], flattening away the zip's
             * top-level folder. Entries outside that folder, and any path climbing out of it, are
             * skipped, since the archive comes off the network.
             */
            fun unpackSupportArchive(
                zip: File,
                folder: String,
                destination: File,
            ) {
                val prefix = "$folder/"
                ZipInputStream(zip.inputStream().buffered()).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (!entry.isDirectory) {
                            val name = entry.name.replace('\\', '/')
                            if (name.startsWith(prefix)) {
                                val relative = name.removePrefix(prefix)
                                if (relative.isNotEmpty() &&
                                    !relative.startsWith("/") &&
                                    ".." !in relative.split('/')
                                ) {
                                    val out = File(destination, relative)
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { input.copyTo(it) }
                                }
                            }
                        }
                        input.closeEntry()
                    }
                }
            }

            /** Fork: whether this build may download cores (off for Google Play) */
            val DOWNLOADS: Boolean = BuildConfig.CORE_DOWNLOADS

            /** Fork: core ids shipped inside the app, from the build */
            val BUNDLED: Set<String> =
                BuildConfig.BUNDLED_CORES
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()

            /** The library name a bundled core is packaged under, which dlopen finds in the app */
            fun bundledLibraryName(coreId: String): String = "lib${coreId}_libretro.so"
        }
    }
