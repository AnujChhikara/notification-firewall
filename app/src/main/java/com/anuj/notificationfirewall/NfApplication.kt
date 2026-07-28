package com.anuj.notificationfirewall

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
    }
}
