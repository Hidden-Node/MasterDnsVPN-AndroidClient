package com.masterdns.vpn.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterdns.vpn.R
import com.masterdns.vpn.ui.components.mdv.controls.MdvFilterChip
import com.masterdns.vpn.ui.components.mdv.controls.MdvPrimaryActionButton
import com.masterdns.vpn.ui.components.mdv.controls.MdvTopAppBar
import com.masterdns.vpn.ui.theme.MdvColor
import com.masterdns.vpn.ui.theme.MdvSpace
import com.masterdns.vpn.util.GlobalSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsScreen(vm: GlobalSettingsViewModel = viewModel()) {
    val current by vm.settings.collectAsState()
    val installedApps by vm.installedApps.collectAsState()
    var draft by remember(current) { mutableStateOf(current) }
    var sharingSocksPortText by remember(current.internetSharingSocksPort) {
        mutableStateOf(current.internetSharingSocksPort.toString())
    }
    var sharingHttpPortText by remember(current.internetSharingHttpPort) {
        mutableStateOf(current.internetSharingHttpPort.toString())
    }
    var modeExpanded by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var availableQuery by remember { mutableStateOf("") }
    var selectedQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("AVAILABLE") }
    var draftAppSelection by remember { mutableStateOf(parseCsv(current.splitPackagesCsv).toMutableSet()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val socksPortValue = sharingSocksPortText.toIntOrNull()
    val httpPortValue = sharingHttpPortText.toIntOrNull()
    val socksPortMissing = sharingSocksPortText.isBlank()
    val httpPortMissing = sharingHttpPortText.isBlank()
    val socksPortRequiresRoot = socksPortValue != null && socksPortValue in 1..1024
    val httpPortRequiresRoot = httpPortValue != null && httpPortValue in 1..1024
    fun saveGlobalSettings() {
        if (socksPortMissing || httpPortMissing) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.global_settings_ports_required_msg))
            }
            return
        }
        if (socksPortValue !in 1025..65535 || httpPortValue !in 1025..65535) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.global_settings_ports_range_msg))
            }
            return
        }
        val safeSocksPort = socksPortValue ?: return
        val safeHttpPort = httpPortValue ?: return
        draft = draft.copy(
            internetSharingSocksPort = safeSocksPort,
            internetSharingHttpPort = safeHttpPort
        )
        vm.save(normalize(draft))
        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.global_settings_saved_msg)) }
    }

    Scaffold(
        containerColor = MdvColor.Background,
        topBar = {
            MdvTopAppBar(
                title = stringResource(R.string.settings_title),
                actions = {
                    IconButton(
                        onClick = ::saveGlobalSettings
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = stringResource(R.string.action_save)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(MdvSpace.S4),
            verticalArrangement = Arrangement.spacedBy(MdvSpace.S3)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceHigh)) {
                    Column(modifier = Modifier.padding(MdvSpace.S3), verticalArrangement = Arrangement.spacedBy(MdvSpace.S3)) {
                        ExposedDropdownMenuBox(
                            expanded = modeExpanded,
                            onExpandedChange = { modeExpanded = !modeExpanded }
                        ) {
                            OutlinedTextField(
                                value = draft.connectionMode,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Connection Mode") },
                                supportingText = { Text("VPN mode or Proxy mode (SOCKS only)") },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowDropDown,
                                        contentDescription = null
                                    )
                                },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                                listOf("VPN", "PROXY").forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode) },
                                        onClick = {
                                            draft = draft.copy(connectionMode = mode)
                                            modeExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        RowSwitch(
                            title = "Split Tunneling",
                            checked = draft.splitTunnelingEnabled,
                            onChecked = { draft = draft.copy(splitTunnelingEnabled = it) }
                        )

                        if (draft.splitTunnelingEnabled) {
                            Card(
                                onClick = {
                                    draftAppSelection = parseCsv(draft.splitPackagesCsv).toMutableSet()
                                    availableQuery = ""
                                    selectedQuery = ""
                                    activeTab = "AVAILABLE"
                                    showAppPicker = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(MdvSpace.S3)) {
                                    Text(stringResource(R.string.split_tunnel_apps_title))
                                    Text(
                                        stringResource(R.string.split_tunnel_apps_selected_count, parseCsv(draft.splitPackagesCsv).size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MdvColor.OnSurfaceVariant
                                    )
                                }
                            }
                        }

                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceHigh)) {
                    Column(
                        modifier = Modifier.padding(MdvSpace.S3),
                        verticalArrangement = Arrangement.spacedBy(MdvSpace.S3)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Sharing Internet", style = MaterialTheme.typography.titleMedium)
                            Switch(
                                checked = draft.internetSharingEnabled,
                                onCheckedChange = { draft = draft.copy(internetSharingEnabled = it) }
                            )
                        }

                        if (draft.internetSharingEnabled) {
                            val localIp = remember { getSystemLocalIp() }

                            if (localIp != null) {
                                Text(
                                    "Local IP: $localIp",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MdvColor.PrimaryContainer
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = sharingSocksPortText,
                                    onValueChange = {
                                        if (it.any { ch -> !ch.isDigit() } || it.length > 5) {
                                            return@OutlinedTextField
                                        }
                                        sharingSocksPortText = it
                                        val port = it.toIntOrNull()
                                        if (port != null && port in 1025..65535) {
                                            draft = draft.copy(internetSharingSocksPort = port)
                                        }
                                    },
                                    label = { Text("SOCKS5 Port") },
                                    isError = socksPortMissing || socksPortRequiresRoot,
                                    supportingText = {
                                        when {
                                            socksPortMissing -> Text("SOCKS5 port is required.")
                                            socksPortRequiresRoot -> Text("Port must be greater than 1024 (ports <=1024 require root).")
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = sharingHttpPortText,
                                    onValueChange = {
                                        if (it.any { ch -> !ch.isDigit() } || it.length > 5) {
                                            return@OutlinedTextField
                                        }
                                        sharingHttpPortText = it
                                        val port = it.toIntOrNull()
                                        if (port != null && port in 1025..65535) {
                                            draft = draft.copy(internetSharingHttpPort = port)
                                        }
                                    },
                                    label = { Text("HTTP Port") },
                                    isError = httpPortMissing || httpPortRequiresRoot,
                                    supportingText = {
                                        when {
                                            httpPortMissing -> Text("HTTP port is required.")
                                            httpPortRequiresRoot -> Text("Port must be greater than 1024 (ports <=1024 require root).")
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            OutlinedTextField(
                                value = draft.internetSharingUser,
                                onValueChange = { draft = draft.copy(internetSharingUser = it) },
                                label = { Text("Username") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = draft.internetSharingPass,
                                onValueChange = { draft = draft.copy(internetSharingPass = it) },
                                label = { Text("Password") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                "Use these endpoints to share your VPN connection with other devices or apps on the same network.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurfaceVariant
                            )
                        }
                    }
                }
            }
            item {
                MdvPrimaryActionButton(
                    text = stringResource(R.string.global_settings_save_button),
                    onClick = ::saveGlobalSettings,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showAppPicker) {
        val selectedApps = installedApps.filter { draftAppSelection.contains(it.packageName) }
        val availableApps = installedApps.filterNot { draftAppSelection.contains(it.packageName) }

        val selectedFiltered = selectedApps.filter {
            val q = selectedQuery.trim().lowercase()
            q.isEmpty() ||
                it.label.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q)
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))

        val availableFiltered = availableApps.filter {
            val q = availableQuery.trim().lowercase()
            q.isEmpty() ||
                it.label.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q)
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))

        Dialog(onDismissRequest = { showAppPicker = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                shape = RoundedCornerShape(16.dp),
                color = MdvColor.Surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MdvSpace.S3),
                    verticalArrangement = Arrangement.spacedBy(MdvSpace.S3)
                ) {
                    Text(stringResource(R.string.split_tunnel_dialog_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.split_tunnel_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MdvColor.OnSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(MdvSpace.S2)) {
                        MdvFilterChip(
                            selected = activeTab == "SELECTED",
                            onClick = { activeTab = "SELECTED" },
                            label = stringResource(R.string.split_tunnel_selected_count, selectedApps.size)
                        )
                        MdvFilterChip(
                            selected = activeTab == "AVAILABLE",
                            onClick = { activeTab = "AVAILABLE" },
                            label = stringResource(R.string.split_tunnel_available_count, availableApps.size)
                        )
                    }

                    if (activeTab == "SELECTED") {
                        OutlinedTextField(
                            value = selectedQuery,
                            onValueChange = { selectedQuery = it },
                            label = { Text(stringResource(R.string.split_tunnel_search_selected)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = availableQuery,
                            onValueChange = { availableQuery = it },
                            label = { Text(stringResource(R.string.split_tunnel_search_available)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(MdvSpace.S2)) {
                        OutlinedButton(
                            onClick = {
                                draftAppSelection = draftAppSelection.toMutableSet().apply {
                                    addAll(availableFiltered.map { it.packageName })
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.split_tunnel_select_visible))
                        }
                        OutlinedButton(
                            onClick = { draftAppSelection = mutableSetOf() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.split_tunnel_select_none))
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MdvColor.SurfaceHigh
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        ) {
                            val appsToShow = if (activeTab == "SELECTED") selectedFiltered else availableFiltered
                            val emptyText = if (activeTab == "SELECTED") {
                                stringResource(R.string.split_tunnel_empty_selected)
                            } else {
                                stringResource(R.string.split_tunnel_empty_available)
                            }

                            Text(
                                if (activeTab == "SELECTED") {
                                    stringResource(R.string.split_tunnel_selected_apps)
                                } else {
                                    stringResource(R.string.split_tunnel_available_apps)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MdvColor.PrimaryContainer
                            )

                            if (appsToShow.isEmpty()) {
                                Text(
                                    emptyText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MdvColor.OnSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 180.dp, max = 260.dp)
                                ) {
                                    items(appsToShow, key = { it.packageName }) { app ->
                                        AppRow(
                                            app = app,
                                            checked = draftAppSelection.contains(app.packageName),
                                            onToggle = {
                                                draftAppSelection = draftAppSelection.toMutableSet().apply {
                                                    if (!add(app.packageName)) remove(app.packageName)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showAppPicker = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        Button(
                            onClick = {
                                draft = draft.copy(splitPackagesCsv = draftAppSelection.sorted().joinToString(","))
                                showAppPicker = false
                            }
                        ) {
                            Text(stringResource(R.string.action_apply))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: GlobalSettingsViewModel.AppEntry,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val appIconBitmap = remember(app.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(48, 48)
        }.getOrNull()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (appIconBitmap != null) {
                Image(
                    bitmap = appIconBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Column {
                Text(text = app.label)
                Text(text = app.packageName)
            }
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() }
        )
    }
}

@Composable
private fun RowSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun parseCsv(value: String): Set<String> {
    return value.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
}

private fun normalize(settings: GlobalSettings): GlobalSettings {
    return settings.copy(
        connectionMode = settings.connectionMode.uppercase(),
        splitPackagesCsv = settings.splitPackagesCsv
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
    )
}

private fun getSystemLocalIp(): String? {
    return try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                    return addr.hostAddress
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}
