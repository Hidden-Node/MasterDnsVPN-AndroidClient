package com.masterdns.vpn.ui.profiles

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.gson.Gson
import com.masterdns.vpn.R
import com.masterdns.vpn.data.local.ProfileEntity
import com.masterdns.vpn.data.local.SubscriptionEntity
import com.masterdns.vpn.ui.components.mdv.cards.MdvCardLow
import com.masterdns.vpn.ui.components.mdv.controls.MdvBackTopAppBar
import com.masterdns.vpn.ui.components.mdv.controls.MdvFilterChip
import com.masterdns.vpn.ui.components.mdv.controls.MdvPrimaryActionButton
import com.masterdns.vpn.ui.scanner.ScannerActivity
import com.masterdns.vpn.ui.theme.ConnectedGreen
import com.masterdns.vpn.ui.theme.MdvColor
import com.masterdns.vpn.ui.theme.MdvRadius
import com.masterdns.vpn.ui.theme.MdvSpace
import com.masterdns.vpn.util.ProfileUrlCodec
import com.masterdns.vpn.util.QrCodeUtil
import com.masterdns.vpn.util.ResolverAnalyzer
import com.masterdns.vpn.util.ResolverImportResult
import com.masterdns.vpn.util.ResolverImportStats
import com.masterdns.vpn.util.parseDomainsJson
import com.masterdns.vpn.util.readDisplayName
import com.masterdns.vpn.util.readTextFromUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ImportedProfileDraft(
    val profile: ProfileEntity,
    val domainList: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onOpenSettings: (Long) -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val isRefreshingSubscriptions by viewModel.isRefreshingSubscriptions.collectAsState()
    var showAddSubscriptionDialog by rememberSaveable { mutableStateOf(false) }
    var addSubscriptionSession by rememberSaveable { mutableStateOf(0) }
    var addSubscriptionError by rememberSaveable { mutableStateOf<String?>(null) }
    var prefilledSubscriptionUrl by rememberSaveable { mutableStateOf("") }
    var showManageSubscriptionsDialog by rememberSaveable { mutableStateOf(false) }
    var selectedSubscriptionId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(subscriptions) {
        if (selectedSubscriptionId != null && subscriptions.none { it.id == selectedSubscriptionId }) {
            selectedSubscriptionId = null
        }
    }

    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editingProfile: ProfileEntity? = editingId?.let { id -> profiles.find { it.id == id } }
    var importedDraft by remember { mutableStateOf<ImportedProfileDraft?>(null) }
    var importedResolvers by remember { mutableStateOf<ResolverImportResult?>(null) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sharingProfile by remember { mutableStateOf<ProfileEntity?>(null) }
    var showAddMenu by rememberSaveable { mutableStateOf(false) }
    var showImportProfilesDialog by rememberSaveable { mutableStateOf(false) }
    var initialImportText by rememberSaveable { mutableStateOf("") }
    var importDialogSession by rememberSaveable { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val qrScanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.profiles_qr_scan_cancelled)) }
            return@rememberLauncherForActivityResult
        }
        val decodedText = result.data?.getStringExtra(ScannerActivity.EXTRA_SCAN_RESULT)?.trim().orEmpty()
        if (decodedText.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.profiles_qr_scan_no_code)) }
            return@rememberLauncherForActivityResult
        }
        val subUrl = ProfileUrlCodec.findSubscriptionUrl(decodedText)
        if (subUrl != null) {
            prefilledSubscriptionUrl = subUrl
            addSubscriptionError = null
            addSubscriptionSession++
            showAddSubscriptionDialog = true
            return@rememberLauncherForActivityResult
        }
        val drafts = runCatching { ProfileUrlCodec.decodeMultiple(decodedText) }.getOrNull()
        if (drafts.isNullOrEmpty()) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.profiles_invalid_link_msg)) }
            return@rememberLauncherForActivityResult
        }
        viewModel.addProfiles(drafts.map { it.toProfileEntity() })
        scope.launch {
            val msg = if (drafts.size == 1) {
                context.getString(R.string.profiles_link_imported_named_msg, drafts.first().name)
            } else {
                context.getString(R.string.profiles_imported_count_msg, drafts.size)
            }
            snackbarHostState.showSnackbar(msg)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = context.readTextFromUri(uri)
        val draft = parseProfileTomlForImport(
            fileName = context.readDisplayName(uri) ?: context.getString(R.string.profiles_imported_profile_default),
            tomlContent = text
        )
        if (draft == null) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.profiles_invalid_toml_msg)) }
            return@rememberLauncherForActivityResult
        }
        importedDraft = draft
        editingId = null
        showEditor = true
        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.profiles_toml_imported_msg)) }
    }
    val importResolversLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val fileName = context.readDisplayName(uri) ?: "client_resolvers.txt"
            val text = withContext(Dispatchers.IO) { context.readTextFromUri(uri) }
            val result = withContext(Dispatchers.Default) {
                ResolverAnalyzer.analyzeAndNormalize(text, fileName)
            }
            if (result == null || result.normalizedText.isBlank()) {
                snackbarHostState.showSnackbar(context.getString(R.string.profiles_resolvers_empty_msg))
                return@launch
            }
            importedResolvers = result
            showEditor = true
            val statsDetails = context.getString(
                if (result.stats.truncated) R.string.resolvers_import_stats_capped
                else R.string.resolvers_import_stats,
                result.stats.uniqueResolvers,
                result.stats.duplicateResolvers,
                result.stats.invalidLines
            )
            snackbarHostState.showSnackbar(
                context.getString(R.string.profiles_resolvers_imported_stats_msg, statsDetails)
            )
        }
    }

    Scaffold(
        containerColor = MdvColor.Background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            MdvBackTopAppBar(
                title = stringResource(R.string.title_profiles),
                onBack = onBack,
                actions = {
                    if (subscriptions.isNotEmpty()) {
                        IconButton(onClick = { showManageSubscriptionsDialog = true }) {
                            Icon(Icons.Filled.FolderSpecial, contentDescription = stringResource(R.string.profiles_subscriptions_manage))
                        }
                        IconButton(
                            onClick = {
                                viewModel.refreshAllSubscriptions { count, errors ->
                                    scope.launch {
                                        if (errors.isEmpty()) {
                                            snackbarHostState.showSnackbar(context.getString(R.string.profiles_subscription_refreshed_msg, count))
                                        } else {
                                            snackbarHostState.showSnackbar(errors.first())
                                        }
                                    }
                                }
                            },
                            enabled = !isRefreshingSubscriptions
                        ) {
                            Icon(Icons.Filled.Sync, contentDescription = stringResource(R.string.profiles_subscriptions_sync_all))
                        }
                    }
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.profiles_add))
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profiles_add_subscription)) },
                                leadingIcon = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
                                onClick = {
                                    showAddMenu = false
                                    addSubscriptionError = null
                                    addSubscriptionSession++
                                    showAddSubscriptionDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profiles_add_clipboard)) },
                                leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
                                onClick = {
                                    showAddMenu = false
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                                    initialImportText = clip
                                    importDialogSession++
                                    showImportProfilesDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profiles_add_qr)) },
                                leadingIcon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                                onClick = {
                                    showAddMenu = false
                                    qrScanLauncher.launch(Intent(context, ScannerActivity::class.java))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profiles_add_toml)) },
                                leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                                onClick = {
                                    showAddMenu = false
                                    importLauncher.launch(
                                        arrayOf(
                                            "application/toml",
                                            "text/x-toml",
                                            "text/plain",
                                            "application/octet-stream",
                                            "*/*"
                                        )
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profiles_add_manual)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    showAddMenu = false
                                    editingId = null
                                    importedDraft = null
                                    importedResolvers = null
                                    showEditor = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (showImportProfilesDialog) {
            key(importDialogSession) {
                ImportProfilesDialog(
                    initialText = initialImportText,
                    onDismiss = {
                        showImportProfilesDialog = false
                        initialImportText = ""
                    },
                    onImport = { entities ->
                        viewModel.addProfiles(entities)
                        showImportProfilesDialog = false
                        initialImportText = ""
                        scope.launch {
                            val msg = if (entities.size == 1) {
                                context.getString(R.string.profiles_link_imported_named_msg, entities.first().name)
                            } else {
                                context.getString(R.string.profiles_imported_count_msg, entities.size)
                            }
                            snackbarHostState.showSnackbar(msg)
                        }
                    },
                    onSwitchToSubscription = { url ->
                        showImportProfilesDialog = false
                        initialImportText = ""
                        prefilledSubscriptionUrl = url
                        addSubscriptionError = null
                        addSubscriptionSession++
                        showAddSubscriptionDialog = true
                    }
                )
            }
        }

        if (showEditor) {
            ProfileEditorDialog(
                profile = editingProfile,
                importedDraft = importedDraft,
                importedResolvers = importedResolvers,
                saveEnabled = editingId == null || editingProfile != null,
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
                    editingId = null
                    importedDraft = null
                    importedResolvers = null
                },
                onDismiss = {
                    showEditor = false
                    editingId = null
                    importedDraft = null
                    importedResolvers = null
                }
            )
        }

        val displayedProfiles = remember(profiles, selectedSubscriptionId) {
            if (selectedSubscriptionId == null) profiles
            else profiles.filter { it.subscriptionId == selectedSubscriptionId }
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (subscriptions.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MdvSpace.S4, vertical = MdvSpace.S2),
                    horizontalArrangement = Arrangement.spacedBy(MdvSpace.S2)
                ) {
                    item {
                        MdvFilterChip(
                            selected = selectedSubscriptionId == null,
                            label = stringResource(R.string.profiles_subscription_filter_all),
                            onClick = { selectedSubscriptionId = null }
                        )
                    }
                    items(subscriptions, key = { it.id }) { sub ->
                        MdvFilterChip(
                            selected = selectedSubscriptionId == sub.id,
                            label = "${sub.name} (${sub.profileCount})",
                            onClick = { selectedSubscriptionId = if (selectedSubscriptionId == sub.id) null else sub.id }
                        )
                    }
                }
            }

            if (displayedProfiles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
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
                            if (selectedSubscriptionId != null) stringResource(R.string.profiles_subscription_empty_title)
                            else stringResource(R.string.profiles_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MdvColor.OnSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (selectedSubscriptionId == null) {
                            FilledTonalButton(onClick = {
                                editingId = null
                                importedDraft = null
                                importedResolvers = null
                                showEditor = true
                            }) {
                                Text(stringResource(R.string.profiles_create))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(MdvSpace.S4),
                    verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)
                ) {
                    items(displayedProfiles, key = { it.id }, contentType = { "profile_card" }) { profile ->
                        ProfileCard(
                            profile = profile,
                            onSelect = { viewModel.selectProfile(profile.id) },
                            onSettings = { onOpenSettings(profile.id) },
                            onEdit = {
                                editingId = profile.id
                                showEditor = true
                            },
                            onDelete = { pendingDeleteId = profile.id },
                            onShare = { sharingProfile = profile }
                        )
                    }
                }
            }
        }
    }

    if (showAddSubscriptionDialog) {
        key(addSubscriptionSession) {
            AddSubscriptionDialog(
                initialUrl = prefilledSubscriptionUrl,
                onDismiss = {
                    showAddSubscriptionDialog = false
                    addSubscriptionError = null
                    prefilledSubscriptionUrl = ""
                },
                onAdd = { name, url ->
                    addSubscriptionError = null
                    viewModel.addSubscription(
                        name = name,
                        url = url,
                        onSuccess = { count ->
                            showAddSubscriptionDialog = false
                            addSubscriptionError = null
                            prefilledSubscriptionUrl = ""
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.profiles_subscription_imported_msg, count)
                                )
                            }
                        },
                        onError = { err ->
                            addSubscriptionError = err
                        }
                    )
                },
                isLoading = isRefreshingSubscriptions,
                externalError = addSubscriptionError
            )
        }
    }

    if (showManageSubscriptionsDialog) {
        ManageSubscriptionsDialog(
            subscriptions = subscriptions,
            onDismiss = { showManageSubscriptionsDialog = false },
            onRefresh = { subId ->
                viewModel.refreshSubscription(subId) { count, err ->
                    scope.launch {
                        val msg = err ?: context.getString(
                            R.string.profiles_subscription_refreshed_msg, count ?: 0
                        )
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            },
            onDelete = { subId ->
                viewModel.deleteSubscription(subId)
            },
            isRefreshing = isRefreshingSubscriptions
        )
    }

    sharingProfile?.let { profile ->
        ShareProfileDialog(
            profile = profile,
            onDismiss = { sharingProfile = null }
        )
    }

    pendingDeleteId?.let { id -> profiles.find { it.id == id } }?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.profiles_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.profiles_delete_confirm_message,
                        profile.name.ifBlank { stringResource(R.string.profiles_dialog_new_title) }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile)
                        pendingDeleteId = null
                    }
                ) {
                    Text(stringResource(R.string.profiles_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun ProfileCard(
    profile: ProfileEntity,
    onSelect: () -> Unit,
    onSettings: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit = {}
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
                val domainsList = remember(profile.domains) { parseDomainsJson(profile.domains) }
                Text(
                    text = domainsList.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MdvColor.OnSurfaceVariant
                )
            }

            IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.profiles_share), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.profiles_edit), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.profiles_settings), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.profiles_delete), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorDialog(
    profile: ProfileEntity?,
    importedDraft: ImportedProfileDraft?,
    importedResolvers: ResolverImportResult?,
    // False while an existing profile (editingId != null) is still loading
    // after process death: saving then would route to addProfile and duplicate
    // the row (editingProfile null + id-0 entity). Re-enabled on load.
    saveEnabled: Boolean,
    onImportToml: () -> Unit,
    onImportResolvers: () -> Unit,
    onSave: (ProfileEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(profile?.name.orEmpty()) }
    val domainList = rememberSaveable(saver = Saver<SnapshotStateList<String>, ArrayList<String>>(save = { ArrayList(it) }, restore = { it.toMutableStateList() })) {
        androidx.compose.runtime.mutableStateListOf<String>().apply {
            val domainsJson = profile?.domains
            if (!domainsJson.isNullOrBlank()) {
                val parsed = parseDomainsJson(domainsJson)
                addAll(parsed)
            }
        }
    }
    var newDomainInput by rememberSaveable { mutableStateOf("") }
    var isDomainInputFocused by remember { mutableStateOf(false) }
    var encryptionKey by rememberSaveable { mutableStateOf(profile?.encryptionKey.orEmpty()) }
    var resolvers by rememberSaveable { mutableStateOf(profile?.resolvers.orEmpty()) }
    var liveStats by remember { mutableStateOf<ResolverImportStats?>(null) }
    var validationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var showResolversEditor by rememberSaveable { mutableStateOf(false) }
    var activeDraft by remember { mutableStateOf(importedDraft) }
    val editorContext = LocalContext.current
    val largeResolversText = resolvers.length > 6000
    val nameRequiredMsg = stringResource(R.string.profiles_name_required_msg)
    val domainRequiredMsg = stringResource(R.string.profiles_domain_required_msg)
    val keyRequiredMsg = stringResource(R.string.profiles_encryption_key_required_msg)
    val resolverRequiredMsg = stringResource(R.string.profiles_resolvers_required_msg)

    // Which profile id the dialog fields were last (re)loaded from. Without
    // this, LaunchedEffect(profile?.id) below runs on every fresh composition
    // (rotation included) and re-copies stored values over restored typing.
    var lastLoadedId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(profile?.id) {
        if (profile != null && lastLoadedId != profile.id) {
            lastLoadedId = profile.id
            name = profile.name
            domainList.clear()
            val parsed = parseDomainsJson(profile.domains)
            domainList.addAll(parsed)
            encryptionKey = profile.encryptionKey
            resolvers = profile.resolvers
            validationMessage = null
            showResolversEditor = false
        }
    }

    LaunchedEffect(resolvers) {
        if (resolvers.isBlank() || largeResolversText) {
            liveStats = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(300)
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            ResolverAnalyzer.analyzeAndNormalize(resolvers, "manual_input")
        }
        liveStats = result?.stats
    }

    LaunchedEffect(importedDraft, importedResolvers) {
        activeDraft = importedDraft
        val importedProfile = importedDraft?.profile
        if (importedProfile != null) {
            if (name.isBlank()) {
                // Keep user-entered profile name if they typed it before import.
                name = importedProfile.name
            }
            domainList.clear()
            domainList.addAll(importedDraft.domainList)
            encryptionKey = importedProfile.encryptionKey
            resolvers = importedProfile.resolvers
        }
        if (importedResolvers != null) {
            resolvers = importedResolvers.normalizedText
        }
        // Only a fresh import clears validation: post-rotation both imports
        // are null (plain remember) and the restored message must survive.
        if (importedDraft != null || importedResolvers != null) {
            validationMessage = null
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
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        validationMessage = null
                    },
                    label = { Text(stringResource(R.string.profiles_name)) },
                    isError = validationMessage == nameRequiredMsg,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth()
                )
                if (profile == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onImportToml,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.UploadFile, contentDescription = null)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.action_import_toml),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onImportResolvers,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Description, contentDescription = null)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.profiles_import_resolvers_short),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    if (domainList.isNotEmpty()) {
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            domainList.forEach { domain ->
                                InputChip(
                                    selected = false,
                                    onClick = { },
                                    label = { Text(domain) },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { domainList.remove(domain) },
                                            modifier = Modifier.size(16.dp)
                                        ) {
                                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_remove))
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            OutlinedTextField(
                                value = newDomainInput,
                                onValueChange = {
                                    newDomainInput = it
                                    validationMessage = null
                                },
                                label = { Text(stringResource(R.string.profiles_domain_hint)) },
                                isError = validationMessage == domainRequiredMsg,
                                placeholder = {
                                    if (isDomainInputFocused) {
                                        Text(stringResource(R.string.profiles_domain_example))
                                    }
                                },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Start),
                                modifier = Modifier
                                    .weight(1f)
                                    .onFocusChanged { focusState ->
                                        isDomainInputFocused = focusState.isFocused
                                    }
                                ,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        FilledIconButton(
                            onClick = {
                                val d = newDomainInput.trim()
                                if (d.isNotEmpty() && !domainList.contains(d)) {
                                    domainList.add(d)
                                    newDomainInput = ""
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = ConnectedGreen)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.profiles_add_domain), tint = MdvColor.Background)
                        }
                    }
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = encryptionKey,
                        onValueChange = {
                            encryptionKey = it
                            validationMessage = null
                        },
                        label = { Text(stringResource(R.string.profiles_encryption_key)) },
                        isError = validationMessage == keyRequiredMsg,
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Start),
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
                }

                // Import Resolvers button for Edit mode (placed above the resolvers field)
                if (profile != null) {
                    OutlinedButton(
                        onClick = onImportResolvers,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.profiles_import_resolvers_short))
                    }
                }

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
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = resolvers,
                            onValueChange = {
                                resolvers = it
                                validationMessage = null
                            },
                            label = { Text(stringResource(R.string.profiles_resolvers_label)) },
                            isError = validationMessage == resolverRequiredMsg,
                            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Start),
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                    }
                }
                liveStats?.let { stats ->
                    ResolverImportStatsCard(stats)
                }
                validationMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                enabled = saveEnabled,
                onClick = {
                    val d = newDomainInput.trim()
                    val finalDomainList = if (d.isNotEmpty() && !domainList.contains(d)) {
                        domainList + d
                    } else {
                        domainList.toList()
                    }
                    validationMessage = when {
                        name.isBlank() -> nameRequiredMsg
                        finalDomainList.isEmpty() -> domainRequiredMsg
                        encryptionKey.isBlank() -> keyRequiredMsg
                        resolvers.isBlank() -> resolverRequiredMsg
                        else -> null
                    }
                    if (validationMessage != null) {
                        return@FilledTonalButton
                    }
                    val baseProfile = profile ?: activeDraft?.profile ?: ProfileEntity(name = "", domains = "")
                    val domainJson = gson.toJson(finalDomainList)
                    val updatedProfile = baseProfile.copy(
                        name = name.trim(),
                        domains = domainJson,
                        encryptionKey = encryptionKey.trim(),
                        resolvers = resolvers.trim()
                    )
                    onSave(
                        if (liveStats != null) {
                            ResolverAnalyzer.withImportStats(updatedProfile, liveStats!!)
                        } else {
                            updatedProfile
                        }
                    )
                }
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
private fun ShareProfileDialog(
    profile: ProfileEntity,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val shareUrl = remember(profile) { runCatching { ProfileUrlCodec.encode(profile) }.getOrNull() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_share_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MdvSpace.S3)
            ) {
                Text(profile.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (shareUrl != null) {
                    val qrBitmap = remember(shareUrl) {
                        runCatching { QrCodeUtil.encodeAsBitmap(shareUrl, sizePx = 800) }.getOrNull()
                    }
                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(bottom = MdvSpace.S3),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.profiles_share_qr_cd),
                                modifier = Modifier
                                    .size(200.dp)
                                    .clip(RoundedCornerShape(MdvRadius.Lg))
                                    .background(MdvColor.QrBackground)
                                    .padding(MdvSpace.S2)
                            )
                        }
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = shareUrl,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            maxLines = 4,
                            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Start)
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.profiles_invalid_link_msg),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            if (shareUrl != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(MdvSpace.S2)) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("MasterDns Profile", shareUrl))
                        Toast.makeText(context, context.getString(R.string.profiles_share_copied), Toast.LENGTH_SHORT).show()
                    }) {
                        Text(stringResource(R.string.btn_copy))
                    }
                    Button(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, profile.name)
                            putExtra(Intent.EXTRA_TEXT, shareUrl)
                        }
                        context.startActivity(Intent.createChooser(intent, context.getString(R.string.profiles_share)))
                    }) {
                        Text(stringResource(R.string.profiles_share))
                    }
                }
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
private fun ImportProfilesDialog(
    initialText: String = "",
    onDismiss: () -> Unit,
    onImport: (List<ProfileEntity>) -> Unit,
    onSwitchToSubscription: (String) -> Unit = {}
) {
    var rawText by rememberSaveable(initialText) { mutableStateOf(initialText) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var detectedSubscriptionUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val context = LocalContext.current

    val dialogQrScanner = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val decodedText = result.data?.getStringExtra(ScannerActivity.EXTRA_SCAN_RESULT)?.trim().orEmpty()
        if (decodedText.isBlank()) {
            errorMessage = context.getString(R.string.profiles_qr_scan_no_code)
        } else {
            rawText = if (rawText.isBlank()) decodedText else "$rawText\n$decodedText"
            errorMessage = null
            detectedSubscriptionUrl = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_import_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MdvSpace.S3)
            ) {
                OutlinedTextField(
                    value = rawText,
                    onValueChange = {
                        rawText = it
                        errorMessage = null
                        detectedSubscriptionUrl = null
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.profiles_import_dialog_hint),
                            modifier = Modifier.fillMaxWidth(),
                            style = LocalTextStyle.current.copy(
                                textDirection = TextDirection.ContentOrRtl,
                                textAlign = if (isRtl) TextAlign.Right else TextAlign.Left
                            )
                        )
                    },
                    trailingIcon = {
                        if (rawText.isNotEmpty()) {
                            IconButton(onClick = {
                                rawText = ""
                                errorMessage = null
                                detectedSubscriptionUrl = null
                            }) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = stringResource(R.string.profiles_clear_input_cd)
                                )
                            }
                        }
                    },
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    maxLines = 8,
                    textStyle = LocalTextStyle.current.copy(
                        textDirection = TextDirection.Ltr,
                        textAlign = TextAlign.Left
                    )
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (detectedSubscriptionUrl != null) {
                        FilledTonalButton(
                            onClick = { onSwitchToSubscription(detectedSubscriptionUrl!!) },
                            modifier = Modifier.fillMaxWidth().padding(top = MdvSpace.S1)
                        ) {
                            Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(MdvSpace.S2))
                            Text(stringResource(R.string.profiles_import_switch_to_subscription_btn))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MdvSpace.S2)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                            if (clip.isNotBlank()) {
                                rawText = if (rawText.isBlank()) clip else "$rawText\n$clip"
                                errorMessage = null
                                detectedSubscriptionUrl = null
                            } else {
                                errorMessage = context.getString(R.string.profiles_clipboard_empty_msg)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(MdvSpace.S1))
                        Text(
                            stringResource(R.string.profiles_add_clipboard),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = { dialogQrScanner.launch(Intent(context, ScannerActivity::class.java)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(MdvSpace.S1))
                        Text(
                            stringResource(R.string.profiles_add_qr),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (rawText.isBlank()) {
                        errorMessage = context.getString(R.string.profiles_invalid_link_msg)
                        detectedSubscriptionUrl = null
                        return@Button
                    }
                    val subUrl = ProfileUrlCodec.findSubscriptionUrl(rawText)
                    if (subUrl != null) {
                        errorMessage = context.getString(R.string.profiles_import_is_subscription_error)
                        detectedSubscriptionUrl = subUrl
                        return@Button
                    }
                    val drafts = runCatching { ProfileUrlCodec.decodeMultiple(rawText) }.getOrElse { error ->
                        errorMessage = error.message ?: context.getString(R.string.profiles_invalid_link_msg)
                        detectedSubscriptionUrl = null
                        return@Button
                    }
                    onImport(drafts.map { it.toProfileEntity() })
                }
            ) {
                Text(stringResource(R.string.btn_import))
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
private fun AddSubscriptionDialog(
    initialUrl: String = "",
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String) -> Unit,
    isLoading: Boolean,
    externalError: String? = null
) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }

    val subQrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra(ScannerActivity.EXTRA_SCAN_RESULT)?.trim().orEmpty()
            if (scanned.isNotBlank()) {
                val detected = ProfileUrlCodec.findSubscriptionUrl(scanned) ?: scanned
                url = detected
                localError = null
            }
        }
    }

    LaunchedEffect(externalError) {
        localError = externalError
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.profiles_subscription_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profiles_subscription_name_label)) },
                    placeholder = { Text(stringResource(R.string.profiles_subscription_name_hint)) },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        localError = null
                    },
                    label = { Text(stringResource(R.string.profiles_subscription_url_label)) },
                    placeholder = { Text(stringResource(R.string.profiles_subscription_url_hint)) },
                    trailingIcon = {
                        IconButton(
                            onClick = { subQrLauncher.launch(Intent(context, ScannerActivity::class.java)) },
                            enabled = !isLoading
                        ) {
                            Icon(
                                Icons.Filled.QrCodeScanner,
                                contentDescription = stringResource(R.string.profiles_subscription_scan_qr)
                            )
                        }
                    },
                    singleLine = true,
                    isError = localError != null,
                    enabled = !isLoading,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth()
                )
                if (localError != null) {
                    Text(
                        text = localError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = MdvSpace.S2))
                }
            }
        },
        confirmButton = {
            val invalidUrlError = stringResource(R.string.profiles_subscription_invalid_url)
            MdvPrimaryActionButton(
                text = stringResource(R.string.btn_import),
                enabled = url.isNotBlank() && !isLoading,
                onClick = {
                    val trimmedUrl = url.trim()
                    if (!trimmedUrl.startsWith("http://", ignoreCase = true) && !trimmedUrl.startsWith("https://", ignoreCase = true)) {
                        localError = invalidUrlError
                    } else {
                        onAdd(name.trim(), trimmedUrl)
                    }
                }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ManageSubscriptionsDialog(
    subscriptions: List<SubscriptionEntity>,
    onDismiss: () -> Unit,
    onRefresh: (subId: Long) -> Unit,
    onDelete: (subId: Long) -> Unit,
    isRefreshing: Boolean
) {
    var pendingDeleteSub by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var sharingSub by remember { mutableStateOf<SubscriptionEntity?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_subscriptions_manage)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)) {
                if (isRefreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (subscriptions.isEmpty()) {
                    Text(stringResource(R.string.profiles_empty_title))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)
                    ) {
                        items(subscriptions, key = { it.id }) { sub ->
                            MdvCardLow(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(MdvSpace.S3),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(sub.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            sub.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MdvColor.OnSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val dateStr = remember(sub.lastUpdated) {
                                            if (sub.lastUpdated > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(sub.lastUpdated))
                                            else "-"
                                        }
                                        Text(
                                            stringResource(R.string.profiles_subscription_last_updated, dateStr),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MdvColor.OnSurfaceVariant
                                        )
                                    }
                                    Row {
                                        IconButton(onClick = { sharingSub = sub }, enabled = !isRefreshing) {
                                            Icon(
                                                Icons.Filled.QrCode2,
                                                contentDescription = stringResource(R.string.profiles_subscription_share_qr)
                                            )
                                        }
                                        IconButton(onClick = { onRefresh(sub.id) }, enabled = !isRefreshing) {
                                            Icon(
                                                Icons.Filled.Sync,
                                                contentDescription = stringResource(R.string.profiles_subscriptions_sync_all)
                                            )
                                        }
                                        IconButton(onClick = { pendingDeleteSub = sub }, enabled = !isRefreshing) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = stringResource(R.string.profiles_delete),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )

    pendingDeleteSub?.let { sub ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSub = null },
            title = { Text(sub.name) },
            text = { Text(stringResource(R.string.profiles_subscription_delete_confirm)) },
            confirmButton = {
                MdvPrimaryActionButton(
                    text = stringResource(R.string.profiles_delete),
                    onClick = {
                        onDelete(sub.id)
                        pendingDeleteSub = null
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSub = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (sharingSub != null) {
        SubscriptionQrDialog(
            subscription = sharingSub!!,
            onDismiss = { sharingSub = null }
        )
    }
}

@Composable
private fun SubscriptionQrDialog(
    subscription: SubscriptionEntity,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val qrBitmap = remember(subscription.url) {
        runCatching { QrCodeUtil.encodeAsBitmap(subscription.url, sizePx = 800) }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(subscription.name) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MdvSpace.S3),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.profiles_subscription_share_qr),
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(MdvRadius.Lg))
                            .background(MdvColor.QrBackground)
                            .padding(MdvSpace.S2)
                    )
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = subscription.url,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        maxLines = 4,
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Start)
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(MdvSpace.S2)) {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(subscription.name, subscription.url))
                    Toast.makeText(context, context.getString(R.string.profiles_share_copied), Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.btn_copy))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    )
}

