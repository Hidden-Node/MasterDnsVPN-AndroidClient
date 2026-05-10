package com.masterdns.vpn.data.repository

import com.masterdns.vpn.data.local.IdentityCipher
import com.masterdns.vpn.data.local.ProfileDao
import com.masterdns.vpn.data.local.ProfileEntity
import com.masterdns.vpn.util.ConfigGenerator
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao
) {
    private val gson = Gson()

    fun getAllProfiles(): Flow<List<ProfileEntity>> = profileDao.getAllProfiles()

    fun getSelectedProfileFlow(): Flow<ProfileEntity?> = profileDao.getSelectedProfileFlow()

    suspend fun getSelectedProfile(): ProfileEntity? = profileDao.getSelectedProfile()

    suspend fun getProfileById(id: Long): ProfileEntity? = profileDao.getProfileById(id)
    fun getProfileByIdFlow(id: Long): Flow<ProfileEntity?> = profileDao.getProfileByIdFlow(id)

    suspend fun insertProfile(profile: ProfileEntity): Long = profileDao.insertProfile(profile)

    suspend fun updateProfile(profile: ProfileEntity) = profileDao.updateProfile(profile)

    suspend fun deleteProfile(profile: ProfileEntity) = profileDao.deleteProfile(profile)

    suspend fun setSelectedProfile(id: Long) = profileDao.setSelectedProfile(id)

    /**
     * Export a profile as a TOML string.
     * If [lockIdentity] is true, DOMAINS and ENCRYPTION_KEY are AES-256-GCM encrypted
     * so the recipient can use the profile without seeing the actual values.
     */
    suspend fun exportProfileToml(id: Long, lockIdentity: Boolean = false): String? {
        val profile = profileDao.getProfileById(id) ?: return null
        return ConfigGenerator.exportToml(profile, lockIdentity)
    }

    /**
     * Import a profile from a TOML string.
     * If IDENTITY_LOCKED = true in the TOML, domains and key are decrypted before storage.
     */
    suspend fun importProfileFromToml(tomlContent: String, name: String): ProfileEntity? {
        val profile = parseTomlToProfile(tomlContent, name) ?: return null
        val id = profileDao.insertProfile(profile)
        return profile.copy(id = id)
    }

    fun previewProfileFromToml(tomlContent: String, name: String): ProfileEntity? {
        return parseTomlToProfile(tomlContent, name)
    }

    private fun parseTomlToProfile(tomlContent: String, name: String): ProfileEntity? {
        val values = mutableMapOf<String, String>()
        tomlContent.lineSequence().forEach { raw ->
            val line = raw.substringBefore("#").trim()
            if (line.isEmpty() || "=" !in line) return@forEach
            val key = line.substringBefore("=").trim()
            val valueRaw = line.substringAfter("=").trim()
            val parsed = when {
                key == "DOMAINS" -> {
                    if (valueRaw.startsWith("[")) {
                        // Array format: ["a.com", "b.com"]
                        valueRaw
                            .removePrefix("[").removeSuffix("]")
                            .split(",")
                            .map { it.trim().removeSurrounding("\"") }
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                    } else {
                        // Single string format: "ENC:..." or "value"
                        valueRaw.removeSurrounding("\"")
                    }
                }
                valueRaw.startsWith("\"") && valueRaw.endsWith("\"") ->
                    valueRaw.removeSurrounding("\"")
                else -> valueRaw
            }
            values[key] = parsed
        }

        val isLocked = values["IDENTITY_LOCKED"]?.trim()?.equals("true", ignoreCase = true) == true

        val rawDomains = values["DOMAINS"]?.takeIf { it.isNotBlank() } ?: return null
        val rawKey = values["ENCRYPTION_KEY"]?.takeIf { it.isNotBlank() } ?: return null

        val finalDomains: String
        val finalKey: String

        if (isLocked) {
            // Try single-encrypted format first (new format: whole domains string encrypted at once)
            val trimmedDomains = rawDomains.trim()
            if (IdentityCipher.isEncrypted(trimmedDomains)) {
                val decrypted = IdentityCipher.decrypt(trimmedDomains)
                if (decrypted != null) {
                    finalDomains = gson.toJson(decrypted.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                } else {
                    // Decryption failed — do not store encrypted garbage
                    return null
                }
            } else {
                // Legacy format: each domain encrypted separately
                val decryptedDomains = rawDomains.split(",")
                    .map { it.trim() }
                    .map { d ->
                        if (IdentityCipher.isEncrypted(d)) {
                            IdentityCipher.decrypt(d) ?: return null // fail if any domain can't be decrypted
                        } else d
                    }
                finalDomains = gson.toJson(decryptedDomains)
            }
            finalKey = if (IdentityCipher.isEncrypted(rawKey)) {
                IdentityCipher.decrypt(rawKey) ?: return null
            } else rawKey
        } else {
            finalDomains = gson.toJson(rawDomains.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            finalKey = rawKey
        }

        val advanced = mutableMapOf<String, String>()
        IMPORT_ADVANCED_KEYS.forEach { key -> values[key]?.let { advanced[key] = it.trim() } }

        return ProfileEntity(
            name = name,
            domains = finalDomains,
            encryptionMethod = values["DATA_ENCRYPTION_METHOD"]?.toIntOrNull() ?: 1,
            encryptionKey = finalKey,
            protocolType = when (values["PROTOCOL_TYPE"]?.trim()?.uppercase()) { "TCP" -> "TCP" else -> "SOCKS5" },
            listenPort = values["LISTEN_PORT"]?.toIntOrNull()?.coerceIn(1, 65535) ?: 18000,
            resolverBalancingStrategy = values["RESOLVER_BALANCING_STRATEGY"]?.toIntOrNull() ?: 2,
            packetDuplicationCount = values["PACKET_DUPLICATION_COUNT"]?.toIntOrNull() ?: 2,
            setupPacketDuplicationCount = values["SETUP_PACKET_DUPLICATION_COUNT"]?.toIntOrNull() ?: 2,
            uploadCompression = values["UPLOAD_COMPRESSION_TYPE"]?.toIntOrNull() ?: 0,
            downloadCompression = values["DOWNLOAD_COMPRESSION_TYPE"]?.toIntOrNull() ?: 0,
            logLevel = values["LOG_LEVEL"]?.trim().takeUnless { it.isNullOrBlank() } ?: "INFO",
            resolvers = "8.8.8.8",
            advancedJson = gson.toJson(advanced),
        )
    }

    companion object {
        private val IMPORT_ADVANCED_KEYS = setOf(
            "LISTEN_IP", "SOCKS5_AUTH", "SOCKS5_USER", "SOCKS5_PASS",
            "LOCAL_DNS_ENABLED", "LOCAL_DNS_IP", "LOCAL_DNS_PORT",
            "LOCAL_DNS_CACHE_MAX_RECORDS", "LOCAL_DNS_CACHE_TTL_SECONDS",
            "LOCAL_DNS_PENDING_TIMEOUT_SECONDS", "DNS_RESPONSE_FRAGMENT_TIMEOUT_SECONDS",
            "LOCAL_DNS_CACHE_PERSIST_TO_FILE", "LOCAL_DNS_CACHE_FLUSH_INTERVAL_SECONDS",
            "STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD", "STREAM_RESOLVER_FAILOVER_COOLDOWN",
            "RECHECK_INACTIVE_SERVERS_ENABLED", "AUTO_DISABLE_TIMEOUT_SERVERS",
            "AUTO_DISABLE_TIMEOUT_WINDOW_SECONDS", "BASE_ENCODE_DATA", "COMPRESSION_MIN_SIZE",
            "MIN_UPLOAD_MTU", "MIN_DOWNLOAD_MTU", "MAX_UPLOAD_MTU", "MAX_DOWNLOAD_MTU",
            "MTU_TEST_RETRIES", "MTU_TEST_TIMEOUT", "MTU_TEST_PARALLELISM",
            "SAVE_MTU_SERVERS_TO_FILE", "MTU_SERVERS_FILE_NAME", "MTU_SERVERS_FILE_FORMAT",
            "MTU_USING_SECTION_SEPARATOR_TEXT", "MTU_REMOVED_SERVER_LOG_FORMAT",
            "MTU_ADDED_SERVER_LOG_FORMAT", "MTU_REACTIVE_ADDED_SERVER_LOG_FORMAT",
            "MTU_EXPORT_URI",
            "RX_TX_WORKERS", "TUNNEL_PROCESS_WORKERS", "TUNNEL_PACKET_TIMEOUT_SECONDS",
            "DISPATCHER_IDLE_POLL_INTERVAL_SECONDS", "RX_CHANNEL_SIZE",
            "SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS",
            "CLIENT_TERMINAL_STREAM_RETENTION_SECONDS",
            "CLIENT_CANCELLED_SETUP_RETENTION_SECONDS",
            "SESSION_INIT_RETRY_BASE_SECONDS", "SESSION_INIT_RETRY_STEP_SECONDS",
            "SESSION_INIT_RETRY_LINEAR_AFTER", "SESSION_INIT_RETRY_MAX_SECONDS",
            "SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS", "SESSION_INIT_RACING_COUNT",
            "PING_AGGRESSIVE_INTERVAL_SECONDS", "PING_LAZY_INTERVAL_SECONDS",
            "PING_COOLDOWN_INTERVAL_SECONDS", "PING_COLD_INTERVAL_SECONDS",
            "PING_WARM_THRESHOLD_SECONDS", "PING_COOL_THRESHOLD_SECONDS",
            "PING_COLD_THRESHOLD_SECONDS",
            "MAX_PACKETS_PER_BATCH", "ARQ_WINDOW_SIZE", "ARQ_INITIAL_RTO_SECONDS",
            "ARQ_MAX_RTO_SECONDS", "ARQ_CONTROL_INITIAL_RTO_SECONDS",
            "ARQ_CONTROL_MAX_RTO_SECONDS", "ARQ_MAX_CONTROL_RETRIES",
            "ARQ_MAX_DATA_RETRIES", "ARQ_DATA_PACKET_TTL_SECONDS",
            "ARQ_CONTROL_PACKET_TTL_SECONDS", "ARQ_DATA_NACK_MAX_GAP",
            "ARQ_DATA_NACK_INITIAL_DELAY_SECONDS", "ARQ_DATA_NACK_REPEAT_SECONDS",
            "ARQ_INACTIVITY_TIMEOUT_SECONDS", "ARQ_TERMINAL_DRAIN_TIMEOUT_SECONDS",
            "ARQ_TERMINAL_ACK_WAIT_TIMEOUT_SECONDS"
        )
    }
}
