package com.github.damontecres.wholphin.services.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.update
import com.github.damontecres.wholphin.services.MusicService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.NowPlayingStatus
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.services.hilt.IoCoroutineScope
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.Version
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Keeps a personal fork build up to date without anyone having to go looking for updates
 *
 * The fork's update feed is checked every few hours in the background and whenever the app comes on
 * screen. A newer build is downloaded straight away, checked to be an upgrade of this same app, and
 * installed at the first good moment, as decided by [decideInstall]:
 * - Android 12 and newer can install an app's own update without asking, so it goes in silently
 *   as soon as the app is off screen, eg the remote's home button or the TV going to sleep.
 * - Older Android, which includes the Shield and Fire TV, always asks. The system prompt is shown
 *   when the app opens, so it is one button press.
 *
 * Nothing is installed while something is playing, because installing restarts the app. Turning
 * off "check for updates" in settings stops all of it.
 */
@Singleton
class SelfUpdater
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val updateChecker: UpdateChecker,
        private val appPreferences: DataStore<AppPreferences>,
        @param:StandardOkHttpClient private val okHttpClient: OkHttpClient,
        private val musicService: MusicService,
        private val navigationManager: NavigationManager,
        private val workManager: WorkManager,
        @param:IoCoroutineScope private val scope: CoroutineScope,
    ) {
        private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
        private val mutex = Mutex()

        private val dir get() = File(context.noBackupFilesDir, "self-update")
        private val apk get() = File(dir, "update.apk")

        @Volatile
        private var inForeground = false

        @Volatile
        private var externalPlayback = false

        /** Start the periodic background check, if this build updates itself */
        fun schedule() {
            if (!ENABLED) return
            val request =
                PeriodicWorkRequestBuilder<SelfUpdateWorker>(
                    CHECK_INTERVAL.toJavaDuration(),
                    1.hours.toJavaDuration(),
                ).setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                ).setInitialDelay(15.minutes.toJavaDuration())
                    .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** The app came on screen */
        fun onForeground() {
            if (!ENABLED) return
            inForeground = true
            externalPlayback = false
            scope.launch(ExceptionHandler()) {
                migrateUpdateUrl()
                announceIfUpdated()
                // Offer one that is already downloaded first, then look for anything newer
                maybeInstall()
                val sinceCheck = System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L)
                if (sinceCheck >= FOREGROUND_CHECK_INTERVAL.inWholeMilliseconds && download()) {
                    maybeInstall()
                }
            }
        }

        /**
         * The app went off screen
         *
         * @param externalPlayback an external player is showing something from this app, which
         * would lose its progress reporting if the app restarted under it
         */
        fun onBackground(externalPlayback: Boolean) {
            if (!ENABLED) return
            inForeground = false
            this.externalPlayback = externalPlayback
            scope.launch(ExceptionHandler()) { maybeInstall() }
        }

        /** The periodic background check */
        suspend fun runScheduledCheck() {
            if (!ENABLED) return
            migrateUpdateUrl()
            download()
            maybeInstall()
        }

        /**
         * Download the newest build if it is newer than this one
         *
         * @return true if an update is downloaded and ready to install
         */
        private suspend fun download(): Boolean =
            mutex.withLock {
                withContext(WholphinDispatchers.IO) {
                    val settings = appPreferences.data.first()
                    if (!settings.autoCheckForUpdates) return@withContext false
                    prefs.edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
                    val release =
                        try {
                            updateChecker.getLatestRelease(settings.updateUrl)
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            Timber.w(ex, "Could not check for an update")
                            null
                        } ?: return@withContext false
                    val installed = updateChecker.getInstalledVersion()
                    if (!release.version.isGreaterThan(installed)) {
                        clearDownload()
                        return@withContext false
                    }
                    if (release.version.toString() == prefs.getString(KEY_FAILED_VERSION, null)) {
                        // Already failed to install, so wait for a newer one rather than loop
                        return@withContext false
                    }
                    if (downloadedVersion() == release.version) return@withContext true
                    val url = release.downloadUrl ?: return@withContext false

                    Timber.i("Downloading update %s", release.version)
                    dir.mkdirs()
                    val part = File(dir, "update.apk.part")
                    try {
                        val request =
                            Request
                                .Builder()
                                .url(url)
                                .get()
                                .build()
                        okHttpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            response.body.byteStream().use { input ->
                                part.outputStream().use { input.copyTo(it) }
                            }
                        }
                        if (verify(part) == null) {
                            Timber.w("Downloaded update is not an upgrade of this app, discarding it")
                            return@withContext false
                        }
                        apk.delete()
                        if (!part.renameTo(apk)) throw IOException("Could not move the update into place")
                        Timber.i("Update %s is ready to install", release.version)
                        true
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Could not download the update")
                        false
                    } finally {
                        part.delete()
                    }
                }
            }

        private suspend fun maybeInstall() =
            mutex.withLock {
                if (!appPreferences.data.first().autoCheckForUpdates) return@withLock
                downloadedVersion() ?: return@withLock
                val lastPrompt = prefs.getLong(KEY_LAST_PROMPT, 0L)
                val action =
                    decideInstall(
                        silentPossible =
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                !prefs.getBoolean(KEY_SILENT_NEEDS_USER, false),
                        inForeground = inForeground,
                        busy = isBusy(),
                        sinceLastPromptMs = (System.currentTimeMillis() - lastPrompt).takeIf { lastPrompt > 0 },
                        promptIntervalMs = PROMPT_INTERVAL.inWholeMilliseconds,
                    )
                Timber.d("Update install decision: %s", action)
                when (action) {
                    InstallAction.SILENT -> {
                        commit(silent = true)
                    }

                    InstallAction.PROMPT -> {
                        prefs.edit { putLong(KEY_LAST_PROMPT, System.currentTimeMillis()) }
                        commit(silent = false)
                    }

                    InstallAction.WAIT -> {}
                }
            }

        private suspend fun isBusy(): Boolean {
            if (externalPlayback) return true
            if (musicService.state.value.status == NowPlayingStatus.PLAYING) return true
            // Video and games only play on screen, and stop when the app is put away
            if (!inForeground) return false
            return withContext(WholphinDispatchers.Main) {
                when (navigationManager.backStack.lastOrNull()) {
                    is Destination.Playback,
                    is Destination.PlaybackList,
                    is Destination.GamePlayer,
                    is Destination.Slideshow,
                    -> true

                    else -> false
                }
            }
        }

        /** Hand the downloaded update to the system installer */
        private suspend fun commit(silent: Boolean) =
            withContext(WholphinDispatchers.IO) {
                try {
                    val installer = context.packageManager.packageInstaller
                    installer.mySessions.forEach {
                        runCatching { installer.abandonSession(it.sessionId) }
                    }
                    val params =
                        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                            setAppPackageName(context.packageName)
                            setSize(apk.length())
                            if (silent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                            }
                        }
                    val sessionId = installer.createSession(params)
                    installer.openSession(sessionId).use { session ->
                        apk.inputStream().use { input ->
                            session.openWrite("base.apk", 0, apk.length()).use { output ->
                                input.copyTo(output)
                                session.fsync(output)
                            }
                        }
                        val intent =
                            Intent(context, SelfUpdateReceiver::class.java)
                                .putExtra(EXTRA_SILENT, silent)
                        val flags =
                            PendingIntent.FLAG_UPDATE_CURRENT or
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                        val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                        Timber.i("Installing update %s", if (silent) "silently" else "with a prompt")
                        session.commit(pendingIntent.intentSender)
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Could not start the update install")
                }
            }

        /** The system installer reported back on a session, see [SelfUpdateReceiver] */
        fun onSessionStatus(intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val silent = intent.getBooleanExtra(EXTRA_SILENT, false)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            Timber.i("Update install status %d (silent=%s): %s", status, silent, message)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (silent) {
                        // The device would not install without asking after all, usually because
                        // this app has not been allowed to install apps yet. Ask instead from now on.
                        prefs.edit { putBoolean(KEY_SILENT_NEEDS_USER, true) }
                        abandon(sessionId)
                        if (inForeground) scope.launch(ExceptionHandler()) { maybeInstall() }
                    } else if (inForeground && confirm != null) {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        abandon(sessionId)
                    }
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    // Usually never seen, because installing replaces this very process
                    clearDownload()
                }

                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    // The user said not now. The prompt interval keeps it from asking again soon.
                }

                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                PackageInstaller.STATUS_FAILURE_INVALID,
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                -> {
                    // This build will never install, eg it was signed with a different key, so stop
                    // trying until a newer one comes along
                    downloadedVersion()?.let { prefs.edit { putString(KEY_FAILED_VERSION, it.toString()) } }
                    clearDownload()
                }

                else -> {}
            }
        }

        private fun abandon(sessionId: Int) {
            if (sessionId < 0) return
            runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        }

        /** The version of the downloaded update, if there is one and it is still an upgrade */
        private fun downloadedVersion(): Version? = apk.takeIf { it.isFile }?.let { verify(it) }

        /**
         * The version of [file], if it is a newer build of this same app
         *
         * Guards against ever installing the wrong thing, eg the official app, which has a
         * different package name and would install alongside this one instead of updating it.
         */
        @Suppress("DEPRECATION")
        private fun verify(file: File): Version? {
            val info = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: return null
            if (info.packageName != context.packageName) return null
            val version = Version.tryFromString(info.versionName) ?: return null
            return version.takeIf { it.isGreaterThan(updateChecker.getInstalledVersion()) }
        }

        private fun clearDownload() {
            dir.deleteRecursively()
        }

        private suspend fun migrateUpdateUrl() {
            val stored = appPreferences.data.first().updateUrl
            forkUpdateUrlMigration(stored, BuildConfig.DEFAULT_UPDATE_URL)?.let { url ->
                Timber.i("Switching the update URL from %s to %s", stored, url)
                appPreferences.updateData { it.update { updateUrl = url } }
            }
        }

        /** Say so once after an update went in, since a silent one otherwise passes unnoticed */
        private suspend fun announceIfUpdated() {
            val current = updateChecker.getInstalledVersion().toString()
            val previous = prefs.getString(KEY_LAST_VERSION, null)
            if (previous == current) return
            prefs.edit {
                putString(KEY_LAST_VERSION, current)
                // A fresh build gets a fresh go at installing silently, eg because the user
                // allowed installs from this app when they were asked for this one
                remove(KEY_SILENT_NEEDS_USER)
                remove(KEY_LAST_PROMPT)
            }
            clearDownload()
            if (previous != null) {
                showToast(context, context.getString(R.string.self_update_installed, context.getString(R.string.app_name), current))
            }
        }

        companion object {
            /** Whether this build keeps itself up to date: personal fork builds only */
            val ENABLED = BuildConfig.SELF_UPDATE && BuildConfig.UPDATING_ENABLED

            const val WORK_NAME = "self-update"
            private const val PREFS_NAME = "self_update"
            private const val EXTRA_SILENT = "silent"
            private const val KEY_LAST_CHECK = "last_check"
            private const val KEY_LAST_PROMPT = "last_prompt"
            private const val KEY_LAST_VERSION = "last_version"
            private const val KEY_FAILED_VERSION = "failed_version"
            private const val KEY_SILENT_NEEDS_USER = "silent_needs_user"

            private val CHECK_INTERVAL = 6.hours
            private val FOREGROUND_CHECK_INTERVAL = 1.hours
            private val PROMPT_INTERVAL = 12.hours
        }
    }

/** Lets [SelfUpdateReceiver], which Hilt cannot inject, reach the [SelfUpdater] */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SelfUpdaterEntryPoint {
    fun selfUpdater(): SelfUpdater
}
