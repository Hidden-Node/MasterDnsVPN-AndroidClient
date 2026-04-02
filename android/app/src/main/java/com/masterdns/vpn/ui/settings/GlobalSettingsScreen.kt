package com.masterdns.vpn.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.masterdns.vpn.util.GlobalSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsScreen(vm: GlobalSettingsViewModel = viewModel()) {
    val current by vm.settings.collectAsState()
    val installedApps by vm.installedApps.collectAsState()
    var draft by remember(current) { mutableStateOf(current) }
    var modeExpanded by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var appQuery by remember { mutableStateOf("") }
    var draftAppSelection by remember { mutableStateOf(parseCsv(current.splitPackagesCsv).toMutableSet()) }
    var sortMode by remember { mutableStateOf("NAME") }
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
                                    appQuery = ""
                                    showAppPicker = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Split Tunnel Apps")
                                    Text(
                                        "${parseCsv(draft.splitPackagesCsv).size} selected apps - tap to choose",
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
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
        val orderedApps = when (sortMode) {
            "RECENT" -> installedApps.sortedByDescending { it.firstInstallTime }
            else -> installedApps.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
        }
        val filteredApps = orderedApps.filter {
            val q = appQuery.trim().lowercase()
            q.isEmpty() ||
                it.label.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q)
        }
        val selectedApps = filteredApps.filter { draftAppSelection.contains(it.packageName) }
        val unselectedApps = filteredApps.filterNot { draftAppSelection.contains(it.packageName) }
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            title = {
                Column {
                    Text("Select Split-Tunnel Apps")
                    Text(
                        "Choose apps that should use VPN tunnel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = appQuery,
                        onValueChange = { appQuery = it },
                        label = { Text("Search apps") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { sortMode = "NAME" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (sortMode == "NAME") "Sort: Name *" else "Sort: Name")
                        }
                        OutlinedButton(
                            onClick = { sortMode = "RECENT" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (sortMode == "RECENT") "Sort: Recent *" else "Sort: Recent")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                draftAppSelection = draftAppSelection.toMutableSet().apply {
                                    addAll(filteredApps.map { it.packageName })
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Select All")
                        }
                        OutlinedButton(
                            onClick = {
                                draftAppSelection = draftAppSelection.toMutableSet().apply {
                                    removeAll(filteredApps.map { it.packageName }.toSet())
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Clear Visible")
                        }
                    }
                    OutlinedButton(
                        onClick = { draftAppSelection = mutableSetOf() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Select None (Global)")
                    }
                    val recentApps = orderedApps.take(8)
                    if (recentApps.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    "Recently Installed",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp)
                                ) {
                                    items(recentApps, key = { "recent_${it.packageName}" }) { app ->
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text("Selected ${selectedApps.size}") }
                        )
                        FilterChip(
                            selected = false,
                            onClick = {},
                            label = { Text("Available ${unselectedApps.size}") }
                        )
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "Selected Apps",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (selectedApps.isEmpty()) {
                                Text(
                                    "No app selected yet",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(150.dp)
                                ) {
                                    items(selectedApps, key = { it.packageName }) { app ->
                                        AppRow(
                                            app = app,
                                            checked = true,
                                            onToggle = {
                                                draftAppSelection = draftAppSelection.toMutableSet().apply {
                                                    remove(app.packageName)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "Available Apps",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                            ) {
                                items(unselectedApps.take(300), key = { it.packageName }) { app ->
                                    AppRow(
                                        app = app,
                                        checked = false,
                                        onToggle = {
                                            draftAppSelection = draftAppSelection.toMutableSet().apply {
                                                add(app.packageName)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (filteredApps.isEmpty()) {
                        Text("No apps found. Check search text or package visibility.")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        draft = draft.copy(splitPackagesCsv = draftAppSelection.sorted().joinToString(","))
                        showAppPicker = false
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAppPicker = false }) {
                    Text("Cancel")
                }
            }
        )
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
