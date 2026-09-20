package com.anuj.notificationfirewall

import android.app.Application
import android.app.NotificationManager
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.service.ArmingController
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
 * Resolved lazily from [NfApplication.onCreate] instead of as `@Inject
 * lateinit var` fields. Hilt's generated field injection resolves every
 * `@Inject` field on the Application eagerly, before `onCreate()`'s body ever
 * runs, which would make [SecurePrefs] -- backed by EncryptedSharedPreferences
 * / the Android Keystore, and a transitive dependency of [ArmingController]
 * too -- a hard dependency of process startup itself. A device without a
 * working Keystore (rare, but seen on some OEM ROMs and degraded
 * work-profile states) would then crash on launch instead of merely losing
 * one best-effort reconcile. Fetching through this entry point inside a
 * `runCatching` keeps such a failure contained to the one call site.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NfApplicationEntryPoint {
    fun securePrefs(): SecurePrefs
    fun armingController(): ArmingController
    fun wallSettings(): WallSettings
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
        // [NfApplicationEntryPoint] for why this is fetched lazily rather than
        // field-injected.
        runCatching {
            entryPoint().securePrefs().listenerConnected = false
        }.onFailure { Log.w(TAG, "Could not reset listenerConnected on startup", it) }

        // Registers the wall's background jobs: the network-constrained
        // re-classification worker, and the periodic maintenance worker
        // (safety net for keep-alive state, health-check, text retention,
        // and cache eviction). Idempotent, so calling it on every app start
        // is safe.
        WallWorkScheduler.scheduleAll(this)

        // Same lazy-and-contained fetch as securePrefs above: WallSettings is
        // backed by the same encrypted prefs, so it can fail the same way on
        // a broken Keystore. Losing the digest schedule for one app start is
        // an acceptable degradation; crashing launch is not.
        runCatching {
            val minute = entryPoint().wallSettings().digestTimeMinuteOfDay
            WallWorkScheduler.scheduleDigest(this, minute)
        }.onFailure { Log.w(TAG, "Could not schedule daily digest on startup", it) }

        // DndChangeReceiver must be registered at runtime, not in the manifest:
        // see the KDoc on DndChangeReceiver for why.
        ContextCompat.registerReceiver(
            this,
            DndChangeReceiver(),
            IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Spec §6.1: "A reconcile runs on every app resume and on boot." Boot
        // is covered by BootReceiver; ProcessLifecycleOwner is the
        // process-wide (not per-Activity) signal for the other half, and
        // lifecycle-process was already a reachable dependency of the
        // lifecycle-runtime family already in use elsewhere in this module,
        // so no per-Activity plumbing (e.g. an ON_RESUME observer wired into
        // MainActivity) is needed. This also closes the gap DndChangeReceiver
        // cannot: DndChangeReceiver only runs while this process is alive,
        // so it can't observe anything after the process itself was killed
        // and later relaunched into the foreground -- exactly the window
        // this observer covers.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    runCatching {
                        entryPoint().armingController().onSystemDndChanged()
                    }.onFailure { Log.w(TAG, "Resume reconcile failed", it) }
                }
            },
        )
    }

    private fun entryPoint(): NfApplicationEntryPoint =
        EntryPointAccessors.fromApplication(this, NfApplicationEntryPoint::class.java)
}
