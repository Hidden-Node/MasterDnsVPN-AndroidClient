package com.masterdns.vpn.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import com.masterdns.vpn.R
import com.masterdns.vpn.data.local.ProfileEntity
import com.masterdns.vpn.ui.components.mdv.cards.MdvSectionCard
import com.masterdns.vpn.ui.components.mdv.cards.MdvSettingFieldCard
import com.masterdns.vpn.ui.components.mdv.controls.MdvPrimaryActionButton
import androidx.annotation.StringRes
import com.masterdns.vpn.ui.components.mdv.controls.MdvBackTopAppBar
import com.masterdns.vpn.ui.components.mdv.controls.MdvTopAppBar
import com.masterdns.vpn.ui.theme.MdvColor
import com.masterdns.vpn.ui.theme.MdvSpace
import com.masterdns.vpn.util.ResolverAnalyzer
import com.masterdns.vpn.util.parseAdvancedJson
import com.masterdns.vpn.util.parseDomainsJson
import com.masterdns.vpn.util.readDisplayName
import com.masterdns.vpn.util.readTextFromUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class FieldType { TEXT, BOOL, OPTION }

private enum class Section(@StringRes val titleRes: Int) {
    IDENTITY(R.string.settings_section_identity),
    SECURITY(R.string.settings_section_security),
    PROXY(R.string.settings_section_proxy),
    DNS(R.string.settings_section_dns),
    RESOLVER(R.string.settings_section_resolver),
    COMPRESSION(R.string.settings_section_compression),
    MTU(R.string.settings_section_mtu),
    RUNTIME(R.string.settings_section_runtime),
    ARQ(R.string.settings_section_arq),
    LOGGING(R.string.settings_section_logging)
}

private data class SettingOption(val value: String, @StringRes val labelRes: Int)

private data class SettingField(
    val section: Section,
    val key: String,
    @StringRes val helperRes: Int,
    val type: FieldType = FieldType.TEXT,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val options: List<SettingOption> = emptyList()
)

