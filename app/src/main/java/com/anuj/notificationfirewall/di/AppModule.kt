package com.anuj.notificationfirewall.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.anuj.notificationfirewall.ai.DigestService
import com.anuj.notificationfirewall.ai.OpenAiClient
import com.anuj.notificationfirewall.ai.OpenAiDigestService
import com.anuj.notificationfirewall.data.db.MIGRATION_3_4
import com.anuj.notificationfirewall.data.db.MIGRATION_4_5
import com.anuj.notificationfirewall.data.db.MIGRATION_5_6
import com.anuj.notificationfirewall.data.db.MIGRATION_6_7
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.db.dao.OverrideDao
import com.anuj.notificationfirewall.data.db.dao.SenderBiasDao
import com.anuj.notificationfirewall.data.db.dao.VerdictCacheDao
import com.anuj.notificationfirewall.ai.jev.JevClient
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.wall.BiasStore
import com.anuj.notificationfirewall.domain.wall.JevApi
import com.anuj.notificationfirewall.domain.wall.OverrideStore
import com.anuj.notificationfirewall.domain.wall.VerdictCache
import com.anuj.notificationfirewall.domain.wall.WallPipeline
import com.anuj.notificationfirewall.service.BucketExecutor
import com.anuj.notificationfirewall.service.ChannelManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private const val DATABASE_NAME = "notification-firewall.db"
private const val SECURE_PREFS_FILE_NAME = "nf_secure_prefs"
private const val OPENAI_BASE_URL = "https://api.openai.com/v1/"
private const val JEV_BASE_URL = "https://api.typesafe.ai/"

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSecurePrefs(@ApplicationContext context: Context): SecurePrefs {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        return SecurePrefs(encryptedPrefs)
    }

    @Provides
    @Singleton
    fun provideNfDatabase(@ApplicationContext context: Context): NfDatabase =
        Room.databaseBuilder(context, NfDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()

    @Provides
    fun provideNotificationDao(db: NfDatabase): NotificationDao = db.notificationDao()

    @Provides
    fun provideVerdictCacheDao(db: NfDatabase): VerdictCacheDao = db.verdictCacheDao()

    @Provides
    fun provideSenderBiasDao(db: NfDatabase): SenderBiasDao = db.senderBiasDao()

    @Provides
    fun provideOverrideDao(db: NfDatabase): OverrideDao = db.overrideDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

    // NOTE: this Singleton OpenAiClient captures SecurePrefs.openAiKey at the
    // moment of first injection. If the user sets/changes the API key later
    // in the same process lifetime, this client will keep using the old
    // value until the process restarts. Acceptable for M1; revisit when
    // wiring the settings screen that lets users edit the key at runtime.
    @Provides
    @Singleton
    fun provideOpenAiClient(securePrefs: SecurePrefs, http: OkHttpClient): OpenAiClient =
        OpenAiClient(
            baseUrl = OPENAI_BASE_URL.toHttpUrl(),
            apiKey = securePrefs.openAiKey ?: "",
            http = http
        )

    @Provides
    @Singleton
    fun provideDigestService(client: OpenAiClient): DigestService =
        OpenAiDigestService(client)

    @Provides
    @Singleton
    fun provideChannelManager(@ApplicationContext context: Context): ChannelManager =
        ChannelManager(context)

    @Provides
    @Singleton
    fun provideBucketExecutor(
        @ApplicationContext context: Context,
        channelManager: ChannelManager,
    ): BucketExecutor = BucketExecutor(context, channelManager)

    @Provides
    @Singleton
    fun provideWallSettings(@ApplicationContext context: Context): WallSettings {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return WallSettings(prefs)
    }

    /** Short timeout on purpose: a slow Jev degrades to silence-and-store
     *  rather than holding up the notification pipeline. */
    @Provides
    @Singleton
    @JevHttp
    fun provideJevHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideJevApi(@JevHttp http: OkHttpClient, settings: WallSettings): JevApi =
        JevClient(JEV_BASE_URL.toHttpUrl(), settings.jevKey.orEmpty(), http)

    @Provides
    @Singleton
    fun provideVerdictCache(dao: VerdictCacheDao): VerdictCache = VerdictCache(dao)

    @Provides
    @Singleton
    fun provideBiasStore(dao: SenderBiasDao): BiasStore = BiasStore(dao)

    @Provides
    @Singleton
    fun provideOverrideStore(dao: OverrideDao): OverrideStore = OverrideStore(dao)

    @Provides
    @Singleton
    fun provideWallPipeline(
        overrides: OverrideStore,
        cache: VerdictCache,
        bias: BiasStore,
        jev: JevApi,
        settings: WallSettings,
    ): WallPipeline = WallPipeline(overrides, cache, bias, jev, settings, ZoneId.systemDefault())
}

@Retention(AnnotationRetention.BINARY)
@javax.inject.Qualifier
annotation class JevHttp
