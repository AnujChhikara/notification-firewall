package com.anuj.notificationfirewall

import android.app.Application
import android.app.NotificationManager
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.anuj.notificationfirewall.service.DndChangeReceiver
import com.anuj.notificationfirewall.work.MaintenanceWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NfApplication : Application(), Configuration.Provider {

    // Lets WorkManager construct @HiltWorker workers with their injected
    // dependencies. Paired with the manifest removal of WorkManager's default
    // initializer so on-demand initialization uses this config.
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Periodic safety net: reconcile state, re-arm alarms, health-check.
        MaintenanceWorker.schedule(this)

        // DndChangeReceiver must be registered at runtime, not in the manifest:
        // see the KDoc on DndChangeReceiver for why.
        ContextCompat.registerReceiver(
            this,
            DndChangeReceiver(),
            IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
