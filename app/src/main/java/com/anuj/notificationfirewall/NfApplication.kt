package com.anuj.notificationfirewall

import android.app.Application
import android.app.NotificationManager
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.anuj.notificationfirewall.service.DndChangeReceiver
import com.anuj.notificationfirewall.work.WallWorkScheduler
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
        // Registers the wall's background jobs: the network-constrained
        // re-classification worker, and the periodic maintenance worker
        // (safety net for keep-alive state, health-check, text retention,
        // and cache eviction). Idempotent, so calling it on every app start
        // is safe.
        WallWorkScheduler.scheduleAll(this)

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
