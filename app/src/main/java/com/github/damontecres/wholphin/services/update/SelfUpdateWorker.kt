package com.github.damontecres.wholphin.services.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Checks for an update every few hours, including while the app is closed, which is when an
 * Android 12+ device can install it without anyone noticing
 */
@HiltWorker
class SelfUpdateWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted workerParams: WorkerParameters,
        private val selfUpdater: SelfUpdater,
    ) : CoroutineWorker(context, workerParams) {
        override suspend fun doWork(): Result {
            selfUpdater.runScheduledCheck()
            return Result.success()
        }
    }
