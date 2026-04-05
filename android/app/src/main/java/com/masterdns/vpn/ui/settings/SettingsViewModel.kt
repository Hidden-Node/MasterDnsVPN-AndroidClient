package com.masterdns.vpn.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.masterdns.vpn.data.local.ProfileEntity
import com.masterdns.vpn.data.repository.ProfileRepository
import com.masterdns.vpn.util.ConfigGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val gson = Gson()

    private val profileIdArg: Long? = savedStateHandle.get<String>("profileId")?.toLongOrNull()

    val selectedProfile: StateFlow<ProfileEntity?> = (
        if (profileIdArg != null) profileRepository.getProfileByIdFlow(profileIdArg)
        else profileRepository.getSelectedProfileFlow()
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun saveSettings(profile: ProfileEntity, values: Map<String, String>) {
        viewModelScope.launch {
            profileRepository.updateProfile(buildUpdatedProfile(profile, values))
        }
    }

    fun exportConfigToml(profile: ProfileEntity, values: Map<String, String>): String {
        val updated = buildUpdatedProfile(profile, values)
        return ConfigGenerator.generateConfig(
            profile = updated,
            listenPort = updated.listenPort
        )
    }

    fun importTomlValues(
        tomlContent: String,
        currentValues: Map<String, String>
    ): Map<String, String> {
        val result = currentValues.toMutableMap()
        tomlContent.lineSequence().forEach { raw ->
            val line = raw.substringBefore("#").trim()
            if (line.isEmpty() || "=" !in line) return@forEach
            val key = line.substringBefore("=").trim()
            val valueRaw = line.substringAfter("=").trim()
            if (key !in TOML_IMPORT_KEYS) return@forEach

            val parsed = when {
                key == "DOMAINS" -> valueRaw
                    .removePrefix("[")
                    .removeSuffix("]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                valueRaw.startsWith("\"") && valueRaw.endsWith("\"") ->
                    valueRaw.removeSurrounding("\"")
                else -> valueRaw
            }
            result[key] = parsed
        }
        return result
    }

    fun importResolvers(profile: ProfileEntity, resolversText: String) {
        viewModelScope.launch {
            profileRepository.updateProfile(profile.copy(resolvers = resolversText.trim()))
        }
    }

    private fun parseAdvanced(json: String): Map<String, String> {
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun normalizeProtocol(value: String?, fallback: String): String {
        val normalized = value?.trim()?.uppercase()
        return when (normalized) {
            "SOCKS5", "TCP" -> normalized
            else -> fallback
        }
    }

    private fun normalizeResolverBalancingStrategy(value: String?, fallback: Int): Int {
        return when (value?.trim()?.toIntOrNull()) {
            0 -> 2
            1, 2, 3, 4 -> value.trim().toInt()
            else -> if (fallback == 0) 2 else fallback
        }
    }

    private fun buildUpdatedProfile(profile: ProfileEntity, values: Map<String, String>): ProfileEntity {
        val mergedAdvanced = parseAdvanced(profile.advancedJson).toMutableMap()
        values.forEach { (key, value) ->
            if (key in ADVANCED_SETTING_KEYS) {
                mergedAdvanced[key] = value.trim()
            }
        }

        return profile.copy(
            domains = domainsToJson(values["DOMAINS"], profile.domains),
            encryptionMethod = values["DATA_ENCRYPTION_METHOD"]?.toIntOrNull()
                ?: profile.encryptionMethod,
            encryptionKey = values["ENCRYPTION_KEY"] ?: profile.encryptionKey,
            protocolType = normalizeProtocol(values["PROTOCOL_TYPE"], profile.protocolType),
            listenPort = values["LISTEN_PORT"]?.toIntOrNull()
                ?.coerceIn(1, 65535) ?: profile.listenPort,
            resolverBalancingStrategy = normalizeResolverBalancingStrategy(
                values["RESOLVER_BALANCING_STRATEGY"],
                profile.resolverBalancingStrategy
            ),
            packetDuplicationCount = values["PACKET_DUPLICATION_COUNT"]?.toIntOrNull()
                ?: profile.packetDuplicationCount,
            setupPacketDuplicationCount = values["SETUP_PACKET_DUPLICATION_COUNT"]?.toIntOrNull()
                ?: profile.setupPacketDuplicationCount,
            uploadCompression = values["UPLOAD_COMPRESSION_TYPE"]?.toIntOrNull()
                ?: profile.uploadCompression,
            downloadCompression = values["DOWNLOAD_COMPRESSION_TYPE"]?.toIntOrNull()
                ?: profile.downloadCompression,
            logLevel = values["LOG_LEVEL"]?.trim().takeUnless { it.isNullOrBlank() }
                ?: profile.logLevel,
            advancedJson = gson.toJson(mergedAdvanced)
        )
    }

    private fun domainsToJson(value: String?, fallbackJson: String): String {
        val domains = value
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return if (domains.isEmpty()) fallbackJson else gson.toJson(domains)
    }

    companion object {
        val TOML_IMPORT_KEYS = setOf(
            "DOMAINS",
            "DATA_ENCRYPTION_METHOD",
            "ENCRYPTION_KEY",
            "PROTOCOL_TYPE",
            "LISTEN_IP",
            "LISTEN_PORT",
            "SOCKS5_AUTH",
            "SOCKS5_USER",
            "SOCKS5_PASS",
            "LOCAL_DNS_ENABLED",
            "RESOLVER_BALANCING_STRATEGY",
            "PACKET_DUPLICATION_COUNT",
            "SETUP_PACKET_DUPLICATION_COUNT",
            "STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD",
            "STREAM_RESOLVER_FAILOVER_COOLDOWN",
            "RECHECK_SERVER_INTERVAL_SECONDS",
            "RECHECK_INACTIVE_SERVERS_ENABLED",
            "AUTO_DISABLE_TIMEOUT_SERVERS",
            "BASE_ENCODE_DATA",
            "UPLOAD_COMPRESSION_TYPE",
            "DOWNLOAD_COMPRESSION_TYPE",
            "COMPRESSION_MIN_SIZE",
            "MIN_UPLOAD_MTU",
            "MIN_DOWNLOAD_MTU",
            "MAX_UPLOAD_MTU",
            "MAX_DOWNLOAD_MTU",
            "MTU_TEST_RETRIES",
            "MTU_TEST_TIMEOUT",
            "MTU_TEST_PARALLELISM",
            "SAVE_MTU_SERVERS_TO_FILE",
            "MTU_SERVERS_FILE_NAME",
            "MTU_SERVERS_FILE_FORMAT",
            "MTU_USING_SECTION_SEPARATOR_TEXT",
            "MTU_REMOVED_SERVER_LOG_FORMAT",
            "MTU_ADDED_SERVER_LOG_FORMAT",
            "TUNNEL_READER_WORKERS",
            "TUNNEL_WRITER_WORKERS",
            "TUNNEL_PROCESS_WORKERS",
            "TUNNEL_PACKET_TIMEOUT_SECONDS",
            "TX_CHANNEL_SIZE",
            "RX_CHANNEL_SIZE",
            "RESOLVER_UDP_CONNECTION_POOL_SIZE",
            "ARQ_WINDOW_SIZE",
            "ARQ_INITIAL_RTO_SECONDS",
            "ARQ_MAX_RTO_SECONDS",
            "ARQ_CONTROL_MAX_RTO_SECONDS",
            "ARQ_MAX_CONTROL_RETRIES",
            "ARQ_MAX_DATA_RETRIES",
            "ARQ_DATA_NACK_INITIAL_DELAY_SECONDS",
            "ARQ_DATA_NACK_REPEAT_SECONDS",
            "ARQ_INACTIVITY_TIMEOUT_SECONDS",
            "LOG_LEVEL"
        )

        val ADVANCED_SETTING_KEYS = setOf(
            "LISTEN_IP",
            "SOCKS5_AUTH",
            "SOCKS5_USER",
            "SOCKS5_PASS",
            "LOCAL_DNS_ENABLED",
            "STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD",
            "STREAM_RESOLVER_FAILOVER_COOLDOWN",
            "RECHECK_SERVER_INTERVAL_SECONDS",
            "RECHECK_BATCH_SIZE",
            "RECHECK_INACTIVE_SERVERS_ENABLED",
            "AUTO_DISABLE_TIMEOUT_SERVERS",
            "AUTO_DISABLE_TIMEOUT_WINDOW_SECONDS",
            "AUTO_DISABLE_MIN_OBSERVATIONS",
            "AUTO_DISABLE_CHECK_INTERVAL_SECONDS",
            "BASE_ENCODE_DATA",
            "COMPRESSION_MIN_SIZE",
            "MIN_UPLOAD_MTU",
            "MIN_DOWNLOAD_MTU",
            "MAX_UPLOAD_MTU",
            "MAX_DOWNLOAD_MTU",
            "MTU_TEST_RETRIES",
            "MTU_TEST_TIMEOUT",
            "MTU_TEST_PARALLELISM",
            "SAVE_MTU_SERVERS_TO_FILE",
            "MTU_SERVERS_FILE_NAME",
            "MTU_SERVERS_FILE_FORMAT",
            "MTU_USING_SECTION_SEPARATOR_TEXT",
            "MTU_REMOVED_SERVER_LOG_FORMAT",
            "MTU_ADDED_SERVER_LOG_FORMAT",
            "RX_TX_WORKERS",
            "TUNNEL_PROCESS_WORKERS",
            "TUNNEL_PACKET_TIMEOUT_SECONDS",
            "TX_CHANNEL_SIZE",
            "RX_CHANNEL_SIZE",
            "RESOLVER_UDP_CONNECTION_POOL_SIZE",
            "ARQ_WINDOW_SIZE",
            "ARQ_INITIAL_RTO_SECONDS",
            "ARQ_MAX_RTO_SECONDS",
            "ARQ_CONTROL_MAX_RTO_SECONDS",
            "ARQ_MAX_CONTROL_RETRIES",
            "ARQ_MAX_DATA_RETRIES",
            "ARQ_DATA_NACK_INITIAL_DELAY_SECONDS",
            "ARQ_DATA_NACK_REPEAT_SECONDS",
            "ARQ_INACTIVITY_TIMEOUT_SECONDS"
        )
    }
}
