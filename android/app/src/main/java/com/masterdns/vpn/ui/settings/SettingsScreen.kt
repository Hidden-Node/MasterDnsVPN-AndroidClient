package com.masterdns.vpn.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.masterdns.vpn.data.local.ProfileEntity
import kotlinx.coroutines.launch

private enum class FieldType { TEXT, BOOL, OPTION }

private data class SettingField(
    val section: String,
    val key: String,
    val label: String,
    val helper: String,
    val type: FieldType = FieldType.TEXT,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val options: List<String> = emptyList()
)

private val configFields = listOf(
    SettingField("Identity", "DOMAINS", "DOMAINS", "Comma-separated domains"),
    SettingField(
        "Identity",
        "DATA_ENCRYPTION_METHOD",
        "DATA_ENCRYPTION_METHOD",
        "0=None, 1=XOR, 2=ChaCha20, 3-5=AES-GCM",
        type = FieldType.OPTION,
        options = listOf("0", "1", "2", "3", "4", "5")
    ),
    SettingField("Identity", "ENCRYPTION_KEY", "ENCRYPTION_KEY", "Shared key with server"),
    SettingField(
        "Proxy",
        "PROTOCOL_TYPE",
        "PROTOCOL_TYPE",
        "Client local proxy protocol",
        type = FieldType.OPTION,
        options = listOf("SOCKS5", "TCP")
    ),
    SettingField("Proxy", "LISTEN_IP", "LISTEN_IP", "Local bind IP"),
    SettingField(
        "Proxy",
        "LISTEN_PORT",
        "LISTEN_PORT",
        "Local proxy port",
        keyboardType = KeyboardType.Number
    ),
    SettingField("Proxy", "SOCKS5_AUTH", "SOCKS5_AUTH", "Enable SOCKS5 auth", type = FieldType.BOOL),
    SettingField("Proxy", "SOCKS5_USER", "SOCKS5_USER", "SOCKS username"),
    SettingField("Proxy", "SOCKS5_PASS", "SOCKS5_PASS", "SOCKS password"),
    SettingField("DNS", "LOCAL_DNS_ENABLED", "LOCAL_DNS_ENABLED", "Enable local DNS mode", type = FieldType.BOOL),
    SettingField("DNS", "LOCAL_DNS_IP", "LOCAL_DNS_IP", "Local DNS bind IP"),
    SettingField("DNS", "LOCAL_DNS_PORT", "LOCAL_DNS_PORT", "Local DNS bind port", keyboardType = KeyboardType.Number),
    SettingField("DNS", "LOCAL_DNS_CACHE_MAX_RECORDS", "LOCAL_DNS_CACHE_MAX_RECORDS", "Local DNS cache max records", keyboardType = KeyboardType.Number),
    SettingField("DNS", "LOCAL_DNS_CACHE_TTL_SECONDS", "LOCAL_DNS_CACHE_TTL_SECONDS", "Local DNS cache TTL seconds", keyboardType = KeyboardType.Decimal),
    SettingField("DNS", "LOCAL_DNS_PENDING_TIMEOUT_SECONDS", "LOCAL_DNS_PENDING_TIMEOUT_SECONDS", "Local DNS pending timeout seconds", keyboardType = KeyboardType.Decimal),
    SettingField("DNS", "DNS_RESPONSE_FRAGMENT_TIMEOUT_SECONDS", "DNS_RESPONSE_FRAGMENT_TIMEOUT_SECONDS", "DNS response fragment timeout seconds", keyboardType = KeyboardType.Decimal),
    SettingField("DNS", "LOCAL_DNS_CACHE_PERSIST_TO_FILE", "LOCAL_DNS_CACHE_PERSIST_TO_FILE", "Persist local DNS cache to file", type = FieldType.BOOL),
    SettingField("DNS", "LOCAL_DNS_CACHE_FLUSH_INTERVAL_SECONDS", "LOCAL_DNS_CACHE_FLUSH_INTERVAL_SECONDS", "Local DNS cache flush interval seconds", keyboardType = KeyboardType.Decimal),
    SettingField(
        "Resolver",
        "RESOLVER_BALANCING_STRATEGY",
        "RESOLVER_BALANCING_STRATEGY",
        "Resolver balancing strategy",
        type = FieldType.OPTION,
        options = listOf(
            "1 - Random",
            "2 - Round Robin",
            "3 - Least Loss",
            "4 - Lowest Latency",
            "5 - Hybrid Score",
            "6 - Loss Then Latency",
            "7 - Least Loss Top Random",
            "8 - Least Loss Top Round Robin"
        )
    ),
    SettingField("Resolver", "PACKET_DUPLICATION_COUNT", "PACKET_DUPLICATION_COUNT", "Runtime packet duplication count", keyboardType = KeyboardType.Number),
    SettingField("Resolver", "SETUP_PACKET_DUPLICATION_COUNT", "SETUP_PACKET_DUPLICATION_COUNT", "Setup packet duplication count", keyboardType = KeyboardType.Number),
    SettingField("Resolver", "STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD", "STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD", "Resends before stream failover", keyboardType = KeyboardType.Number),
    SettingField("Resolver", "STREAM_RESOLVER_FAILOVER_COOLDOWN", "STREAM_RESOLVER_FAILOVER_COOLDOWN", "Failover cooldown seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Resolver", "RECHECK_INACTIVE_SERVERS_ENABLED", "RECHECK_INACTIVE_SERVERS_ENABLED", "Re-check inactive resolvers", type = FieldType.BOOL),
    SettingField("Resolver", "AUTO_DISABLE_TIMEOUT_SERVERS", "AUTO_DISABLE_TIMEOUT_SERVERS", "Auto disable timeout resolvers", type = FieldType.BOOL),
    SettingField("Resolver", "AUTO_DISABLE_TIMEOUT_WINDOW_SECONDS", "AUTO_DISABLE_TIMEOUT_WINDOW_SECONDS", "Timeout-only window seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Resolver", "BASE_ENCODE_DATA", "BASE_ENCODE_DATA", "Use base-encoded payloads", type = FieldType.BOOL),
    SettingField("Compression", "UPLOAD_COMPRESSION_TYPE", "UPLOAD_COMPRESSION_TYPE", "0=Off, 1=Snappy, 2=LZ4, 3=ZSTD, 4=Gzip, 5=Zlib", keyboardType = KeyboardType.Number),
    SettingField("Compression", "DOWNLOAD_COMPRESSION_TYPE", "DOWNLOAD_COMPRESSION_TYPE", "0=Off, 1=Snappy, 2=LZ4, 3=ZSTD, 4=Gzip, 5=Zlib", keyboardType = KeyboardType.Number),
    SettingField("Compression", "COMPRESSION_MIN_SIZE", "COMPRESSION_MIN_SIZE", "Min bytes to trigger compression", keyboardType = KeyboardType.Number),
    SettingField("MTU", "MIN_UPLOAD_MTU", "MIN_UPLOAD_MTU", "Minimum upload MTU bytes", keyboardType = KeyboardType.Number),
    SettingField("MTU", "MIN_DOWNLOAD_MTU", "MIN_DOWNLOAD_MTU", "Minimum download MTU bytes", keyboardType = KeyboardType.Number),
    SettingField("MTU", "MAX_UPLOAD_MTU", "MAX_UPLOAD_MTU", "Maximum upload MTU bytes", keyboardType = KeyboardType.Number),
    SettingField("MTU", "MAX_DOWNLOAD_MTU", "MAX_DOWNLOAD_MTU", "Maximum download MTU bytes", keyboardType = KeyboardType.Number),
    SettingField("MTU", "MTU_TEST_RETRIES", "MTU_TEST_RETRIES", "Retries per MTU probe", keyboardType = KeyboardType.Number),
    SettingField("MTU", "MTU_TEST_TIMEOUT", "MTU_TEST_TIMEOUT", "Probe timeout seconds", keyboardType = KeyboardType.Decimal),
    SettingField("MTU", "MTU_TEST_PARALLELISM", "MTU_TEST_PARALLELISM", "Parallel probe workers", keyboardType = KeyboardType.Number),
    SettingField("MTU", "SAVE_MTU_SERVERS_TO_FILE", "SAVE_MTU_SERVERS_TO_FILE", "Persist successful MTU resolvers to file", type = FieldType.BOOL),
    SettingField("MTU", "MTU_SERVERS_FILE_NAME", "MTU_SERVERS_FILE_NAME", "Output file name/path (absolute path supported)"),
    SettingField("MTU", "MTU_SERVERS_FILE_FORMAT", "MTU_SERVERS_FILE_FORMAT", "Format: {IP} {UP_MTU} {DOWN-MTU}"),
    SettingField("MTU", "MTU_USING_SECTION_SEPARATOR_TEXT", "MTU_USING_SECTION_SEPARATOR_TEXT", "Optional separator text between sections"),
    SettingField("MTU", "MTU_REMOVED_SERVER_LOG_FORMAT", "MTU_REMOVED_SERVER_LOG_FORMAT", "Log format when resolver is removed"),
    SettingField("MTU", "MTU_ADDED_SERVER_LOG_FORMAT", "MTU_ADDED_SERVER_LOG_FORMAT", "Log format when resolver is re-added"),
    SettingField("MTU", "MTU_REACTIVE_ADDED_SERVER_LOG_FORMAT", "MTU_REACTIVE_ADDED_SERVER_LOG_FORMAT", "Log format when resolver is re-added after reactive recheck"),
    SettingField("Runtime", "RX_TX_WORKERS", "RX_TX_WORKERS", "Combined RX/TX worker count", keyboardType = KeyboardType.Number),
    SettingField("Runtime", "TUNNEL_PROCESS_WORKERS", "TUNNEL_PROCESS_WORKERS", "Processor worker count", keyboardType = KeyboardType.Number),
    SettingField("Runtime", "TUNNEL_PACKET_TIMEOUT_SECONDS", "TUNNEL_PACKET_TIMEOUT_SECONDS", "Packet timeout seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "RX_CHANNEL_SIZE", "RX_CHANNEL_SIZE", "RX channel size", keyboardType = KeyboardType.Number),
    SettingField("Runtime", "DISPATCHER_IDLE_POLL_INTERVAL_SECONDS", "DISPATCHER_IDLE_POLL_INTERVAL_SECONDS", "Dispatcher idle poll interval seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS", "SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS", "SOCKS UDP associate read timeout seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "CLIENT_TERMINAL_STREAM_RETENTION_SECONDS", "CLIENT_TERMINAL_STREAM_RETENTION_SECONDS", "Terminal stream retention seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "CLIENT_CANCELLED_SETUP_RETENTION_SECONDS", "CLIENT_CANCELLED_SETUP_RETENTION_SECONDS", "Cancelled setup stream retention seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "SESSION_INIT_RETRY_BASE_SECONDS", "SESSION_INIT_RETRY_BASE_SECONDS", "Session init retry base seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "SESSION_INIT_RETRY_STEP_SECONDS", "SESSION_INIT_RETRY_STEP_SECONDS", "Session init retry step seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "SESSION_INIT_RETRY_LINEAR_AFTER", "SESSION_INIT_RETRY_LINEAR_AFTER", "Session init retry linear-after", keyboardType = KeyboardType.Number),
    SettingField("Runtime", "SESSION_INIT_RETRY_MAX_SECONDS", "SESSION_INIT_RETRY_MAX_SECONDS", "Session init retry max seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS", "SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS", "Session busy retry interval seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "SESSION_INIT_RACING_COUNT", "SESSION_INIT_RACING_COUNT", "Session init racing count", keyboardType = KeyboardType.Number),
    SettingField("Runtime", "PING_AGGRESSIVE_INTERVAL_SECONDS", "PING_AGGRESSIVE_INTERVAL_SECONDS", "Ping aggressive interval seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "PING_LAZY_INTERVAL_SECONDS", "PING_LAZY_INTERVAL_SECONDS", "Ping lazy interval seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "PING_COOLDOWN_INTERVAL_SECONDS", "PING_COOLDOWN_INTERVAL_SECONDS", "Ping cooldown interval seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "PING_COLD_INTERVAL_SECONDS", "PING_COLD_INTERVAL_SECONDS", "Ping cold interval seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "PING_WARM_THRESHOLD_SECONDS", "PING_WARM_THRESHOLD_SECONDS", "Ping warm threshold seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "PING_COOL_THRESHOLD_SECONDS", "PING_COOL_THRESHOLD_SECONDS", "Ping cool threshold seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "PING_COLD_THRESHOLD_SECONDS", "PING_COLD_THRESHOLD_SECONDS", "Ping cold threshold seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_WINDOW_SIZE", "ARQ_WINDOW_SIZE", "ARQ receive window size", keyboardType = KeyboardType.Number),
    SettingField("ARQ", "MAX_PACKETS_PER_BATCH", "MAX_PACKETS_PER_BATCH", "Max packets per batch", keyboardType = KeyboardType.Number),
    SettingField("ARQ", "ARQ_INITIAL_RTO_SECONDS", "ARQ_INITIAL_RTO_SECONDS", "Initial RTO seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_MAX_RTO_SECONDS", "ARQ_MAX_RTO_SECONDS", "Maximum RTO seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_CONTROL_INITIAL_RTO_SECONDS", "ARQ_CONTROL_INITIAL_RTO_SECONDS", "Control initial RTO seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_CONTROL_MAX_RTO_SECONDS", "ARQ_CONTROL_MAX_RTO_SECONDS", "Control packet max RTO seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_MAX_CONTROL_RETRIES", "ARQ_MAX_CONTROL_RETRIES", "Maximum retries per control packet", keyboardType = KeyboardType.Number),
    SettingField("ARQ", "ARQ_MAX_DATA_RETRIES", "ARQ_MAX_DATA_RETRIES", "Maximum retries per data packet", keyboardType = KeyboardType.Number),
    SettingField("ARQ", "ARQ_DATA_PACKET_TTL_SECONDS", "ARQ_DATA_PACKET_TTL_SECONDS", "ARQ data packet TTL seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_CONTROL_PACKET_TTL_SECONDS", "ARQ_CONTROL_PACKET_TTL_SECONDS", "ARQ control packet TTL seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_DATA_NACK_MAX_GAP", "ARQ_DATA_NACK_MAX_GAP", "ARQ data NACK max gap", keyboardType = KeyboardType.Number),
    SettingField("ARQ", "ARQ_DATA_NACK_INITIAL_DELAY_SECONDS", "ARQ_DATA_NACK_INITIAL_DELAY_SECONDS", "Initial delay before first NACK seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_DATA_NACK_REPEAT_SECONDS", "ARQ_DATA_NACK_REPEAT_SECONDS", "NACK repeat interval seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_INACTIVITY_TIMEOUT_SECONDS", "ARQ_INACTIVITY_TIMEOUT_SECONDS", "Inactivity timeout seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_TERMINAL_DRAIN_TIMEOUT_SECONDS", "ARQ_TERMINAL_DRAIN_TIMEOUT_SECONDS", "ARQ terminal drain timeout seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_TERMINAL_ACK_WAIT_TIMEOUT_SECONDS", "ARQ_TERMINAL_ACK_WAIT_TIMEOUT_SECONDS", "ARQ terminal ACK wait timeout seconds", keyboardType = KeyboardType.Decimal),
    SettingField(
        "Logging",
        "LOG_LEVEL",
        "LOG_LEVEL",
        "Client log level",
        type = FieldType.OPTION,
        options = listOf("DEBUG", "INFO", "WARN", "ERROR")
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val profile by viewModel.selectedProfile.collectAsState()
    val fieldsState = remember { mutableStateMapOf<String, String>() }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val sections = remember { configFields.groupBy { it.section } }
    val sectionExpanded = remember {
        mutableStateMapOf<String, Boolean>().apply {
            sections.keys.forEach { put(it, it == "Identity" || it == "Proxy") }
        }
    }

    var pendingExportContent by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-toml")
    ) { uri ->
        val content = pendingExportContent ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            writeTextToUri(context, uri, content)
            scope.launch { snackbarHostState.showSnackbar("TOML exported") }
        }
        pendingExportContent = null
    }

    val importTomlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = readTextFromUri(context, uri)
            val updated = viewModel.importTomlValues(text, fieldsState.toMap())
            fieldsState.clear()
            fieldsState.putAll(updated)
            scope.launch { snackbarHostState.showSnackbar("TOML imported to form") }
        }
    }

    val importResolversLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val selected = profile
        if (uri != null && selected != null) {
            val text = readTextFromUri(context, uri)
            viewModel.importResolvers(selected, text)
            scope.launch { snackbarHostState.showSnackbar("Resolvers imported into profile") }
        }
    }
    val pickMtuExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.grantUriPermission(
                    context.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            fieldsState["MTU_EXPORT_URI"] = uri.toString()
            scope.launch { snackbarHostState.showSnackbar("MTU export destination selected") }
        }
    }

    LaunchedEffect(profile?.id) {
        fieldsState.clear()
        profile?.let { fieldsState.putAll(defaultValuesFor(it)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = {
                    val selected = profile
                    if (selected != null) {
                        IconButton(
                            onClick = {
                                viewModel.saveSettings(selected, fieldsState.toMap())
                                scope.launch { snackbarHostState.showSnackbar("Profile settings saved and applied") }
                            }
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        val selected = profile
        if (selected == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No selected profile", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Create/select a profile in Profiles tab, then configure client_config values here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            state = listState
        ) {
            item {
                Text(
                    text = "Editing profile: ${selected.name}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            importTomlLauncher.launch(
                                arrayOf(
                                    "application/toml",
                                    "text/x-toml",
                                    "text/plain",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        }
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import TOML")
                    }
                    Button(
                        onClick = {
                            pendingExportContent = viewModel.exportConfigToml(selected, fieldsState.toMap())
                            exportLauncher.launch("${selected.name}_client_config.toml")
                        }
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export TOML")
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Button(onClick = { importResolversLauncher.launch(arrayOf("text/*")) }) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import client_resolvers.txt")
                }
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = {
                        val selectedName = selected.name.trim().ifBlank { "profile" }
                        pickMtuExportLauncher.launch("${selectedName}_mtu_results.log")
                    }
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pick MTU export destination")
                }
            }

            val socksAuthEnabled = fieldsState["SOCKS5_AUTH"].equals("true", ignoreCase = true)
            items(sections.keys.toList(), key = { "section_$it" }) { section ->
                val expanded = sectionExpanded[section] ?: false
                Card(
                    onClick = { sectionExpanded[section] = !expanded },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = section,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(if (expanded) "Hide" else "Show", color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (!expanded) return@items

                Spacer(modifier = Modifier.height(6.dp))
                if (section == "DNS") {
                    val dnsEnabled = fieldsState["LOCAL_DNS_ENABLED"].equals("true", ignoreCase = true)
                    val dnsPort = fieldsState["LOCAL_DNS_PORT"]?.toIntOrNull() ?: 53
                    if (dnsEnabled && dnsPort <= 1024) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⚠ Port $dnsPort requires root access on Android. " +
                                        "The app will automatically use port 5353 instead.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                sections[section].orEmpty().forEach { field ->
                    if ((field.key == "SOCKS5_USER" || field.key == "SOCKS5_PASS") && !socksAuthEnabled) {
                        return@forEach
                    }
                    ConfigFieldCard(
                        field = field,
                        value = fieldsState[field.key].orEmpty(),
                        onChange = { fieldsState[field.key] = it }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.saveSettings(selected, fieldsState.toMap())
                        scope.launch { snackbarHostState.showSnackbar("Profile settings saved and applied") }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Filled.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Settings")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigFieldCard(
    field: SettingField,
    value: String,
    onChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when (field.type) {
                FieldType.BOOL -> {
                    val checked = value.equals("true", ignoreCase = true)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(field.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                field.helper,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = checked,
                            onCheckedChange = { onChange(if (it) "true" else "false") }
                        )
                    }
                }

                FieldType.OPTION -> {
                    var expanded by remember(field.key) { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(field.label) },
                            supportingText = { Text(field.helper) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            field.options.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        onChange(option)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                FieldType.TEXT -> {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onChange,
                        label = { Text(field.label) },
                        supportingText = { Text(field.helper) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = field.keyboardType)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider()
        }
    }
}

private fun readTextFromUri(context: Context, uri: Uri): String {
    return runCatching {
        val stream = context.contentResolver.openInputStream(uri)
        stream?.bufferedReader()?.use { it.readText() } ?: ""
    }.getOrDefault("")
}

private fun writeTextToUri(context: Context, uri: Uri, content: String) {
    runCatching {
        context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
            it.write(content)
        }
    }
}

private fun defaultValuesFor(profile: ProfileEntity): Map<String, String> {
    val advanced = parseAdvanced(profile.advancedJson)
    fun adv(key: String, fallback: String): String {
        return advanced[key]?.trim().takeUnless { it.isNullOrEmpty() } ?: fallback
    }

    return buildMap {
        put("DOMAINS", parseDomains(profile.domains).joinToString(", "))
        put("DATA_ENCRYPTION_METHOD", profile.encryptionMethod.toString())
        put("ENCRYPTION_KEY", profile.encryptionKey)
        put("PROTOCOL_TYPE", profile.protocolType)
        put("LISTEN_IP", adv("LISTEN_IP", "127.0.0.1"))
        put("LISTEN_PORT", profile.listenPort.toString())
        put("SOCKS5_AUTH", adv("SOCKS5_AUTH", "false"))
        put("SOCKS5_USER", adv("SOCKS5_USER", "master_dns_vpn"))
        put("SOCKS5_PASS", adv("SOCKS5_PASS", "master_dns_vpn"))
        put("LOCAL_DNS_ENABLED", adv("LOCAL_DNS_ENABLED", "false"))
        put("LOCAL_DNS_IP", adv("LOCAL_DNS_IP", "127.0.0.1"))
        put("LOCAL_DNS_PORT", adv("LOCAL_DNS_PORT", "5353"))
        put("LOCAL_DNS_CACHE_MAX_RECORDS", adv("LOCAL_DNS_CACHE_MAX_RECORDS", "10000"))
        put("LOCAL_DNS_CACHE_TTL_SECONDS", adv("LOCAL_DNS_CACHE_TTL_SECONDS", "14400.0"))
        put("LOCAL_DNS_PENDING_TIMEOUT_SECONDS", adv("LOCAL_DNS_PENDING_TIMEOUT_SECONDS", "300.0"))
        put("DNS_RESPONSE_FRAGMENT_TIMEOUT_SECONDS", adv("DNS_RESPONSE_FRAGMENT_TIMEOUT_SECONDS", "60.0"))
        put("LOCAL_DNS_CACHE_PERSIST_TO_FILE", adv("LOCAL_DNS_CACHE_PERSIST_TO_FILE", "true"))
        put("LOCAL_DNS_CACHE_FLUSH_INTERVAL_SECONDS", adv("LOCAL_DNS_CACHE_FLUSH_INTERVAL_SECONDS", "60.0"))
        put("RESOLVER_BALANCING_STRATEGY", profile.resolverBalancingStrategy.takeIf { it != 0 }?.toString() ?: "2")
        put("PACKET_DUPLICATION_COUNT", profile.packetDuplicationCount.toString())
        put("SETUP_PACKET_DUPLICATION_COUNT", profile.setupPacketDuplicationCount.toString())
        put("STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD", adv("STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD", "2"))
        put("STREAM_RESOLVER_FAILOVER_COOLDOWN", adv("STREAM_RESOLVER_FAILOVER_COOLDOWN", "2.5"))
        put("RECHECK_INACTIVE_SERVERS_ENABLED", adv("RECHECK_INACTIVE_SERVERS_ENABLED", "true"))
        put("AUTO_DISABLE_TIMEOUT_SERVERS", adv("AUTO_DISABLE_TIMEOUT_SERVERS", "true"))
        put("AUTO_DISABLE_TIMEOUT_WINDOW_SECONDS", adv("AUTO_DISABLE_TIMEOUT_WINDOW_SECONDS", "30.0"))
        put("BASE_ENCODE_DATA", adv("BASE_ENCODE_DATA", "false"))
        put("UPLOAD_COMPRESSION_TYPE", profile.uploadCompression.toString())
        put("DOWNLOAD_COMPRESSION_TYPE", profile.downloadCompression.toString())
        put("COMPRESSION_MIN_SIZE", adv("COMPRESSION_MIN_SIZE", "120"))
        put("MIN_UPLOAD_MTU", adv("MIN_UPLOAD_MTU", "38"))
        put("MIN_DOWNLOAD_MTU", adv("MIN_DOWNLOAD_MTU", "100"))
        put("MAX_UPLOAD_MTU", adv("MAX_UPLOAD_MTU", "150"))
        put("MAX_DOWNLOAD_MTU", adv("MAX_DOWNLOAD_MTU", "500"))
        put("MTU_TEST_RETRIES", adv("MTU_TEST_RETRIES", "2"))
        put("MTU_TEST_TIMEOUT", adv("MTU_TEST_TIMEOUT", "2.0"))
        put("MTU_TEST_PARALLELISM", adv("MTU_TEST_PARALLELISM", "16"))
        put("SAVE_MTU_SERVERS_TO_FILE", adv("SAVE_MTU_SERVERS_TO_FILE", "false"))
        put("MTU_SERVERS_FILE_NAME", adv("MTU_SERVERS_FILE_NAME", "masterdnsvpn_success_test_{time}.log"))
        put("MTU_SERVERS_FILE_FORMAT", adv("MTU_SERVERS_FILE_FORMAT", "{IP} ({DOMAIN}) - UP: {UP_MTU} DOWN: {DOWN-MTU}"))
        put("MTU_USING_SECTION_SEPARATOR_TEXT", adv("MTU_USING_SECTION_SEPARATOR_TEXT", ""))
        put("MTU_REMOVED_SERVER_LOG_FORMAT", adv("MTU_REMOVED_SERVER_LOG_FORMAT", "Resolver {IP} ({DOMAIN}) removed at {TIME} due to {CAUSE}"))
        put("MTU_ADDED_SERVER_LOG_FORMAT", adv("MTU_ADDED_SERVER_LOG_FORMAT", "Resolver {IP} ({DOMAIN}) added back at {TIME} (UP {UP_MTU}, DOWN {DOWN_MTU})"))
        put("MTU_REACTIVE_ADDED_SERVER_LOG_FORMAT", adv("MTU_REACTIVE_ADDED_SERVER_LOG_FORMAT", "Resolver {IP} ({DOMAIN}) added back at {TIME} after reactive recheck (UP {UP_MTU}, DOWN {DOWN_MTU})"))
        put("MTU_EXPORT_URI", adv("MTU_EXPORT_URI", ""))
        put("RX_TX_WORKERS", adv("RX_TX_WORKERS", "4"))
        put("TUNNEL_PROCESS_WORKERS", adv("TUNNEL_PROCESS_WORKERS", "6"))
        put("TUNNEL_PACKET_TIMEOUT_SECONDS", adv("TUNNEL_PACKET_TIMEOUT_SECONDS", "10.0"))
        put("DISPATCHER_IDLE_POLL_INTERVAL_SECONDS", adv("DISPATCHER_IDLE_POLL_INTERVAL_SECONDS", "0.020"))
        put("RX_CHANNEL_SIZE", adv("RX_CHANNEL_SIZE", "4096"))
        put("SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS", adv("SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS", "30.0"))
        put("CLIENT_TERMINAL_STREAM_RETENTION_SECONDS", adv("CLIENT_TERMINAL_STREAM_RETENTION_SECONDS", "45.0"))
        put("CLIENT_CANCELLED_SETUP_RETENTION_SECONDS", adv("CLIENT_CANCELLED_SETUP_RETENTION_SECONDS", "120.0"))
        put("SESSION_INIT_RETRY_BASE_SECONDS", adv("SESSION_INIT_RETRY_BASE_SECONDS", "1.0"))
        put("SESSION_INIT_RETRY_STEP_SECONDS", adv("SESSION_INIT_RETRY_STEP_SECONDS", "1.0"))
        put("SESSION_INIT_RETRY_LINEAR_AFTER", adv("SESSION_INIT_RETRY_LINEAR_AFTER", "5"))
        put("SESSION_INIT_RETRY_MAX_SECONDS", adv("SESSION_INIT_RETRY_MAX_SECONDS", "60.0"))
        put("SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS", adv("SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS", "60.0"))
        put("SESSION_INIT_RACING_COUNT", adv("SESSION_INIT_RACING_COUNT", "3"))
        put("PING_AGGRESSIVE_INTERVAL_SECONDS", adv("PING_AGGRESSIVE_INTERVAL_SECONDS", "0.100"))
        put("PING_LAZY_INTERVAL_SECONDS", adv("PING_LAZY_INTERVAL_SECONDS", "0.750"))
        put("PING_COOLDOWN_INTERVAL_SECONDS", adv("PING_COOLDOWN_INTERVAL_SECONDS", "2.0"))
        put("PING_COLD_INTERVAL_SECONDS", adv("PING_COLD_INTERVAL_SECONDS", "15.0"))
        put("PING_WARM_THRESHOLD_SECONDS", adv("PING_WARM_THRESHOLD_SECONDS", "8.0"))
        put("PING_COOL_THRESHOLD_SECONDS", adv("PING_COOL_THRESHOLD_SECONDS", "20.0"))
        put("PING_COLD_THRESHOLD_SECONDS", adv("PING_COLD_THRESHOLD_SECONDS", "30.0"))
        put("ARQ_WINDOW_SIZE", adv("ARQ_WINDOW_SIZE", "600"))
        put("MAX_PACKETS_PER_BATCH", adv("MAX_PACKETS_PER_BATCH", "8"))
        put("ARQ_INITIAL_RTO_SECONDS", adv("ARQ_INITIAL_RTO_SECONDS", "1.0"))
        put("ARQ_MAX_RTO_SECONDS", adv("ARQ_MAX_RTO_SECONDS", "5.0"))
        put("ARQ_CONTROL_INITIAL_RTO_SECONDS", adv("ARQ_CONTROL_INITIAL_RTO_SECONDS", "0.5"))
        put("ARQ_CONTROL_MAX_RTO_SECONDS", adv("ARQ_CONTROL_MAX_RTO_SECONDS", "3.0"))
        put("ARQ_MAX_CONTROL_RETRIES", adv("ARQ_MAX_CONTROL_RETRIES", "400"))
        put("ARQ_MAX_DATA_RETRIES", adv("ARQ_MAX_DATA_RETRIES", "1200"))
        put("ARQ_DATA_PACKET_TTL_SECONDS", adv("ARQ_DATA_PACKET_TTL_SECONDS", "2400.0"))
        put("ARQ_CONTROL_PACKET_TTL_SECONDS", adv("ARQ_CONTROL_PACKET_TTL_SECONDS", "1200.0"))
        put("ARQ_DATA_NACK_MAX_GAP", adv("ARQ_DATA_NACK_MAX_GAP", "16"))
        put("ARQ_DATA_NACK_INITIAL_DELAY_SECONDS", adv("ARQ_DATA_NACK_INITIAL_DELAY_SECONDS", "0.1"))
        put("ARQ_DATA_NACK_REPEAT_SECONDS", adv("ARQ_DATA_NACK_REPEAT_SECONDS", "1.0"))
        put("ARQ_INACTIVITY_TIMEOUT_SECONDS", adv("ARQ_INACTIVITY_TIMEOUT_SECONDS", "1800.0"))
        put("ARQ_TERMINAL_DRAIN_TIMEOUT_SECONDS", adv("ARQ_TERMINAL_DRAIN_TIMEOUT_SECONDS", "120.0"))
        put("ARQ_TERMINAL_ACK_WAIT_TIMEOUT_SECONDS", adv("ARQ_TERMINAL_ACK_WAIT_TIMEOUT_SECONDS", "90.0"))
        put("LOG_LEVEL", profile.logLevel)
    }
}

private fun parseAdvanced(json: String): Map<String, String> {
    return try {
        val type = object : TypeToken<Map<String, String>>() {}.type
        Gson().fromJson<Map<String, String>>(json, type) ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun parseDomains(json: String): List<String> {
    return try {
        val type = object : TypeToken<List<String>>() {}.type
        Gson().fromJson<List<String>>(json, type) ?: emptyList()
    } catch (_: Exception) {
        listOf(json.trim().removeSurrounding("\"")).filter { it.isNotBlank() }
    }
}
