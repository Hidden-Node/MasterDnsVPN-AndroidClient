package com.masterdns.vpn.util

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class GlobalSettings(
    val connectionMode: String = "VPN",
    val allowLan: Boolean = false,
    val splitTunnelingEnabled: Boolean = false,
    val splitPackagesCsv: String = "",
    val internetSharingEnabled: Boolean = false,
    val internetSharingSocksPort: Int = 8090,
    val internetSharingHttpPort: Int = 8091,
    val internetSharingUser: String = "",
    val internetSharingPass: String = ""
)

object GlobalSettingsStore {
    private val Context.dataStore by preferencesDataStore(name = "global_settings")

    private val KEY_CONNECTION_MODE = stringPreferencesKey("connection_mode")
    private val KEY_ALLOW_LAN = booleanPreferencesKey("allow_lan")
    private val KEY_SPLIT_TUNNELING_ENABLED = booleanPreferencesKey("split_tunneling_enabled")
    private val KEY_SPLIT_PACKAGES = stringPreferencesKey("split_packages")
    private val KEY_INTERNET_SHARING_ENABLED = booleanPreferencesKey("internet_sharing_enabled")
    private val KEY_INTERNET_SHARING_SOCKS_PORT = stringPreferencesKey("internet_sharing_socks_port")
    private val KEY_INTERNET_SHARING_HTTP_PORT = stringPreferencesKey("internet_sharing_http_port")
    private val KEY_INTERNET_SHARING_USER = stringPreferencesKey("internet_sharing_user")
    private val KEY_INTERNET_SHARING_PASS = stringPreferencesKey("internet_sharing_pass")

    fun observe(context: Context): Flow<GlobalSettings> {
        return context.dataStore.data.map { prefs ->
            prefs.toModel()
        }
    }

    suspend fun load(context: Context): GlobalSettings {
        return context.dataStore.data.first().toModel()
    }

    suspend fun save(context: Context, settings: GlobalSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CONNECTION_MODE] = settings.connectionMode
            prefs[KEY_ALLOW_LAN] = settings.allowLan
            prefs[KEY_SPLIT_TUNNELING_ENABLED] = settings.splitTunnelingEnabled
            prefs[KEY_SPLIT_PACKAGES] = settings.splitPackagesCsv
            prefs[KEY_INTERNET_SHARING_ENABLED] = settings.internetSharingEnabled
            prefs[KEY_INTERNET_SHARING_SOCKS_PORT] = settings.internetSharingSocksPort.toString()
            prefs[KEY_INTERNET_SHARING_HTTP_PORT] = settings.internetSharingHttpPort.toString()
            prefs[KEY_INTERNET_SHARING_USER] = settings.internetSharingUser
            prefs[KEY_INTERNET_SHARING_PASS] = settings.internetSharingPass
        }
    }

    private fun Preferences.toModel(): GlobalSettings {
        return GlobalSettings(
            connectionMode = this[KEY_CONNECTION_MODE] ?: "VPN",
            allowLan = this[KEY_ALLOW_LAN] ?: false,
            splitTunnelingEnabled = this[KEY_SPLIT_TUNNELING_ENABLED] ?: false,
            splitPackagesCsv = this[KEY_SPLIT_PACKAGES] ?: "",
            internetSharingEnabled = this[KEY_INTERNET_SHARING_ENABLED] ?: false,
            internetSharingSocksPort = this[KEY_INTERNET_SHARING_SOCKS_PORT]?.toIntOrNull() ?: 1080,
            internetSharingHttpPort = this[KEY_INTERNET_SHARING_HTTP_PORT]?.toIntOrNull() ?: 1081,
            internetSharingUser = this[KEY_INTERNET_SHARING_USER] ?: "",
            internetSharingPass = this[KEY_INTERNET_SHARING_PASS] ?: ""
        )
    }
}
