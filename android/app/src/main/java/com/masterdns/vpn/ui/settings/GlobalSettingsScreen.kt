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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterdns.vpn.util.GlobalSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsScreen(vm: GlobalSettingsViewModel = viewModel()) {
    val current by vm.settings.collectAsState()
    val installedApps by vm.installedApps.collectAsState()
    var draft by remember(current) { mutableStateOf(current) }
    var modeExpanded by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var availableQuery by remember { mutableStateOf("") }
    var selectedQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("AVAILABLE") }
    var draftAppSelection by remember { mutableStateOf(parseCsv(current.splitPackagesCsv).toMutableSet()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Split Tunnel Apps")
                                    Text(
                                        "${parseCsv(draft.splitPackagesCsv).size} selected apps - tap to choose",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                vm.save(normalize(draft))
                                scope.launch { snackbarHostState.showSnackbar("Global settings saved and applied") }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save Global Settings")
                        }
                    }
                }
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
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Select Split-Tunnel Apps", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Choose apps that should use VPN tunnel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = activeTab == "SELECTED",
                            onClick = { activeTab = "SELECTED" },
                            label = { Text("Selected ${selectedApps.size}") }
                        )
                        FilterChip(
                            selected = activeTab == "AVAILABLE",
                            onClick = { activeTab = "AVAILABLE" },
                            label = { Text("Available ${availableApps.size}") }
                        )
                    }

                    if (activeTab == "SELECTED") {
                        OutlinedTextField(
                            value = selectedQuery,
                            onValueChange = { selectedQuery = it },
                            label = { Text("Search selected apps") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = availableQuery,
                            onValueChange = { availableQuery = it },
                            label = { Text("Search available apps") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                draftAppSelection = draftAppSelection.toMutableSet().apply {
                                    addAll(availableFiltered.map { it.packageName })
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Select Visible")
                        }
                        OutlinedButton(
                            onClick = { draftAppSelection = mutableSetOf() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Select None")
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        ) {
                            val appsToShow = if (activeTab == "SELECTED") selectedFiltered else availableFiltered
                            val emptyText = if (activeTab == "SELECTED") {
                                "No selected app matches your search"
                            } else {
                                "No available app matches your search"
                            }

                            Text(
                                if (activeTab == "SELECTED") "Selected Apps" else "Available Apps",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )

                            if (appsToShow.isEmpty()) {
                                Text(
                                    emptyText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                draft = draft.copy(splitPackagesCsv = draftAppSelection.sorted().joinToString(","))
                                showAppPicker = false
                            }
                        ) {
                            Text("Apply")
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
