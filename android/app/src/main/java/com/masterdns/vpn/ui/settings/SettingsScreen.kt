package com.masterdns.vpn.ui.settings

import android.content.Context
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
    SettingField(
        "Resolver",
        "RESOLVER_BALANCING_STRATEGY",
        "RESOLVER_BALANCING_STRATEGY",
        "1=RoundRobin, 2=LowestLatency, 3=LeastLoss, 4=Sticky",
        keyboardType = KeyboardType.Number
    ),
    SettingField("Resolver", "PACKET_DUPLICATION_COUNT", "PACKET_DUPLICATION_COUNT", "Runtime packet duplication count", keyboardType = KeyboardType.Number),
    SettingField("Resolver", "SETUP_PACKET_DUPLICATION_COUNT", "SETUP_PACKET_DUPLICATION_COUNT", "Setup packet duplication count", keyboardType = KeyboardType.Number),
    SettingField("Resolver", "STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD", "STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD", "Resends before stream failover", keyboardType = KeyboardType.Number),
    SettingField("Resolver", "STREAM_RESOLVER_FAILOVER_COOLDOWN", "STREAM_RESOLVER_FAILOVER_COOLDOWN", "Failover cooldown seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Resolver", "RECHECK_SERVER_INTERVAL_SECONDS", "RECHECK_SERVER_INTERVAL_SECONDS", "Resolver health recheck interval seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Resolver", "RECHECK_INACTIVE_SERVERS_ENABLED", "RECHECK_INACTIVE_SERVERS_ENABLED", "Re-check inactive resolvers", type = FieldType.BOOL),
    SettingField("Resolver", "AUTO_DISABLE_TIMEOUT_SERVERS", "AUTO_DISABLE_TIMEOUT_SERVERS", "Auto disable timeout resolvers", type = FieldType.BOOL),
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
    SettingField("Runtime", "TUNNEL_READER_WORKERS", "TUNNEL_READER_WORKERS", "Reader worker count", keyboardType = KeyboardType.Number),
    SettingField("Runtime", "TUNNEL_WRITER_WORKERS", "TUNNEL_WRITER_WORKERS", "Writer worker count", keyboardType = KeyboardType.Number),
    SettingField("Runtime", "TUNNEL_PROCESS_WORKERS", "TUNNEL_PROCESS_WORKERS", "Processor worker count", keyboardType = KeyboardType.Number),
    SettingField("Runtime", "TUNNEL_PACKET_TIMEOUT_SECONDS", "TUNNEL_PACKET_TIMEOUT_SECONDS", "Packet timeout seconds", keyboardType = KeyboardType.Decimal),
    SettingField("Runtime", "TX_CHANNEL_SIZE", "TX_CHANNEL_SIZE", "TX channel size", keyboardType = KeyboardType.Number),
    SettingField("Runtime", "RX_CHANNEL_SIZE", "RX_CHANNEL_SIZE", "RX channel size", keyboardType = KeyboardType.Number),
    SettingField("Runtime", "RESOLVER_UDP_CONNECTION_POOL_SIZE", "RESOLVER_UDP_CONNECTION_POOL_SIZE", "UDP connection pool size", keyboardType = KeyboardType.Number),
    SettingField("ARQ", "ARQ_WINDOW_SIZE", "ARQ_WINDOW_SIZE", "ARQ receive window size", keyboardType = KeyboardType.Number),
    SettingField("ARQ", "ARQ_INITIAL_RTO_SECONDS", "ARQ_INITIAL_RTO_SECONDS", "Initial RTO seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_MAX_RTO_SECONDS", "ARQ_MAX_RTO_SECONDS", "Maximum RTO seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_CONTROL_MAX_RTO_SECONDS", "ARQ_CONTROL_MAX_RTO_SECONDS", "Control packet max RTO seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_MAX_CONTROL_RETRIES", "ARQ_MAX_CONTROL_RETRIES", "Maximum retries per control packet", keyboardType = KeyboardType.Number),
    SettingField("ARQ", "ARQ_MAX_DATA_RETRIES", "ARQ_MAX_DATA_RETRIES", "Maximum retries per data packet", keyboardType = KeyboardType.Number),
    SettingField("ARQ", "ARQ_DATA_NACK_INITIAL_DELAY_SECONDS", "ARQ_DATA_NACK_INITIAL_DELAY_SECONDS", "Initial delay before first NACK seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_DATA_NACK_REPEAT_SECONDS", "ARQ_DATA_NACK_REPEAT_SECONDS", "NACK repeat interval seconds", keyboardType = KeyboardType.Decimal),
    SettingField("ARQ", "ARQ_INACTIVITY_TIMEOUT_SECONDS", "ARQ_INACTIVITY_TIMEOUT_SECONDS", "Inactivity timeout seconds", keyboardType = KeyboardType.Decimal),
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

    val listItems = remember {
        buildList<Any> {
            var section = ""
            configFields.forEach { field ->
                if (field.section != section) {
                    section = field.section
                    add(section)
                }
                add(field)
            }
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
            }

            val socksAuthEnabled = fieldsState["SOCKS5_AUTH"].equals("true", ignoreCase = true)
            items(listItems, key = { item ->
                when (item) {
                    is String -> "header_$item"
                    is SettingField -> item.key
                    else -> item.hashCode().toString()
                }
            }) { item ->
                when (item) {
                    is String -> {
                        Text(
                            text = item,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    is SettingField -> {
                        if ((item.key == "SOCKS5_USER" || item.key == "SOCKS5_PASS") && !socksAuthEnabled) {
                            return@items
                        }
                        ConfigFieldCard(
                            field = item,
                            value = fieldsState[item.key].orEmpty(),
                            onChange = { fieldsState[item.key] = it }
                        )
                    }
                    else -> Unit
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
        put("RESOLVER_BALANCING_STRATEGY", profile.resolverBalancingStrategy.takeIf { it != 0 }?.toString() ?: "2")
        put("PACKET_DUPLICATION_COUNT", profile.packetDuplicationCount.toString())
        put("SETUP_PACKET_DUPLICATION_COUNT", profile.setupPacketDuplicationCount.toString())
        put("STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD", adv("STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD", "3"))
        put("STREAM_RESOLVER_FAILOVER_COOLDOWN", adv("STREAM_RESOLVER_FAILOVER_COOLDOWN", "8.0"))
        put("RECHECK_SERVER_INTERVAL_SECONDS", adv("RECHECK_SERVER_INTERVAL_SECONDS", "5.0"))
        put("RECHECK_INACTIVE_SERVERS_ENABLED", adv("RECHECK_INACTIVE_SERVERS_ENABLED", "true"))
        put("AUTO_DISABLE_TIMEOUT_SERVERS", adv("AUTO_DISABLE_TIMEOUT_SERVERS", "true"))
        put("BASE_ENCODE_DATA", adv("BASE_ENCODE_DATA", "false"))
        put("UPLOAD_COMPRESSION_TYPE", profile.uploadCompression.toString())
        put("DOWNLOAD_COMPRESSION_TYPE", profile.downloadCompression.toString())
        put("COMPRESSION_MIN_SIZE", adv("COMPRESSION_MIN_SIZE", "120"))
        put("MIN_UPLOAD_MTU", adv("MIN_UPLOAD_MTU", "38"))
        put("MIN_DOWNLOAD_MTU", adv("MIN_DOWNLOAD_MTU", "500"))
        put("MAX_UPLOAD_MTU", adv("MAX_UPLOAD_MTU", "150"))
        put("MAX_DOWNLOAD_MTU", adv("MAX_DOWNLOAD_MTU", "900"))
        put("MTU_TEST_RETRIES", adv("MTU_TEST_RETRIES", "2"))
        put("MTU_TEST_TIMEOUT", adv("MTU_TEST_TIMEOUT", "2.0"))
        put("MTU_TEST_PARALLELISM", adv("MTU_TEST_PARALLELISM", "32"))
        put("SAVE_MTU_SERVERS_TO_FILE", adv("SAVE_MTU_SERVERS_TO_FILE", "false"))
        put("TUNNEL_READER_WORKERS", adv("TUNNEL_READER_WORKERS", "5"))
        put("TUNNEL_WRITER_WORKERS", adv("TUNNEL_WRITER_WORKERS", "5"))
        put("TUNNEL_PROCESS_WORKERS", adv("TUNNEL_PROCESS_WORKERS", "5"))
        put("TUNNEL_PACKET_TIMEOUT_SECONDS", adv("TUNNEL_PACKET_TIMEOUT_SECONDS", "10.0"))
        put("TX_CHANNEL_SIZE", adv("TX_CHANNEL_SIZE", "12288"))
        put("RX_CHANNEL_SIZE", adv("RX_CHANNEL_SIZE", "16384"))
        put("RESOLVER_UDP_CONNECTION_POOL_SIZE", adv("RESOLVER_UDP_CONNECTION_POOL_SIZE", "128"))
        put("ARQ_WINDOW_SIZE", adv("ARQ_WINDOW_SIZE", "1000"))
        put("ARQ_INITIAL_RTO_SECONDS", adv("ARQ_INITIAL_RTO_SECONDS", "0.6"))
        put("ARQ_MAX_RTO_SECONDS", adv("ARQ_MAX_RTO_SECONDS", "3.0"))
        put("ARQ_CONTROL_MAX_RTO_SECONDS", adv("ARQ_CONTROL_MAX_RTO_SECONDS", "2.0"))
        put("ARQ_MAX_CONTROL_RETRIES", adv("ARQ_MAX_CONTROL_RETRIES", "120"))
        put("ARQ_MAX_DATA_RETRIES", adv("ARQ_MAX_DATA_RETRIES", "120"))
        put("ARQ_DATA_NACK_INITIAL_DELAY_SECONDS", adv("ARQ_DATA_NACK_INITIAL_DELAY_SECONDS", "0.4"))
        put("ARQ_DATA_NACK_REPEAT_SECONDS", adv("ARQ_DATA_NACK_REPEAT_SECONDS", "0.8"))
        put("ARQ_INACTIVITY_TIMEOUT_SECONDS", adv("ARQ_INACTIVITY_TIMEOUT_SECONDS", "1800.0"))
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
