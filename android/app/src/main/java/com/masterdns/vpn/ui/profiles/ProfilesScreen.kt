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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.masterdns.vpn.data.local.ProfileEntity
import com.masterdns.vpn.ui.theme.ConnectedGreen
import kotlinx.coroutines.launch

private data class ImportedProfileDraft(
    val fileName: String,
    val domain: String,
    val encryptionKey: String
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
            fileName = readDisplayName(context, uri) ?: "Imported Profile",
            tomlContent = text
        )
        if (draft == null) {
            scope.launch { snackbarHostState.showSnackbar("Invalid TOML: DOMAIN/ENCRYPTION_KEY not found") }
            return@rememberLauncherForActivityResult
        }
        importedDraft = draft
        editingProfile = null
        showEditor = true
        scope.launch { snackbarHostState.showSnackbar("TOML imported into profile form") }
    }
    val importResolversLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readTextFromUri(context, uri).trim()
        if (text.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("Resolvers file is empty") }
            return@rememberLauncherForActivityResult
        }
        importedResolvers = text
        editingProfile = null
        showEditor = true
        scope.launch { snackbarHostState.showSnackbar("Resolvers imported into profile form") }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Profiles", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editingProfile = null
                        importedDraft = null
                        importedResolvers = null
                        showEditor = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add Profile")
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
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No profiles yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledTonalButton(onClick = {
                        editingProfile = null
                        importedDraft = null
                        importedResolvers = null
                        showEditor = true
                    }) {
                        Text("Create Profile")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
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
                    contentDescription = "Selected",
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp))
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
    var name by remember(profile, importedDraft) { mutableStateOf(importedDraft?.fileName ?: profile?.name.orEmpty()) }
    var domains by remember(profile, importedDraft) {
        mutableStateOf(importedDraft?.domain ?: profile?.domains?.removeSurrounding("[\"", "\"]").orEmpty())
    }
    var encryptionKey by remember(profile, importedDraft) { mutableStateOf(importedDraft?.encryptionKey ?: profile?.encryptionKey.orEmpty()) }
    var encryptionMethod by remember { mutableIntStateOf(profile?.encryptionMethod ?: 1) }
    var resolvers by remember(profile, importedResolvers) { mutableStateOf(importedResolvers ?: profile?.resolvers ?: "8.8.8.8") }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile != null) "Edit Profile" else "New Profile") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
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
                            Text("Import TOML")
                        }
                        OutlinedButton(
                            onClick = onImportResolvers,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Description, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Import Resolvers")
                        }
                    }
                }

                OutlinedTextField(
                    value = domains,
                    onValueChange = { domains = it },
                    label = { Text("Domain (e.g., v.domain.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = encryptionKey,
                    onValueChange = { encryptionKey = it },
                    label = { Text("Encryption Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "Toggle visibility"
                            )
                        }
                    }
                )

                // Encryption method dropdown
                var expanded by remember { mutableStateOf(false) }
                val methods = listOf("None", "XOR", "ChaCha20", "AES-128-GCM", "AES-192-GCM", "AES-256-GCM")
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = methods.getOrElse(encryptionMethod) { "XOR" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Encryption Method") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        methods.forEachIndexed { index, methodName ->
                            DropdownMenuItem(
                                text = { Text(methodName) },
                                onClick = {
                                    encryptionMethod = index
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = resolvers,
                    onValueChange = { resolvers = it },
                    label = { Text("Resolvers (one per line)") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    val domainJson = "[\"${domains.trim()}\"]"
                    onSave(
                        (profile ?: ProfileEntity(name = "", domains = "")).copy(
                            name = name.trim().ifEmpty { "Profile" },
                            domains = domainJson,
                            encryptionKey = encryptionKey,
                            encryptionMethod = encryptionMethod,
                            resolvers = resolvers.trim()
                        )
                    )
                },
                enabled = name.isNotBlank() && domains.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
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

private fun parseProfileTomlForImport(fileName: String, tomlContent: String): ImportedProfileDraft? {
    var domain: String? = null
    var encryptionKey: String? = null
    tomlContent.lineSequence().forEach { raw ->
        val line = raw.substringBefore("#").trim()
        if (line.isEmpty() || "=" !in line) return@forEach
        val key = line.substringBefore("=").trim()
        val valueRaw = line.substringAfter("=").trim()
        when (key) {
            "DOMAINS" -> {
                domain = valueRaw
                    .removePrefix("[")
                    .removeSuffix("]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .firstOrNull { it.isNotBlank() }
            }
            "ENCRYPTION_KEY" -> {
                encryptionKey = valueRaw.removeSurrounding("\"").trim()
            }
        }
    }

    val parsedDomain = domain?.takeIf { it.isNotBlank() } ?: return null
    val parsedKey = encryptionKey?.takeIf { it.isNotBlank() } ?: return null
    return ImportedProfileDraft(
        fileName = fileName,
        domain = parsedDomain,
        encryptionKey = parsedKey
    )
}