@Composable
private fun ResolverImportStatsCard(stats: ResolverImportStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceHigh)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.profiles_resolvers_import_preview_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(
                    R.string.profiles_resolvers_import_preview_body,
                    stats.uniqueResolvers,
                    stats.duplicateResolvers,
                    stats.invalidLines,
                    stats.cidrRanges
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MdvColor.OnSurfaceVariant
            )
            if (stats.truncated || stats.skippedCidrRanges > 0) {
                Text(
                    stringResource(
                        R.string.profiles_resolvers_import_preview_limits,
                        stats.skippedCidrRanges
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private val gson = Gson()

private fun parseProfileTomlForImport(fileName: String, tomlContent: String): ImportedProfileDraft? {
    val values = mutableMapOf<String, String>()
    var parsedDomainsList = emptyList<String>()
    tomlContent.lineSequence().forEach { raw ->
        val line = raw.substringBefore("#").trim()
        if (line.isEmpty() || "=" !in line) return@forEach
        val key = line.substringBefore("=").trim()
        val valueRaw = line.substringAfter("=").trim()
        if (key == "DOMAINS") {
            parsedDomainsList = valueRaw
                .removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }
            return@forEach
        }
        val parsed = when {
            valueRaw.startsWith("\"") && valueRaw.endsWith("\"") ->
                valueRaw.removeSurrounding("\"")
            else -> valueRaw
        }
        values[key] = parsed
    }

    val parsedDomain = if (parsedDomainsList.isNotEmpty()) parsedDomainsList else return null
    val parsedKey = values["ENCRYPTION_KEY"]?.takeIf { it.isNotBlank() } ?: return null

    val advanced = mutableMapOf<String, String>()
    IMPORT_ADVANCED_KEYS.forEach { key ->
        values[key]?.let { advanced[key] = it.trim() }
    }

    val importedProfile = ProfileEntity(
        name = fileName,
        domains = gson.toJson(parsedDomain),
        encryptionMethod = values["DATA_ENCRYPTION_METHOD"]?.toIntOrNull() ?: 1,
        encryptionKey = parsedKey,
        protocolType = normalizeProtocol(values["PROTOCOL_TYPE"]),
        listenPort = values["LISTEN_PORT"]?.toIntOrNull()?.coerceIn(1025, 65535) ?: 18000,
        resolverBalancingStrategy = values["RESOLVER_BALANCING_STRATEGY"]?.toIntOrNull() ?: 2,
        packetDuplicationCount = values["PACKET_DUPLICATION_COUNT"]?.toIntOrNull() ?: 2,
        setupPacketDuplicationCount = values["SETUP_PACKET_DUPLICATION_COUNT"]?.toIntOrNull() ?: 2,
        uploadCompression = values["UPLOAD_COMPRESSION_TYPE"]?.toIntOrNull() ?: 0,
        downloadCompression = values["DOWNLOAD_COMPRESSION_TYPE"]?.toIntOrNull() ?: 0,
        logLevel = values["LOG_LEVEL"]?.trim().takeUnless { it.isNullOrBlank() } ?: "INFO",
        resolvers = "",
        advancedJson = gson.toJson(advanced)
    )

    return ImportedProfileDraft(
        profile = importedProfile,
        domainList = parsedDomain
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
