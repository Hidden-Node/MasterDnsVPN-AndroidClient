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
        val parsed = value?.trim()?.toIntOrNull()
        if (parsed != null && parsed in 1..8) return parsed
        if (parsed == 0) return 2
        return if (fallback in 1..8) fallback else 2
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
            "LOCAL_DNS_IP",
            "LOCAL_DNS_PORT",
            "LOCAL_DNS_CACHE_MAX_RECORDS",
            "LOCAL_DNS_CACHE_TTL_SECONDS",
            "LOCAL_DNS_PENDING_TIMEOUT_SECONDS",
            "DNS_RESPONSE_FRAGMENT_TIMEOUT_SECONDS",
            "LOCAL_DNS_CACHE_PERSIST_TO_FILE",
            "LOCAL_DNS_CACHE_FLUSH_INTERVAL_SECONDS",
            "RESOLVER_BALANCING_STRATEGY",
            "PACKET_DUPLICATION_COUNT",
            "SETUP_PACKET_DUPLICATION_COUNT",
            "STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD",
            "STREAM_RESOLVER_FAILOVER_COOLDOWN",
            "RECHECK_INACTIVE_SERVERS_ENABLED",
            "AUTO_DISABLE_TIMEOUT_SERVERS",
            "AUTO_DISABLE_TIMEOUT_WINDOW_SECONDS",
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
            "MTU_EXPORT_URI",
            "RX_TX_WORKERS",
            "TUNNEL_PROCESS_WORKERS",
            "TUNNEL_PACKET_TIMEOUT_SECONDS",
            "RX_CHANNEL_SIZE",
            "DISPATCHER_IDLE_POLL_INTERVAL_SECONDS",
            "SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS",
            "CLIENT_TERMINAL_STREAM_RETENTION_SECONDS",
            "CLIENT_CANCELLED_SETUP_RETENTION_SECONDS",
            "SESSION_INIT_RETRY_BASE_SECONDS",
            "SESSION_INIT_RETRY_STEP_SECONDS",
            "SESSION_INIT_RETRY_LINEAR_AFTER",
            "SESSION_INIT_RETRY_MAX_SECONDS",
            "SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS",
            "SESSION_INIT_RACING_COUNT",
            "PING_AGGRESSIVE_INTERVAL_SECONDS",
            "PING_LAZY_INTERVAL_SECONDS",
            "PING_COOLDOWN_INTERVAL_SECONDS",
            "PING_COLD_INTERVAL_SECONDS",
            "PING_WARM_THRESHOLD_SECONDS",
            "PING_COOL_THRESHOLD_SECONDS",
            "PING_COLD_THRESHOLD_SECONDS",
            "MAX_PACKETS_PER_BATCH",
            "ARQ_WINDOW_SIZE",
            "ARQ_INITIAL_RTO_SECONDS",
            "ARQ_MAX_RTO_SECONDS",
            "ARQ_CONTROL_INITIAL_RTO_SECONDS",
            "ARQ_CONTROL_MAX_RTO_SECONDS",
            "ARQ_MAX_CONTROL_RETRIES",
            "ARQ_MAX_DATA_RETRIES",
            "ARQ_DATA_PACKET_TTL_SECONDS",
            "ARQ_CONTROL_PACKET_TTL_SECONDS",
            "ARQ_DATA_NACK_MAX_GAP",
            "ARQ_DATA_NACK_INITIAL_DELAY_SECONDS",
            "ARQ_DATA_NACK_REPEAT_SECONDS",
            "ARQ_INACTIVITY_TIMEOUT_SECONDS",
            "ARQ_TERMINAL_DRAIN_TIMEOUT_SECONDS",
            "ARQ_TERMINAL_ACK_WAIT_TIMEOUT_SECONDS",
            "LOG_LEVEL"
        )

        val ADVANCED_SETTING_KEYS = setOf(
            "LISTEN_IP",
            "SOCKS5_AUTH",
            "SOCKS5_USER",
            "SOCKS5_PASS",
            "LOCAL_DNS_ENABLED",
            "LOCAL_DNS_IP",
            "LOCAL_DNS_PORT",
            "LOCAL_DNS_CACHE_MAX_RECORDS",
            "LOCAL_DNS_CACHE_TTL_SECONDS",
            "LOCAL_DNS_PENDING_TIMEOUT_SECONDS",
            "DNS_RESPONSE_FRAGMENT_TIMEOUT_SECONDS",
            "LOCAL_DNS_CACHE_PERSIST_TO_FILE",
            "LOCAL_DNS_CACHE_FLUSH_INTERVAL_SECONDS",
            "STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD",
            "STREAM_RESOLVER_FAILOVER_COOLDOWN",
            "RECHECK_INACTIVE_SERVERS_ENABLED",
            "AUTO_DISABLE_TIMEOUT_SERVERS",
            "AUTO_DISABLE_TIMEOUT_WINDOW_SECONDS",
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
            "MTU_EXPORT_URI",
            "RX_TX_WORKERS",
            "TUNNEL_PROCESS_WORKERS",
            "TUNNEL_PACKET_TIMEOUT_SECONDS",
            "RX_CHANNEL_SIZE",
            "DISPATCHER_IDLE_POLL_INTERVAL_SECONDS",
            "SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS",
            "CLIENT_TERMINAL_STREAM_RETENTION_SECONDS",
            "CLIENT_CANCELLED_SETUP_RETENTION_SECONDS",
            "SESSION_INIT_RETRY_BASE_SECONDS",
            "SESSION_INIT_RETRY_STEP_SECONDS",
            "SESSION_INIT_RETRY_LINEAR_AFTER",
            "SESSION_INIT_RETRY_MAX_SECONDS",
            "SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS",
            "SESSION_INIT_RACING_COUNT",
            "PING_AGGRESSIVE_INTERVAL_SECONDS",
            "PING_LAZY_INTERVAL_SECONDS",
            "PING_COOLDOWN_INTERVAL_SECONDS",
            "PING_COLD_INTERVAL_SECONDS",
            "PING_WARM_THRESHOLD_SECONDS",
            "PING_COOL_THRESHOLD_SECONDS",
            "PING_COLD_THRESHOLD_SECONDS",
            "MAX_PACKETS_PER_BATCH",
            "ARQ_WINDOW_SIZE",
            "ARQ_INITIAL_RTO_SECONDS",
            "ARQ_MAX_RTO_SECONDS",
            "ARQ_CONTROL_INITIAL_RTO_SECONDS",
            "ARQ_CONTROL_MAX_RTO_SECONDS",
            "ARQ_MAX_CONTROL_RETRIES",
            "ARQ_MAX_DATA_RETRIES",
            "ARQ_DATA_PACKET_TTL_SECONDS",
            "ARQ_CONTROL_PACKET_TTL_SECONDS",
            "ARQ_DATA_NACK_MAX_GAP",
            "ARQ_DATA_NACK_INITIAL_DELAY_SECONDS",
            "ARQ_DATA_NACK_REPEAT_SECONDS",
            "ARQ_INACTIVITY_TIMEOUT_SECONDS",
            "ARQ_TERMINAL_DRAIN_TIMEOUT_SECONDS",
            "ARQ_TERMINAL_ACK_WAIT_TIMEOUT_SECONDS"
        )
    }
}
