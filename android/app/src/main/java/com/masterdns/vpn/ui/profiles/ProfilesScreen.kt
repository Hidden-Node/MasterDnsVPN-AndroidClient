package com.masterdns.vpn.ui.profiles

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.gson.Gson
import com.masterdns.vpn.R
import com.masterdns.vpn.data.local.ProfileEntity
import com.masterdns.vpn.ui.components.mdv.controls.MdvBackTopAppBar
import com.masterdns.vpn.ui.theme.ConnectedGreen
import com.masterdns.vpn.ui.theme.MdvColor
import com.masterdns.vpn.ui.theme.MdvSpace
import kotlinx.coroutines.launch

private data class ImportedProfileDraft(
    val profile: ProfileEntity,
    val domainInput: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onOpenSettings: (Long) -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ProfileEntity?>(null) }
    var importedDraft by remember { mutableStateOf<ImportedProfileDraft?>(null) }
    var importedResolvers by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readTextFromUri(context, uri)
        val draft = parseProfileTomlForImport(
            fileName = readDisplayName(context, uri) ?: context.getString(R.string.profiles_imported_profile_default),
            tomlContent = text
        )
        if (draft == null) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.profiles_invalid_toml_msg)) }
            return@rememberLauncherForActivityResult
        }
        importedDraft = draft
        editingProfile = null
        showEditor = true
        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.profiles_toml_imported_msg)) }
    }
    val importResolversLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readTextFromUri(context, uri).trim()
        if (text.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.profiles_resolvers_empty_msg)) }
            return@rememberLauncherForActivityResult
        }
        importedResolvers = text
        editingProfile = null
        showEditor = true
        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.profiles_resolvers_imported_msg)) }
    }

    Scaffold(
        containerColor = MdvColor.Background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            MdvBackTopAppBar(
                title = stringResource(R.string.title_profiles),
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        editingProfile = null
                        importedDraft = null
                        importedResolvers = null
                        showEditor = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.profiles_add))
                    }
                }
            )
        }
    ) { padding ->
        if (showEditor) {
            ProfileEditorDialog(
                profile = editingProfile,
                importedDraft = importedDraft,
                importedResolvers = importedResolvers,
                onImportToml = {
                    importLauncher.launch(
                        arrayOf(
                            "application/toml",
                            "text/x-toml",
                            "text/plain",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                },
                onImportResolvers = {
                    importResolversLauncher.launch(
                        arrayOf(
                            "text/plain",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                },
                onSave = { profile ->
                    if (editingProfile != null) {
                        viewModel.updateProfile(profile)
                    } else {
                        viewModel.addProfile(profile)
                    }
                    showEditor = false
                    editingProfile = null
                    importedDraft = null
                    importedResolvers = null
                },
                onDismiss = {
                    showEditor = false
                    editingProfile = null
                    importedDraft = null
                    importedResolvers = null
                }
            )
        }

        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.PersonAdd,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MdvColor.OnSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.profiles_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MdvColor.OnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledTonalButton(onClick = {
                        editingProfile = null
                        importedDraft = null
                        importedResolvers = null
                        showEditor = true
                    }) {
                        Text(stringResource(R.string.profiles_create))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(MdvSpace.S4),
                verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)
            ) {
                items(profiles) { profile ->
                    ProfileCard(
                        profile = profile,
                        onSelect = { viewModel.selectProfile(profile.id) },
                        onSettings = { onOpenSettings(profile.id) },
                        onEdit = {
                            editingProfile = profile
                            showEditor = true
                        },
                        onDelete = { viewModel.deleteProfile(profile) }
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileCard(
    profile: ProfileEntity,
    onSelect: () -> Unit,
    onSettings: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isSelected)
                MdvColor.PrimaryContainer.copy(alpha = 0.16f)
            else
                MdvColor.SurfaceHigh
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selected indicator
            if (profile.isSelected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.profiles_selected),
                    tint = ConnectedGreen,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = profile.domains.replace("[\"", "").replace("\"]", ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MdvColor.OnSurfaceVariant
                )
            }

            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.profiles_edit), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.profiles_settings), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.profiles_delete), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorDialog(
    profile: ProfileEntity?,
    importedDraft: ImportedProfileDraft?,
    importedResolvers: String?,
    onImportToml: () -> Unit,
    onImportResolvers: () -> Unit,
    onSave: (ProfileEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var domains by remember { mutableStateOf(profile?.domains?.removeSurrounding("[\"", "\"]").orEmpty()) }
    var encryptionKey by remember { mutableStateOf(profile?.encryptionKey.orEmpty()) }
    var resolvers by remember { mutableStateOf(profile?.resolvers ?: "8.8.8.8") }
    var showKey by remember { mutableStateOf(false) }
    var showResolversEditor by remember { mutableStateOf(false) }
    val largeResolversText = resolvers.length > 6000

    LaunchedEffect(profile?.id) {
        if (profile != null) {
            name = profile.name
            domains = profile.domains.removeSurrounding("[\"", "\"]")
            encryptionKey = profile.encryptionKey
            resolvers = profile.resolvers
            showResolversEditor = false
        }
    }

    LaunchedEffect(importedDraft, importedResolvers) {
        val importedProfile = importedDraft?.profile
        if (importedProfile != null) {
            if (name.isBlank()) {
                // Keep user-entered profile name if they typed it before import.
                name = importedProfile.name
            }
            domains = importedDraft.domainInput
            encryptionKey = importedProfile.encryptionKey
            resolvers = importedProfile.resolvers
        }
        if (!importedResolvers.isNullOrBlank()) {
            resolvers = importedResolvers
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (profile != null) {
                    stringResource(R.string.profiles_dialog_edit_title)
                } else {
                    stringResource(R.string.profiles_dialog_new_title)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profiles_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (profile == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onImportToml,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.UploadFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_import_toml))
                        }
                        OutlinedButton(
                            onClick = onImportResolvers,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Description, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.profiles_import_resolvers_short))
                        }
                    }
                }

                OutlinedTextField(
                    value = domains,
                    onValueChange = { domains = it },
                    label = { Text(stringResource(R.string.profiles_domain_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = encryptionKey,
                    onValueChange = { encryptionKey = it },
                    label = { Text(stringResource(R.string.profiles_encryption_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) {
                                    stringResource(R.string.profiles_hide_sensitive)
                                } else {
                                    stringResource(R.string.profiles_show_sensitive)
                                }
                            )
                        }
                    }
                )

                if (!showResolversEditor && largeResolversText) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceHigh)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.profiles_large_resolvers_title, resolvers.lines().size))
                            Text(
                                stringResource(R.string.profiles_large_resolvers_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurfaceVariant
                            )
                            OutlinedButton(onClick = { showResolversEditor = true }) {
                                Text(stringResource(R.string.profiles_edit_resolvers))
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = resolvers,
                        onValueChange = { resolvers = it },
                        label = { Text(stringResource(R.string.profiles_resolvers_label)) },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    val baseProfile = profile ?: importedDraft?.profile ?: ProfileEntity(name = "", domains = "")
                    val domainJson = gson.toJson(
                        domains.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    )
                    onSave(
                        baseProfile.copy(
                            name = name.trim().ifEmpty { "Profile" },
                            domains = if (domainJson == "[]") gson.toJson(listOf(domains.trim())) else domainJson,
                            encryptionKey = encryptionKey,
                            resolvers = resolvers.trim()
                        )
                    )
                },
                enabled = name.isNotBlank() && domains.isNotBlank()
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

private val gson = Gson()

private fun readTextFromUri(context: Context, uri: Uri): String {
    return context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
}

private fun readDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex < 0 || !cursor.moveToFirst()) return@use null
        cursor.getString(nameIndex)
    }?.substringBeforeLast(".")?.trim()?.takeIf { it.isNotEmpty() }
}

private fun parseProfileTomlForImport(fileName: String, tomlContent: String): ImportedProfileDraft? {
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

    val parsedDomain = values["DOMAINS"]?.takeIf { it.isNotBlank() } ?: return null
    val parsedKey = values["ENCRYPTION_KEY"]?.takeIf { it.isNotBlank() } ?: return null

    val advanced = mutableMapOf<String, String>()
    IMPORT_ADVANCED_KEYS.forEach { key ->
        values[key]?.let { advanced[key] = it.trim() }
    }

    val importedProfile = ProfileEntity(
        name = fileName,
        domains = gson.toJson(parsedDomain.split(",").map { it.trim() }.filter { it.isNotEmpty() }),
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

    return ImportedProfileDraft(
        profile = importedProfile,
        domainInput = parsedDomain
    )
}

private fun normalizeProtocol(value: String?): String {
    return when (value?.trim()?.uppercase()) {
        "TCP" -> "TCP"
        else -> "SOCKS5"
    }
}

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
