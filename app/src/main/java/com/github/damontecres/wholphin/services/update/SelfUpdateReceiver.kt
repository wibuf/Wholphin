package com.github.damontecres.wholphin.services.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors

/**
 * Receives the system installer's progress on a [SelfUpdater] install, including the prompt it
 * needs the user to see when the device cannot install without asking
 */
class SelfUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, SelfUpdaterEntryPoint::class.java)
            .selfUpdater()
            .onSessionStatus(intent)
    }
}
