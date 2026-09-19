package com.anuj.notificationfirewall

import android.app.Application
import android.app.NotificationManager
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.service.DndChangeReceiver
import com.anuj.notificationfirewall.work.WallWorkScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

private const val TAG = "NfApplication"

/**
 * Resolved lazily from [NfApplication.onCreate] instead of as an `@Inject
 * lateinit var` field. Hilt's generated field injection resolves every
 * `@Inject` field on the Application eagerly, before `onCreate()`'s body ever
 * runs, which would make [SecurePrefs] -- backed by EncryptedSharedPreferences
 * / the Android Keystore -- a hard dependency of process startup itself. A
 * device without a working Keystore (rare, but seen on some OEM ROMs and
 * degraded work-profile states) would then crash on launch instead of merely
 * losing one best-effort reset. Fetching it through this entry point inside a
 * `runCatching` in `onCreate()` keeps the failure contained to that one line.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SecurePrefsEntryPoint {
    fun securePrefs(): SecurePrefs
}

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

        // securePrefs.listenerConnected describes THIS process's binding to
        // NfListenerService, not a durable fact -- it is written true on
        // onListenerConnected and false on onListenerDisconnected, but a
        // process kill skips onListenerDisconnected entirely, leaving a stale
        // "true" across restart/reboot that the 15-minute health heartbeat
        // (which reads only this flag) can never detect. Reset it to false on
        // every process start; the listener sets it back to true promptly
        // once the platform re-binds it and access is granted. See
        // [SecurePrefsEntryPoint] for why this is fetched lazily rather than
        // field-injected.
        runCatching {
            EntryPointAccessors.fromApplication(this, SecurePrefsEntryPoint::class.java)
                .securePrefs()
                .listenerConnected = false
        }.onFailure { Log.w(TAG, "Could not reset listenerConnected on startup", it) }

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
