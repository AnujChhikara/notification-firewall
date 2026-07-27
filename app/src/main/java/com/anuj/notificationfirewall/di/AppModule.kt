package com.anuj.notificationfirewall.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.anuj.notificationfirewall.ai.DigestService
import com.anuj.notificationfirewall.ai.ImportanceService
import com.anuj.notificationfirewall.ai.OpenAiClient
import com.anuj.notificationfirewall.ai.OpenAiDigestService
import com.anuj.notificationfirewall.ai.OpenAiImportanceService
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.db.dao.ProfileDao
import com.anuj.notificationfirewall.data.db.dao.RuleDao
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.domain.profile.ProfileManager
import com.anuj.notificationfirewall.domain.rules.RuleEngine
import com.anuj.notificationfirewall.service.NotificationPipeline
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Singleton

private const val DATABASE_NAME = "notification-firewall.db"
private const val SECURE_PREFS_FILE_NAME = "nf_secure_prefs"
private const val OPENAI_BASE_URL = "https://api.openai.com/v1/"

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
        Room.databaseBuilder(context, NfDatabase::class.java, DATABASE_NAME).build()

    @Provides
    fun provideProfileDao(db: NfDatabase): ProfileDao = db.profileDao()

    @Provides
    fun provideRuleDao(db: NfDatabase): RuleDao = db.ruleDao()

    @Provides
    fun provideNotificationDao(db: NfDatabase): NotificationDao = db.notificationDao()

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
    fun provideImportanceService(client: OpenAiClient): ImportanceService =
        OpenAiImportanceService(client)

    @Provides
    @Singleton
    fun provideDigestService(client: OpenAiClient): DigestService =
        OpenAiDigestService(client)

    @Provides
    @Singleton
    fun provideRuleEngine(): RuleEngine = RuleEngine()

    @Provides
    @Singleton
    fun provideProfileManager(): ProfileManager = ProfileManager()

    @Provides
    @Singleton
    fun provideNotificationPipeline(
        profileManager: ProfileManager,
        ruleEngine: RuleEngine,
        importanceService: ImportanceService
    ): NotificationPipeline = NotificationPipeline(profileManager, ruleEngine, importanceService)
}