private val configFields = listOf(
    SettingField(Section.IDENTITY, "DOMAINS", R.string.settings_helper_domains),
    SettingField(Section.IDENTITY, "ENCRYPTION_KEY", R.string.settings_helper_encryption_key),
    SettingField(
        Section.SECURITY,
        "DATA_ENCRYPTION_METHOD",
        R.string.settings_helper_data_encryption_method,
        type = FieldType.OPTION,
        options = listOf(
            SettingOption("0", R.string.settings_option_data_encryption_method_0),
            SettingOption("1", R.string.settings_option_data_encryption_method_1),
            SettingOption("2", R.string.settings_option_data_encryption_method_2),
            SettingOption("3", R.string.settings_option_data_encryption_method_3),
            SettingOption("4", R.string.settings_option_data_encryption_method_4),
            SettingOption("5", R.string.settings_option_data_encryption_method_5)
        )
    ),
    SettingField(
        Section.PROXY,
        "PROTOCOL_TYPE",
        R.string.settings_helper_protocol_type,
        type = FieldType.OPTION,
        options = listOf(
            SettingOption("SOCKS5", R.string.settings_option_protocol_type_socks5),
            SettingOption("TCP", R.string.settings_option_protocol_type_tcp)
        )
    ),
    SettingField(Section.PROXY, "LISTEN_IP", R.string.settings_helper_listen_ip),
    SettingField(
        Section.PROXY,
        "LISTEN_PORT",
        R.string.settings_helper_listen_port,
        keyboardType = KeyboardType.Number
    ),
    SettingField(Section.PROXY, "SOCKS5_AUTH", R.string.settings_helper_socks5_auth, type = FieldType.BOOL),
    SettingField(Section.PROXY, "SOCKS5_USER", R.string.settings_helper_socks5_user),
    SettingField(Section.PROXY, "SOCKS5_PASS", R.string.settings_helper_socks5_pass),
    SettingField(Section.DNS, "LOCAL_DNS_ENABLED", R.string.settings_helper_local_dns_enabled, type = FieldType.BOOL),
    SettingField(Section.DNS, "LOCAL_DNS_IP", R.string.settings_helper_local_dns_ip),
    SettingField(Section.DNS, "LOCAL_DNS_PORT", R.string.settings_helper_local_dns_port, keyboardType = KeyboardType.Number),
    SettingField(Section.DNS, "LOCAL_DNS_CACHE_MAX_RECORDS", R.string.settings_helper_local_dns_cache_max_records, keyboardType = KeyboardType.Number),
    SettingField(Section.DNS, "LOCAL_DNS_CACHE_TTL_SECONDS", R.string.settings_helper_local_dns_cache_ttl_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.DNS, "LOCAL_DNS_PENDING_TIMEOUT_SECONDS", R.string.settings_helper_local_dns_pending_timeout_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.DNS, "DNS_RESPONSE_FRAGMENT_TIMEOUT_SECONDS", R.string.settings_helper_dns_response_fragment_timeout_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.DNS, "LOCAL_DNS_CACHE_PERSIST_TO_FILE", R.string.settings_helper_local_dns_cache_persist_to_file, type = FieldType.BOOL),
    SettingField(Section.DNS, "LOCAL_DNS_CACHE_FLUSH_INTERVAL_SECONDS", R.string.settings_helper_local_dns_cache_flush_interval_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(
        Section.RESOLVER,
        "RESOLVER_BALANCING_STRATEGY",
        R.string.settings_helper_resolver_balancing_strategy,
        type = FieldType.OPTION,
        options = listOf(
            SettingOption("1", R.string.settings_option_resolver_balancing_strategy_1),
            SettingOption("2", R.string.settings_option_resolver_balancing_strategy_2),
            SettingOption("3", R.string.settings_option_resolver_balancing_strategy_3),
            SettingOption("4", R.string.settings_option_resolver_balancing_strategy_4),
            SettingOption("5", R.string.settings_option_resolver_balancing_strategy_5),
            SettingOption("6", R.string.settings_option_resolver_balancing_strategy_6),
            SettingOption("7", R.string.settings_option_resolver_balancing_strategy_7),
            SettingOption("8", R.string.settings_option_resolver_balancing_strategy_8)
        )
    ),
    SettingField(Section.RESOLVER, "PACKET_DUPLICATION_COUNT", R.string.settings_helper_packet_duplication_count, keyboardType = KeyboardType.Number),
    SettingField(Section.RESOLVER, "SETUP_PACKET_DUPLICATION_COUNT", R.string.settings_helper_setup_packet_duplication_count, keyboardType = KeyboardType.Number),
    SettingField(Section.RESOLVER, "STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD", R.string.settings_helper_stream_resolver_failover_resend_threshold, keyboardType = KeyboardType.Number),
    SettingField(Section.RESOLVER, "STREAM_RESOLVER_FAILOVER_COOLDOWN", R.string.settings_helper_stream_resolver_failover_cooldown, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RESOLVER, "RECHECK_INACTIVE_SERVERS_ENABLED", R.string.settings_helper_recheck_inactive_servers_enabled, type = FieldType.BOOL),
    SettingField(Section.RESOLVER, "AUTO_DISABLE_TIMEOUT_SERVERS", R.string.settings_helper_auto_disable_timeout_servers, type = FieldType.BOOL),
    SettingField(Section.RESOLVER, "AUTO_DISABLE_TIMEOUT_WINDOW_SECONDS", R.string.settings_helper_auto_disable_timeout_window_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RESOLVER, "BASE_ENCODE_DATA", R.string.settings_helper_base_encode_data, type = FieldType.BOOL),
    SettingField(Section.COMPRESSION, "UPLOAD_COMPRESSION_TYPE", R.string.settings_helper_upload_compression_type, keyboardType = KeyboardType.Number),
    SettingField(Section.COMPRESSION, "DOWNLOAD_COMPRESSION_TYPE", R.string.settings_helper_download_compression_type, keyboardType = KeyboardType.Number),
    SettingField(Section.COMPRESSION, "COMPRESSION_MIN_SIZE", R.string.settings_helper_compression_min_size, keyboardType = KeyboardType.Number),
    SettingField(Section.MTU, "AUTO_REMOVE_LOW_MTU_SERVERS", R.string.settings_helper_auto_remove_low_mtu_servers, type = FieldType.BOOL),
    SettingField(Section.MTU, "MIN_UPLOAD_MTU", R.string.settings_helper_min_upload_mtu, keyboardType = KeyboardType.Number),
    SettingField(Section.MTU, "MIN_DOWNLOAD_MTU", R.string.settings_helper_min_download_mtu, keyboardType = KeyboardType.Number),
    SettingField(Section.MTU, "MAX_UPLOAD_MTU", R.string.settings_helper_max_upload_mtu, keyboardType = KeyboardType.Number),
    SettingField(Section.MTU, "MAX_DOWNLOAD_MTU", R.string.settings_helper_max_download_mtu, keyboardType = KeyboardType.Number),
    SettingField(Section.MTU, "MTU_TEST_RETRIES", R.string.settings_helper_mtu_test_retries, keyboardType = KeyboardType.Number),
    SettingField(Section.MTU, "MTU_TEST_TIMEOUT", R.string.settings_helper_mtu_test_timeout, keyboardType = KeyboardType.Decimal),
    SettingField(Section.MTU, "MTU_TEST_PARALLELISM", R.string.settings_helper_mtu_test_parallelism, keyboardType = KeyboardType.Number),
    SettingField(Section.MTU, "SAVE_MTU_SERVERS_TO_FILE", R.string.settings_helper_save_mtu_servers_to_file, type = FieldType.BOOL),
    SettingField(Section.MTU, "MTU_SERVERS_FILE_NAME", R.string.settings_helper_mtu_servers_file_name),
    SettingField(Section.MTU, "MTU_SERVERS_FILE_FORMAT", R.string.settings_helper_mtu_servers_file_format),
    SettingField(Section.MTU, "MTU_USING_SECTION_SEPARATOR_TEXT", R.string.settings_helper_mtu_using_section_separator_text),
    SettingField(Section.MTU, "MTU_REMOVED_SERVER_LOG_FORMAT", R.string.settings_helper_mtu_removed_server_log_format),
    SettingField(Section.MTU, "MTU_ADDED_SERVER_LOG_FORMAT", R.string.settings_helper_mtu_added_server_log_format),
    SettingField(Section.MTU, "MTU_REACTIVE_ADDED_SERVER_LOG_FORMAT", R.string.settings_helper_mtu_reactive_added_server_log_format),
    SettingField(Section.RUNTIME, "RX_TX_WORKERS", R.string.settings_helper_rx_tx_workers, keyboardType = KeyboardType.Number),
    SettingField(Section.RUNTIME, "TUNNEL_PROCESS_WORKERS", R.string.settings_helper_tunnel_process_workers, keyboardType = KeyboardType.Number),
    SettingField(Section.RUNTIME, "TUNNEL_PACKET_TIMEOUT_SECONDS", R.string.settings_helper_tunnel_packet_timeout_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "RX_CHANNEL_SIZE", R.string.settings_helper_rx_channel_size, keyboardType = KeyboardType.Number),
    SettingField(Section.RUNTIME, "DISPATCHER_IDLE_POLL_INTERVAL_SECONDS", R.string.settings_helper_dispatcher_idle_poll_interval_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS", R.string.settings_helper_socks_udp_associate_read_timeout_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "CLIENT_TERMINAL_STREAM_RETENTION_SECONDS", R.string.settings_helper_client_terminal_stream_retention_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "CLIENT_CANCELLED_SETUP_RETENTION_SECONDS", R.string.settings_helper_client_cancelled_setup_retention_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "SESSION_INIT_RETRY_BASE_SECONDS", R.string.settings_helper_session_init_retry_base_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "SESSION_INIT_RETRY_STEP_SECONDS", R.string.settings_helper_session_init_retry_step_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "SESSION_INIT_RETRY_LINEAR_AFTER", R.string.settings_helper_session_init_retry_linear_after, keyboardType = KeyboardType.Number),
    SettingField(Section.RUNTIME, "SESSION_INIT_RETRY_MAX_SECONDS", R.string.settings_helper_session_init_retry_max_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS", R.string.settings_helper_session_init_busy_retry_interval_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "SESSION_INIT_RACING_COUNT", R.string.settings_helper_session_init_racing_count, keyboardType = KeyboardType.Number),
    SettingField(Section.RUNTIME, "PING_AGGRESSIVE_INTERVAL_SECONDS", R.string.settings_helper_ping_aggressive_interval_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "PING_LAZY_INTERVAL_SECONDS", R.string.settings_helper_ping_lazy_interval_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "PING_COOLDOWN_INTERVAL_SECONDS", R.string.settings_helper_ping_cooldown_interval_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "PING_COLD_INTERVAL_SECONDS", R.string.settings_helper_ping_cold_interval_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "PING_WARM_THRESHOLD_SECONDS", R.string.settings_helper_ping_warm_threshold_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "PING_COOL_THRESHOLD_SECONDS", R.string.settings_helper_ping_cool_threshold_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.RUNTIME, "PING_COLD_THRESHOLD_SECONDS", R.string.settings_helper_ping_cold_threshold_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.ARQ, "ARQ_WINDOW_SIZE", R.string.settings_helper_arq_window_size, keyboardType = KeyboardType.Number),
    SettingField(Section.ARQ, "MAX_PACKETS_PER_BATCH", R.string.settings_helper_max_packets_per_batch, keyboardType = KeyboardType.Number),
    SettingField(Section.ARQ, "ARQ_INITIAL_RTO_SECONDS", R.string.settings_helper_arq_initial_rto_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.ARQ, "ARQ_MAX_RTO_SECONDS", R.string.settings_helper_arq_max_rto_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.ARQ, "ARQ_CONTROL_INITIAL_RTO_SECONDS", R.string.settings_helper_arq_control_initial_rto_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.ARQ, "ARQ_CONTROL_MAX_RTO_SECONDS", R.string.settings_helper_arq_control_max_rto_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.ARQ, "ARQ_MAX_CONTROL_RETRIES", R.string.settings_helper_arq_max_control_retries, keyboardType = KeyboardType.Number),
    SettingField(Section.ARQ, "ARQ_MAX_DATA_RETRIES", R.string.settings_helper_arq_max_data_retries, keyboardType = KeyboardType.Number),
    SettingField(Section.ARQ, "ARQ_DATA_PACKET_TTL_SECONDS", R.string.settings_helper_arq_data_packet_ttl_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.ARQ, "ARQ_CONTROL_PACKET_TTL_SECONDS", R.string.settings_helper_arq_control_packet_ttl_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.ARQ, "ARQ_DATA_NACK_MAX_GAP", R.string.settings_helper_arq_data_nack_max_gap, keyboardType = KeyboardType.Number),
    SettingField(Section.ARQ, "ARQ_DATA_NACK_INITIAL_DELAY_SECONDS", R.string.settings_helper_arq_data_nack_initial_delay_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.ARQ, "ARQ_DATA_NACK_REPEAT_SECONDS", R.string.settings_helper_arq_data_nack_repeat_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.ARQ, "ARQ_INACTIVITY_TIMEOUT_SECONDS", R.string.settings_helper_arq_inactivity_timeout_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.ARQ, "ARQ_TERMINAL_DRAIN_TIMEOUT_SECONDS", R.string.settings_helper_arq_terminal_drain_timeout_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(Section.ARQ, "ARQ_TERMINAL_ACK_WAIT_TIMEOUT_SECONDS", R.string.settings_helper_arq_terminal_ack_wait_timeout_seconds, keyboardType = KeyboardType.Decimal),
    SettingField(
        Section.LOGGING,
        "LOG_LEVEL",
        R.string.settings_helper_log_level,
        type = FieldType.OPTION,
        options = listOf(
            SettingOption("DEBUG", R.string.settings_option_log_level_debug),
            SettingOption("INFO", R.string.settings_option_log_level_info),
            SettingOption("WARN", R.string.settings_option_log_level_warn),
            SettingOption("ERROR", R.string.settings_option_log_level_error)
        )
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
    val sectionOrder = remember { configFields.map { it.section }.distinct() }
    val sectionExpanded = remember {
        mutableStateMapOf<Section, Boolean>().apply {
            sectionOrder.forEach { put(it, it == Section.IDENTITY) }
        }
    }

    var pendingExportContent by rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-toml")
    ) { uri ->
        val content = pendingExportContent
        if (uri != null && content != null) {
            runCatching {
                context.grantUriPermission(
                    context.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                it.write(content)
            }
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.settings_toml_exported_msg)) }
        }
        pendingExportContent = null
    }

    val importTomlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = context.readTextFromUri(uri)
            val updated = viewModel.importTomlValues(text, fieldsState.toMap())
            fieldsState.clear()
            fieldsState.putAll(updated)
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.settings_toml_imported_msg)) }
        }
    }

    val importResolversLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val selected = profile
        if (uri != null && selected != null) {
            val text = context.readTextFromUri(uri)
            val fileName = context.readDisplayName(uri) ?: "client_resolvers.txt"
            scope.launch {
                val result = withContext(Dispatchers.Default) {
                    ResolverAnalyzer.analyzeAndNormalize(text, fileName)
                }
                if (result == null || result.normalizedText.isBlank()) {
                    snackbarHostState.showSnackbar(context.getString(R.string.settings_resolvers_empty_msg))
                    return@launch
                }
                viewModel.importResolvers(selected, result)
                val statsDetails = context.getString(
                    if (result.stats.truncated) R.string.resolvers_import_stats_capped
                    else R.string.resolvers_import_stats,
                    result.stats.uniqueResolvers,
                    result.stats.duplicateResolvers,
                    result.stats.invalidLines
                )
                snackbarHostState.showSnackbar(
                    context.getString(R.string.settings_resolvers_imported_stats_msg, statsDetails)
                )
            }
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
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.settings_mtu_destination_msg)) }
        }
    }

    LaunchedEffect(profile?.id) {
        fieldsState.clear()
        profile?.let { fieldsState.putAll(defaultValuesFor(it)) }
    }

    Scaffold(
        containerColor = MdvColor.Background,
        topBar = {
            val topActions: @Composable RowScope.() -> Unit = {
                val selected = profile
                if (selected != null) {
                    IconButton(
                        onClick = {
                            viewModel.saveSettings(selected, fieldsState.toMap())
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.settings_saved_msg)) }
                        }
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.action_save))
                    }
                }
            }
            if (onBack != null) {
                MdvBackTopAppBar(
                    title = stringResource(R.string.profile_settings_title),
                    onBack = onBack,
                    actions = topActions
                )
            } else {
                MdvTopAppBar(
                    title = stringResource(R.string.profile_settings_title),
                    actions = topActions
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val maxContentWidth = when {
                maxWidth >= 1200.dp -> 980.dp
                maxWidth >= 840.dp -> 840.dp
                else -> Dp.Unspecified
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                val selected = profile
                if (selected == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = maxContentWidth)
                            .padding(MdvSpace.S6),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(R.string.settings_no_profile_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(MdvSpace.S2))
                        Text(
                            stringResource(R.string.settings_no_profile_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MdvColor.OnSurfaceVariant
                        )
                    }
                    return@Box
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = maxContentWidth),
                    contentPadding = PaddingValues(MdvSpace.S4),
                    verticalArrangement = Arrangement.spacedBy(MdvSpace.S3),
                    state = listState
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.settings_editing_profile, selected.name),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MdvColor.OnSurface
                        )
                        Spacer(modifier = Modifier.height(MdvSpace.S1))
                        Row(horizontalArrangement = Arrangement.spacedBy(MdvSpace.S2)) {
                            MdvPrimaryActionButton(
                                text = stringResource(R.string.action_import_toml),
                                onClick = {
                                    runCatching {
                                        importTomlLauncher.launch(
                                            arrayOf(
                                                "application/toml",
                                                "text/x-toml",
                                                "text/plain",
                                                "application/octet-stream",
                                                "*/*"
                                            )
                                        )
                                    }.onFailure { err ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(
                                                    R.string.settings_file_picker_error,
                                                    err.message ?: context.getString(R.string.common_unknown_error)
                                                )
                                            )
                                        }
                                    }
                                },
                                icon = Icons.Filled.UploadFile
                            )
                            MdvPrimaryActionButton(
                                text = stringResource(R.string.action_export_toml),
                                onClick = {
                                    pendingExportContent = viewModel.exportConfigToml(selected, fieldsState.toMap())
                                    exportLauncher.launch("${selected.name}_client_config.toml")
                                },
                                icon = Icons.Filled.Download
                            )
                        }
                        Spacer(modifier = Modifier.height(MdvSpace.S1))
                        MdvPrimaryActionButton(
                            text = stringResource(R.string.action_import_resolvers),
                            onClick = { importResolversLauncher.launch(arrayOf("text/*")) },
                            icon = Icons.Filled.UploadFile
                        )
                        Spacer(modifier = Modifier.height(MdvSpace.S1))
                        MdvPrimaryActionButton(
                            text = stringResource(R.string.action_pick_mtu_destination),
                            onClick = {
                                val selectedName = selected.name.trim().ifBlank { "profile" }
                                pickMtuExportLauncher.launch("${selectedName}_mtu_results.log")
                            },
                            icon = Icons.Filled.Download
                        )
                    }

                    val socksAuthEnabled = fieldsState["SOCKS5_AUTH"].equals("true", ignoreCase = true)
                    items(sectionOrder, key = { "section_${it.name}" }) { section ->
                        val expanded = sectionExpanded[section] ?: false
                        MdvSectionCard(
                            title = stringResource(section.titleRes),
                            expanded = expanded,
                            onToggle = { sectionExpanded[section] = !expanded }
                        )
                        if (!expanded) return@items

                        Spacer(modifier = Modifier.height(MdvSpace.S1))
                        if (section == Section.DNS) {
                            val dnsEnabled = fieldsState["LOCAL_DNS_ENABLED"].equals("true", ignoreCase = true)
                            val dnsPort = fieldsState["LOCAL_DNS_PORT"]?.toIntOrNull() ?: 53
                            if (dnsEnabled && dnsPort <= 1024) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MdvColor.ErrorContainer
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_dns_root_warning, dnsPort),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MdvColor.Error
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(MdvSpace.S2))
                            }
                        }
                        sections[section].orEmpty().forEach { field ->
                            if ((field.key == "SOCKS5_USER" || field.key == "SOCKS5_PASS") && !socksAuthEnabled) {
                                return@forEach
                            }
                            val value by remember(field.key) { derivedStateOf { fieldsState[field.key].orEmpty() } }
                            ConfigFieldCard(
                                field = field,
                                value = value,
                                onChange = { fieldsState[field.key] = it }
                            )
                            Spacer(modifier = Modifier.height(MdvSpace.S2))
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(MdvSpace.S2))
                        MdvPrimaryActionButton(
                            text = stringResource(R.string.action_save_settings),
                            onClick = {
                                viewModel.saveSettings(selected, fieldsState.toMap())
                                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.settings_saved_msg)) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Filled.Save
                        )
                    }
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
    MdvSettingFieldCard {
        Column {
            when (field.type) {
                FieldType.BOOL -> {
                    val checked = value.equals("true", ignoreCase = true)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(field.key, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(field.helperRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurfaceVariant
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
                    val displayLabel = field.options.firstOrNull { it.value == value }?.let { stringResource(it.labelRes) } ?: value
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedTextField(
                                value = displayLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(field.key) },
                                supportingText = { Text(stringResource(field.helperRes)) },
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
                                        text = { Text(stringResource(option.labelRes)) },
                                        onClick = {
                                            onChange(option.value)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                FieldType.TEXT -> {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onChange,
                        label = { Text(field.key) },
                        supportingText = {
                            Text(
                                text = stringResource(field.helperRes),
                                style = LocalTextStyle.current.copy(textDirection = TextDirection.Content)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = field.keyboardType)
                    )
                }
            }
        }
    }
}

private fun writeTextToUri(context: Context, uri: Uri, content: String) {
    runCatching {
        context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
            it.write(content)
        }
    }
}

private fun defaultValuesFor(profile: ProfileEntity): Map<String, String> {
    val advanced = parseAdvancedJson(profile.advancedJson)
    fun adv(key: String, fallback: String): String {
        return advanced[key]?.trim().takeUnless { it.isNullOrEmpty() } ?: fallback
    }

    return buildMap {
        put("DOMAINS", parseDomainsJson(profile.domains).joinToString(", "))
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
        put("RESOLVER_BALANCING_STRATEGY", profile.resolverBalancingStrategy.takeIf { it != 0 }?.toString() ?: "3")
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
        put("AUTO_REMOVE_LOW_MTU_SERVERS", adv("AUTO_REMOVE_LOW_MTU_SERVERS", "true"))
        put("MIN_UPLOAD_MTU", adv("MIN_UPLOAD_MTU", "38"))
        put("MIN_DOWNLOAD_MTU", adv("MIN_DOWNLOAD_MTU", "200"))
        put("MAX_UPLOAD_MTU", adv("MAX_UPLOAD_MTU", "150"))
        put("MAX_DOWNLOAD_MTU", adv("MAX_DOWNLOAD_MTU", "4000"))
        put("MTU_TEST_RETRIES", adv("MTU_TEST_RETRIES", "2"))
        put("MTU_TEST_TIMEOUT", adv("MTU_TEST_TIMEOUT", "2.0"))
        put("MTU_TEST_PARALLELISM", adv("MTU_TEST_PARALLELISM", "32"))
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
        put("ARQ_WINDOW_SIZE", adv("ARQ_WINDOW_SIZE", "1000"))
        put("MAX_PACKETS_PER_BATCH", adv("MAX_PACKETS_PER_BATCH", "8"))
        put("ARQ_INITIAL_RTO_SECONDS", adv("ARQ_INITIAL_RTO_SECONDS", "0.5"))
        put("ARQ_MAX_RTO_SECONDS", adv("ARQ_MAX_RTO_SECONDS", "3.0"))
        put("ARQ_CONTROL_INITIAL_RTO_SECONDS", adv("ARQ_CONTROL_INITIAL_RTO_SECONDS", "0.5"))
        put("ARQ_CONTROL_MAX_RTO_SECONDS", adv("ARQ_CONTROL_MAX_RTO_SECONDS", "2.0"))
        put("ARQ_MAX_CONTROL_RETRIES", adv("ARQ_MAX_CONTROL_RETRIES", "126"))
        put("ARQ_MAX_DATA_RETRIES", adv("ARQ_MAX_DATA_RETRIES", "126"))
        put("ARQ_DATA_PACKET_TTL_SECONDS", adv("ARQ_DATA_PACKET_TTL_SECONDS", "2400.0"))
        put("ARQ_CONTROL_PACKET_TTL_SECONDS", adv("ARQ_CONTROL_PACKET_TTL_SECONDS", "1200.0"))
        put("ARQ_DATA_NACK_MAX_GAP", adv("ARQ_DATA_NACK_MAX_GAP", "32"))
        put("ARQ_DATA_NACK_INITIAL_DELAY_SECONDS", adv("ARQ_DATA_NACK_INITIAL_DELAY_SECONDS", "0.1"))
        put("ARQ_DATA_NACK_REPEAT_SECONDS", adv("ARQ_DATA_NACK_REPEAT_SECONDS", "0.8"))
        put("ARQ_INACTIVITY_TIMEOUT_SECONDS", adv("ARQ_INACTIVITY_TIMEOUT_SECONDS", "1800.0"))
        put("ARQ_TERMINAL_DRAIN_TIMEOUT_SECONDS", adv("ARQ_TERMINAL_DRAIN_TIMEOUT_SECONDS", "120.0"))
        put("ARQ_TERMINAL_ACK_WAIT_TIMEOUT_SECONDS", adv("ARQ_TERMINAL_ACK_WAIT_TIMEOUT_SECONDS", "90.0"))
        put("LOG_LEVEL", profile.logLevel)
    }
}
