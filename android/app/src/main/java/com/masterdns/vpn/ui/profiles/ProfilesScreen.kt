package com.masterdns.vpn.ui.profiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.masterdns.vpn.util.ImportedResolverFile
import com.masterdns.vpn.util.ResolverAnalyzer
import com.masterdns.vpn.util.ResolverStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var importedResolvers by remember { mutableStateOf<ImportedResolverFile?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Export dialog state
    var exportTarget by remember { mutableStateOf<ProfileEntity?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readTextFromUri(context, uri)
        val name = readDisplayName(context, uri)
            ?: context.getString(R.string.profiles_imported_profile_default)
        if (text.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.profiles_invalid_toml_msg)) }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val imported = viewModel.importProfileFromToml(text, name)
            if (imported != null) {
                snackbarHostState.showSnackbar(context.getString(R.string.profiles_toml_imported_msg))
            } else {
                snackbarHostState.showSnackbar(context.getString(R.string.profiles_invalid_toml_msg))
            }
        }
    }

    val importResolversLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                ResolverAnalyzer.importUriToCache(context, uri)
            }
            if (imported == null) {
                snackbarHostState.showSnackbar(context.getString(R.string.profiles_resolvers_empty_msg))
                return@launch
            }
            ResolverAnalyzer.discardImportedResolver(importedResolvers)
            importedResolvers = imported
            showEditor = true
            snackbarHostState.showSnackbar(
                context.getString(R.string.profiles_resolvers_imported_stats_msg, imported.stats.summary())
            )
        }
    }

    var exportToSaveLocked by remember { mutableStateOf<Boolean?>(null) }
    val saveExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/toml")
    ) { uri ->
        if (uri != null && exportTarget != null && exportToSaveLocked != null) {
            scope.launch {
                val config = viewModel.exportProfileToml(exportTarget!!.id, exportToSaveLocked!!)
                if (config != null) {
                    runCatching {
                        context.grantUriPermission(
                            context.packageName,
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(config.toByteArray())
                            outputStream.flush()
                        }
                    }
                    snackbarHostState.showSnackbar(context.getString(R.string.profiles_export_saved))
                }
            }
        }
        exportTarget = null
        exportToSaveLocked = null
    }

    // Export dialog
    if (exportTarget != null && exportToSaveLocked == null) {
        ExportProfileDialog(
            profileName = exportTarget!!.name,
            onDismiss = { exportTarget = null },
            onShare = { locked ->
                viewModel.exportProfile(context, exportTarget!!.id, exportTarget!!.name, lockIdentity = locked)
                exportTarget = null
            },
            onSaveToFile = { locked ->
                exportToSaveLocked = locked
                val safeName = exportTarget!!.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val suffix = if (locked) "_locked" else ""
                saveExportLauncher.launch("${safeName}_client_config$suffix.toml")
            }
        )
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
                        importLauncher.launch(
                            arrayOf(
                                "application/toml",
                                "text/x-toml",
                                "text/plain",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    }) {
                        Icon(Icons.Filled.UploadFile, contentDescription = stringResource(R.string.action_import_toml))
                    }
                    IconButton(onClick = {
                        editingProfile = null
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
                importedResolvers = importedResolvers,
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
                    importedResolvers = null
                },
                onDismiss = {
                    ResolverAnalyzer.discardImportedResolver(importedResolvers)
                    showEditor = false
                    editingProfile = null
                    importedResolvers = null
                },
                viewModel = viewModel,
                snackbarHostState = snackbarHostState
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
                            importedResolvers = null
                            showEditor = true
                        },
                        onDelete = { viewModel.deleteProfile(profile) },
                        onExport = { exportTarget = profile }
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
    onDelete: () -> Unit,
    onExport: () -> Unit
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                Text(
                    text = parseDomainList(profile.domains).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MdvColor.OnSurfaceVariant
                )
                ProfileHealthRow(profile)
            }

            IconButton(onClick = onExport, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Share, contentDescription = "Export", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.profiles_edit), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.profiles_settings), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.profiles_delete), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ProfileHealthRow(profile: ProfileEntity) {
    val imported = ResolverAnalyzer.profileImportedResolver(profile)
    val sourceText = if (imported != null) {
        stringResource(R.string.profiles_source_file, imported.displayName)
    } else {
        stringResource(R.string.profiles_source_inline)
    }
    val resolverCount = imported?.stats?.uniqueUsableIps ?: ResolverAnalyzer.resolverCount(profile)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AssistChip(
            onClick = {},
            modifier = Modifier.weight(1f, fill = false),
            label = {
                Text(
                    text = sourceText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    if (imported != null) Icons.Filled.Description else Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        )
        AssistChip(
            onClick = {},
            label = {
                Text(
                    text = stringResource(R.string.profiles_resolver_count_badge, resolverCount),
                    maxLines = 1
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditorDialog(
    profile: ProfileEntity?,
    importedResolvers: ImportedResolverFile?,
    onImportResolvers: () -> Unit,
    onSave: (ProfileEntity) -> Unit,
    onDismiss: () -> Unit,
    viewModel: ProfilesViewModel,
    snackbarHostState: SnackbarHostState
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    // Multi-domain as list
    var domainList by remember {
        mutableStateOf(
            if (profile != null) parseDomainList(profile.domains).toMutableList()
            else mutableListOf("")
        )
    }
    var domainInput by remember { mutableStateOf("") }
    var encryptionKey by remember { mutableStateOf(profile?.encryptionKey.orEmpty()) }
    var resolvers by remember { mutableStateOf(profile?.resolvers ?: "8.8.8.8") }
    var resolverFile by remember(profile?.id) { mutableStateOf(profile?.let { ResolverAnalyzer.profileImportedResolver(it) }) }
    var resolverStats by remember(profile?.id) {
        mutableStateOf(resolverFile?.stats ?: ResolverAnalyzer.analyzeText(resolvers))
    }
    var showKey by remember { mutableStateOf(false) }
    var showResolversEditor by remember { mutableStateOf(false) }
    val usingResolverFile = resolverFile != null
    val largeResolversText = !usingResolverFile && resolvers.length > 6000

    LaunchedEffect(profile?.id) {
        if (profile != null) {
            name = profile.name
            domainList = parseDomainList(profile.domains).toMutableList()
            encryptionKey = profile.encryptionKey
            resolvers = profile.resolvers
            resolverFile = ResolverAnalyzer.profileImportedResolver(profile)
            resolverStats = resolverFile?.stats ?: ResolverAnalyzer.analyzeText(profile.resolvers)
            showResolversEditor = false
        }
    }

    LaunchedEffect(importedResolvers) {
        if (importedResolvers != null) {
            resolverFile = importedResolvers
            resolverStats = importedResolvers.stats
            resolvers = ""
        }
    }

    LaunchedEffect(resolvers, resolverFile?.cachedPath) {
        resolverStats = resolverFile
            ?.let { ResolverAnalyzer.analyzeCachedFile(it.cachedPath, it.displayName) ?: it.stats }
            ?: ResolverAnalyzer.analyzeText(resolvers)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profiles_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // ── Multi-Domain editor ──────────────────────────────────
                Text(
                    stringResource(R.string.profiles_domain_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MdvColor.OnSurfaceVariant
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    domainList.forEachIndexed { index, domain ->
                        if (domain.isNotBlank()) {
                            InputChip(
                                selected = false,
                                onClick = {
                                    val newList = domainList.toMutableList()
                                    newList.removeAt(index)
                                    domainList = newList
                                },
                                label = { Text(domain, style = MaterialTheme.typography.bodySmall) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        label = { Text("Add domain") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("v.example.com") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    IconButton(
                        onClick = {
                            val d = domainInput.trim()
                            if (d.isNotEmpty() && !domainList.contains(d)) {
                                val newList = domainList.toMutableList()
                                newList.add(d)
                                domainList = newList
                            }
                            domainInput = ""
                        }
                    ) {
                        Icon(Icons.Filled.AddCircle, contentDescription = "Add domain", tint = MdvColor.PrimaryContainer)
                    }
                }

                // ── Encryption Key ───────────────────────────────────────
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

                // ── Import Actions ───────────────────────────────────────────
                val importTomlLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val text = readTextFromUri(context, uri).trim()
                    if (text.isBlank()) {
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.profiles_invalid_toml_msg)) }
                        return@rememberLauncherForActivityResult
                    }
                    scope.launch {
                        val parsed = viewModel.previewProfileFromToml(text, name.ifEmpty { "Imported" })
                        if (parsed != null) {
                            name = parsed.name
                            domainList = parseDomainList(parsed.domains).toMutableList()
                            encryptionKey = parsed.encryptionKey
                            resolvers = parsed.resolvers
                            snackbarHostState.showSnackbar(context.getString(R.string.profiles_toml_imported_msg))
                        } else {
                            snackbarHostState.showSnackbar(context.getString(R.string.profiles_invalid_toml_msg))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            importTomlLauncher.launch(arrayOf("application/toml", "text/x-toml", "text/plain", "application/octet-stream", "*/*"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_import_toml))
                    }

                    OutlinedButton(
                        onClick = onImportResolvers,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.profiles_import_resolvers_short))
                    }
                }

                if (usingResolverFile) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (resolverFile?.cachedPath == importedResolvers?.cachedPath) {
                                    ResolverAnalyzer.discardImportedResolver(importedResolvers)
                                }
                                resolverFile = null
                                resolvers = "8.8.8.8"
                                showResolversEditor = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.profiles_use_inline_resolvers))
                        }
                    }
                    ResolverStatsCard(
                        title = stringResource(R.string.profiles_imported_resolver_file, resolverFile?.displayName.orEmpty()),
                        stats = resolverStats
                    )
                } else if (!showResolversEditor && largeResolversText) {
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
                        supportingText = { Text(stringResource(R.string.profiles_resolvers_stats_line, resolverStats.summary())) },
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
                    val baseProfile = profile ?: ProfileEntity(name = "", domains = "")
                    val effectiveDomains = domainList.filter { it.isNotBlank() }.toMutableList()
                    val dInput = domainInput.trim()
                    if (dInput.isNotEmpty() && !effectiveDomains.contains(dInput)) {
                        effectiveDomains.add(dInput)
                    }
                    if (effectiveDomains.isEmpty()) effectiveDomains.add("v.domain.com") // fallback
                    val domainJson = gson.toJson(effectiveDomains)
                    val preparedProfile = baseProfile.copy(
                        name = name.trim().ifEmpty { "Profile" },
                        domains = domainJson,
                        encryptionKey = encryptionKey
                    )
                    onSave(
                        resolverFile
                            ?.let { ResolverAnalyzer.withImportedResolver(preparedProfile, it) }
                            ?: ResolverAnalyzer.withInlineResolvers(preparedProfile, resolvers)
                    )
                },
                enabled = name.isNotBlank() && (domainList.any { it.isNotBlank() } || domainInput.isNotBlank())
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

@Composable
private fun ResolverStatsCard(title: String, stats: ResolverStats?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceHigh)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (stats == null) {
                Text(
                    stringResource(R.string.profiles_resolver_stats_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MdvColor.OnSurfaceVariant
                )
            } else {
                Text(
                    stats.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MdvColor.OnSurfaceVariant
                )
                Text(
                    stringResource(
                        R.string.profiles_resolver_stats_detail,
                        stats.rawLines,
                        stats.blankLines,
                        stats.commentLines,
                        stats.customPorts,
                        stats.skippedCidrs
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MdvColor.OnSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ExportProfileDialog(
    profileName: String,
    onDismiss: () -> Unit,
    onShare: (Boolean) -> Unit,
    onSaveToFile: (Boolean) -> Unit
) {
    var lockIdentity by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_export_title, profileName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = lockIdentity,
                        onCheckedChange = { lockIdentity = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.profiles_export_lock_identity),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.profiles_export_lock_identity_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MdvColor.OnSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { onShare(lockIdentity) }) {
                Text(stringResource(R.string.profiles_export_share))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSaveToFile(lockIdentity) }) {
                    Text(stringResource(R.string.profiles_export_save_file))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

private val gson = Gson()

private fun parseDomainList(json: String): List<String> {
    return try {
        val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, String::class.java).type
        val list = gson.fromJson<List<String>>(json, type)
        list?.filter { it.isNotBlank() } ?: listOf(json.trim())
    } catch (_: Exception) {
        listOf(json.trim().removeSurrounding("\""))
    }
}

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
