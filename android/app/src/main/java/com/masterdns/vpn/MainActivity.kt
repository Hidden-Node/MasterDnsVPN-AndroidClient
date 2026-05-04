package com.masterdns.vpn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.masterdns.vpn.data.local.ProfileEntity
import com.masterdns.vpn.data.repository.ProfileRepository
import com.masterdns.vpn.ui.navigation.AppNavigation
import com.masterdns.vpn.ui.theme.MasterDnsVPNTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var profileRepository: ProfileRepository

    private val gson = Gson()
    @Volatile
    private var lastHandledImportUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleTomlImportIntent(intent)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MasterDnsVPNTheme {
                    AppNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTomlImportIntent(intent)
    }

    private fun handleTomlImportIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return

        val uri = when {
            intent.data != null -> intent.data
            intent.clipData != null && intent.clipData!!.itemCount > 0 -> intent.clipData!!.getItemAt(0).uri
            else -> null
        } ?: return

        val uriToken = uri.toString()
        if (lastHandledImportUri == uriToken) return
        val mime = intent.type.orEmpty()
        val isTomlLike = mime.contains("toml", ignoreCase = true) ||
            uri.toString().lowercase().endsWith(".toml")
        if (!isTomlLike) return

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        lifecycleScope.launch {
            val content = readTextFromUri(uri)
            if (content.isBlank()) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.profiles_invalid_toml_msg),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val imported = parseImportedProfile(uri, content)
            if (imported == null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.profiles_invalid_toml_msg),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            lastHandledImportUri = uriToken
            val id = profileRepository.insertProfile(imported)
            profileRepository.setSelectedProfile(id)
            Toast.makeText(
                this@MainActivity,
                getString(R.string.profiles_toml_imported_msg),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        return contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.orEmpty()
    }

    private fun parseImportedProfile(uri: Uri, tomlContent: String): ProfileEntity? {
        val values = parseTomlValues(tomlContent)
        val parsedDomain = values["DOMAINS"]?.takeIf { it.isNotBlank() } ?: values["DOMAIN"]?.takeIf { it.isNotBlank() } ?: return null
        val parsedKey = values["ENCRYPTION_KEY"]?.takeIf { it.isNotBlank() } ?: return null
        val domainList = parsedDomain.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (domainList.isEmpty()) return null

        val advanced = mutableMapOf<String, String>()
        IMPORT_ADVANCED_KEYS.forEach { key ->
            values[key]?.let { advanced[key] = it.trim() }
        }

        val fileName = readDisplayName(uri) ?: "Imported Profile"

        return ProfileEntity(
            name = fileName,
            domains = gson.toJson(domainList),
            encryptionMethod = values["DATA_ENCRYPTION_METHOD"]?.toIntOrNull() ?: 1,
            encryptionKey = parsedKey,
            protocolType = normalizeProtocol(values["PROTOCOL_TYPE"]),
            listenPort = values["LISTEN_PORT"]?.toIntOrNull()?.coerceIn(1, 65535) ?: 18000,
            resolverBalancingStrategy = values["RESOLVER_BALANCING_STRATEGY"]?.toIntOrNull() ?: 2,
            packetDuplicationCount = values["PACKET_DUPLICATION_COUNT"]?.toIntOrNull() ?: 2,
            setupPacketDuplicationCount = values["SETUP_PACKET_DUPLICATION_COUNT"]?.toIntOrNull() ?: 2,
            uploadCompression = values["UPLOAD_COMPRESSION_TYPE"]?.toIntOrNull() ?: 0,
            downloadCompression = values["DOWNLOAD_COMPRESSION_TYPE"]?.toIntOrNull() ?: 0,
            logLevel = values["LOG_LEVEL"]?.trim().takeUnless { it.isNullOrBlank() } ?: "INFO",
            resolvers = "8.8.8.8",
            advancedJson = gson.toJson(advanced)
        )
    }

    private fun readDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx < 0 || !cursor.moveToFirst()) return@use null
                cursor.getString(idx)
            }
        }.getOrNull()
            ?.substringBeforeLast(".")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun parseTomlValues(tomlContent: String): Map<String, String> {
        val values = mutableMapOf<String, String>()
        tomlContent.lineSequence().forEach { raw ->
            val line = raw.substringBefore("#").trim()
            if (line.isEmpty() || "=" !in line) return@forEach
            val key = line.substringBefore("=").trim()
            val valueRaw = line.substringAfter("=").trim()
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
            values[key] = parsed
        }
        return values
    }

    private fun normalizeProtocol(value: String?): String {
        return when (value?.trim()?.uppercase()) {
            "TCP" -> "TCP"
            else -> "SOCKS5"
        }
    }

    companion object {
        private val IMPORT_ADVANCED_KEYS = setOf(
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
            "MTU_REACTIVE_ADDED_SERVER_LOG_FORMAT",
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
