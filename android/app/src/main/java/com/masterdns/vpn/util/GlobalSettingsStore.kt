package com.masterdns.vpn.util

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

enum class SplitTunnelMode { INCLUDE, EXCLUDE }

data class GlobalSettings(
    val connectionMode: String = "VPN",
    val allowLan: Boolean = false,
    val splitTunnelingEnabled: Boolean = false,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.INCLUDE,
    val splitPackagesCsv: String = "",
    val customDnsServers: String = "",
    val fakeDnsEnabled: Boolean = true,
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
    private val KEY_SPLIT_TUNNEL_MODE = stringPreferencesKey("split_tunnel_mode")
    private val KEY_SPLIT_PACKAGES = stringPreferencesKey("split_packages")
    private val KEY_CUSTOM_DNS_SERVERS = stringPreferencesKey("custom_dns_servers")
    private val KEY_FAKE_DNS_ENABLED = booleanPreferencesKey("fake_dns_enabled")
    private val KEY_INTERNET_SHARING_ENABLED = booleanPreferencesKey("internet_sharing_enabled")
    private val KEY_INTERNET_SHARING_SOCKS_PORT = intPreferencesKey("internet_sharing_socks_port_v2")
    private val KEY_INTERNET_SHARING_HTTP_PORT = intPreferencesKey("internet_sharing_http_port_v2")

    fun observe(context: Context): Flow<GlobalSettings> {
        return flow {
            val prefs = context.dataStore.data.first()
            val model = prefs.toModel().copy(
                internetSharingUser = SecureCredentialStore.getUser(context),
                internetSharingPass = SecureCredentialStore.getPass(context)
            )
            emit(model)
            context.dataStore.data.collect { updated ->
                emit(
                    updated.toModel().copy(
                        internetSharingUser = SecureCredentialStore.getUser(context),
                        internetSharingPass = SecureCredentialStore.getPass(context)
                    )
                )
            }
        }
    }

    suspend fun load(context: Context): GlobalSettings {
        val prefs = context.dataStore.data.first()
        return prefs.toModel().copy(
            internetSharingUser = SecureCredentialStore.getUser(context),
            internetSharingPass = SecureCredentialStore.getPass(context)
        )
    }

    @androidx.annotation.VisibleForTesting
    internal suspend fun snapshot(context: Context): androidx.datastore.preferences.core.Preferences =
        context.dataStore.data.first()

    suspend fun save(context: Context, settings: GlobalSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CONNECTION_MODE] = settings.connectionMode
            prefs[KEY_ALLOW_LAN] = settings.allowLan
            prefs[KEY_SPLIT_TUNNELING_ENABLED] = settings.splitTunnelingEnabled
            prefs[KEY_SPLIT_TUNNEL_MODE] = settings.splitTunnelMode.name
            prefs[KEY_SPLIT_PACKAGES] = settings.splitPackagesCsv
            prefs[KEY_CUSTOM_DNS_SERVERS] = settings.customDnsServers
            prefs[KEY_FAKE_DNS_ENABLED] = settings.fakeDnsEnabled
            prefs[KEY_INTERNET_SHARING_ENABLED] = settings.internetSharingEnabled
            prefs[KEY_INTERNET_SHARING_SOCKS_PORT] = settings.internetSharingSocksPort.coerceIn(1025, 65535)
            prefs[KEY_INTERNET_SHARING_HTTP_PORT] = settings.internetSharingHttpPort.coerceIn(1025, 65535)
        }
        SecureCredentialStore.setUser(context, settings.internetSharingUser)
        SecureCredentialStore.setPass(context, settings.internetSharingPass)
    }

    private fun Preferences.toModel(): GlobalSettings {
        return GlobalSettings(
            connectionMode = this[KEY_CONNECTION_MODE] ?: "VPN",
            allowLan = this[KEY_ALLOW_LAN] ?: false,
            splitTunnelingEnabled = this[KEY_SPLIT_TUNNELING_ENABLED] ?: false,
            splitTunnelMode = runCatching { SplitTunnelMode.valueOf(this[KEY_SPLIT_TUNNEL_MODE] ?: "INCLUDE") }.getOrDefault(SplitTunnelMode.INCLUDE),
            splitPackagesCsv = this[KEY_SPLIT_PACKAGES] ?: "",
            customDnsServers = this[KEY_CUSTOM_DNS_SERVERS] ?: "",
            fakeDnsEnabled = this[KEY_FAKE_DNS_ENABLED] ?: true,
            internetSharingEnabled = this[KEY_INTERNET_SHARING_ENABLED] ?: false,
            internetSharingSocksPort = this[KEY_INTERNET_SHARING_SOCKS_PORT] ?: 8090,
            internetSharingHttpPort = this[KEY_INTERNET_SHARING_HTTP_PORT] ?: 8091,
            internetSharingUser = "",
            internetSharingPass = ""
        )
    }
}
