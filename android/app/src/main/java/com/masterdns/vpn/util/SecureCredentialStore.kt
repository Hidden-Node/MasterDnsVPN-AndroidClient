package com.masterdns.vpn.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds the two credential fields (internetSharingUser, internetSharingPass)
 * in EncryptedSharedPreferences rather than the plain DataStore.
 */
object SecureCredentialStore {

    private const val FILE = "masterdns_credentials"
    private const val KEY_USER = "internet_sharing_user"
    private const val KEY_PASS = "internet_sharing_pass"

    @Synchronized
    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getUser(context: Context): String = prefs(context).getString(KEY_USER, "").orEmpty()
    fun getPass(context: Context): String = prefs(context).getString(KEY_PASS, "").orEmpty()

    fun setUser(context: Context, value: String) {
        prefs(context).edit().putString(KEY_USER, value).apply()
    }
    fun setPass(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PASS, value).apply()
    }

    /** One-time migration: read from DataStore if present, write to Encrypted Prefs. */
    @Synchronized
    fun migrateFromDataStore(context: Context, dataStorePrefs: androidx.datastore.preferences.core.Preferences) {
        val existingUser = dataStorePrefs[androidx.datastore.preferences.core.stringPreferencesKey("internet_sharing_user")]?.takeIf { it.isNotEmpty() }
        val existingPass = dataStorePrefs[androidx.datastore.preferences.core.stringPreferencesKey("internet_sharing_pass")]?.takeIf { it.isNotEmpty() }
        if (existingUser != null) prefs(context).edit().putString(KEY_USER, existingUser).commit()
        if (existingPass != null) prefs(context).edit().putString(KEY_PASS, existingPass).commit()
    }

    /**
     * One-time wrapper for use from App.onCreate(): reads the existing DataStore
     * snapshot synchronously, calls [migrateFromDataStore] once, sets the
     * `credentials_migrated_v1` flag in `SharedPreferences("masterdns_migration")`.
     */
    @Synchronized
    fun migrateFromDataStoreOnce(context: Context) {
        val flags = context.getSharedPreferences("masterdns_migration", Context.MODE_PRIVATE)
        if (flags.getBoolean("credentials_migrated_v1", false)) return
        val snapshot = kotlinx.coroutines.runBlocking { GlobalSettingsStore.snapshot(context) }
        migrateFromDataStore(context, snapshot)
        flags.edit().putBoolean("credentials_migrated_v1", true).commit()
    }
}
