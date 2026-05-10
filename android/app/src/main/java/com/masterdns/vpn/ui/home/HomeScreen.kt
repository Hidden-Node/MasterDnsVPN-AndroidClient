package com.masterdns.vpn.ui.home

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.masterdns.vpn.R
import com.masterdns.vpn.ui.theme.ConnectedGreen
import com.masterdns.vpn.ui.theme.ConnectingAmber
import com.masterdns.vpn.ui.theme.DisconnectedRed
import com.masterdns.vpn.ui.theme.MdvColor
import com.masterdns.vpn.ui.theme.MdvSpace
import com.masterdns.vpn.util.ResolverAnalyzer
import com.masterdns.vpn.util.VpnManager
import kotlinx.coroutines.delay

private data class HomeLayoutMetrics(
    val horizontalPadding: androidx.compose.ui.unit.Dp,
    val verticalPadding: androidx.compose.ui.unit.Dp,
    val isWide: Boolean
)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToProfiles: () -> Unit,
    onOpenInfo: () -> Unit
) {
    val vpnState by VpnManager.state.collectAsState()
    val upBps by VpnManager.uploadSpeedBps.collectAsState()
    val downBps by VpnManager.downloadSpeedBps.collectAsState()
    val upTotalBytes by VpnManager.uploadTotalBytes.collectAsState()
    val downTotalBytes by VpnManager.downloadTotalBytes.collectAsState()
    val connectedSinceMs by VpnManager.connectedSinceMs.collectAsState()
    val scanStatus by VpnManager.scanStatus.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val error by VpnManager.errorMessage.collectAsState()
    val context = LocalContext.current

    val advanced = remember(selectedProfile?.advancedJson) {
        parseAdvanced(selectedProfile?.advancedJson.orEmpty())
    }
    val proxyHost = advanced["LISTEN_IP"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "127.0.0.1"
    val proxyPort = selectedProfile?.listenPort ?: 18000
    val socksAuthEnabled = advanced["SOCKS5_AUTH"].equals("true", ignoreCase = true)
    val socksUser = advanced["SOCKS5_USER"]?.trim().orEmpty()
    val socksPass = advanced["SOCKS5_PASS"]?.trim().orEmpty()
    val preConnectWarnings = remember(selectedProfile?.id, selectedProfile?.resolvers, selectedProfile?.advancedJson) {
        buildPreConnectWarnings(context, selectedProfile, advanced)
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedProfile?.let { profile ->
                VpnManager.connect(context, profile)
            }
        }
    }

    val isConnected = vpnState == VpnManager.VpnState.CONNECTED
    val isConnecting = vpnState == VpnManager.VpnState.CONNECTING
    val isDisconnecting = vpnState == VpnManager.VpnState.DISCONNECTING
    val totalResolvers = scanStatus.scanTotalFromCore
    val scannedCount = scanStatus.validCount + scanStatus.rejectedCount
    val scanProgress = if (totalResolvers > 0) {
        (scannedCount.toFloat() / totalResolvers.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val scanEtaText = estimateScanEta(
        scannedCount = scannedCount,
        totalResolvers = totalResolvers,
        startedAtMs = scanStatus.scanStartedAtMs,
        updatedAtMs = scanStatus.scanUpdatedAtMs
    )
    val nowMs by produceState(System.currentTimeMillis(), vpnState, connectedSinceMs) {
        while (vpnState == VpnManager.VpnState.CONNECTED && connectedSinceMs > 0L) {
            value = System.currentTimeMillis()
            delay(1000L)
        }
    }
    val connectionDurationText = remember(vpnState, connectedSinceMs, nowMs) {
        if (vpnState == VpnManager.VpnState.CONNECTED && connectedSinceMs > 0L) {
            formatDuration(((nowMs - connectedSinceMs) / 1000L).coerceAtLeast(0L))
        } else {
            ""
        }
    }

    val statusColor by animateColorAsState(
        targetValue = when (vpnState) {
            VpnManager.VpnState.CONNECTED -> ConnectedGreen
            VpnManager.VpnState.CONNECTING,
            VpnManager.VpnState.DISCONNECTING -> ConnectingAmber
            VpnManager.VpnState.ERROR -> DisconnectedRed
            else -> Color.Gray
        },
        animationSpec = tween(500),
        label = "statusColor"
    )

    // Avoid running an infinite animation loop while idle/connected.
    val pulseScale = if (isConnecting || isDisconnecting) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val animated by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )
        animated
    } else {
        1f
    }

    val statusText = when (vpnState) {
        VpnManager.VpnState.CONNECTED -> stringResource(R.string.home_state_connected)
        VpnManager.VpnState.CONNECTING -> stringResource(R.string.home_state_connecting)
        VpnManager.VpnState.DISCONNECTING -> stringResource(R.string.home_state_disconnecting)
        VpnManager.VpnState.ERROR -> stringResource(R.string.home_state_error)
        else -> stringResource(R.string.home_state_disconnected)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MdvColor.Background)
            .statusBarsPadding()
    ) {
        val metrics = when {
            maxWidth >= 840.dp -> HomeLayoutMetrics(MdvSpace.S7, MdvSpace.S6, true)
            maxWidth >= 600.dp -> HomeLayoutMetrics(MdvSpace.S6, MdvSpace.S5, false)
            else -> HomeLayoutMetrics(MdvSpace.S4, MdvSpace.S6, false)
        }

        val toggleVpn: () -> Unit = {
            when (vpnState) {
                VpnManager.VpnState.CONNECTED -> VpnManager.disconnect(context)
                VpnManager.VpnState.CONNECTING,
                VpnManager.VpnState.DISCONNECTING -> VpnManager.disconnect(context)
                VpnManager.VpnState.DISCONNECTED, VpnManager.VpnState.ERROR -> {
                    val profile = selectedProfile
                    if (profile == null) {
                        onNavigateToProfiles()
                    } else {
                        val vpnIntent = VpnService.prepare(context)
                        if (vpnIntent != null) {
                            vpnPermissionLauncher.launch(vpnIntent)
                        } else {
                            VpnManager.connect(context, profile)
                        }
                    }
                }
            }
        }

        if (metrics.isWide) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = metrics.horizontalPadding, vertical = metrics.verticalPadding)
            ) {
                MdvHomeHeader(onOpenInfo = onOpenInfo)
                Spacer(modifier = Modifier.height(MdvSpace.S4))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.home_network_status),
                            style = MaterialTheme.typography.labelSmall,
                            color = MdvColor.OnSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(MdvSpace.S2))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = statusColor
                        )
                        Text(
                            text = selectedProfile?.name ?: stringResource(R.string.home_no_profile_selected),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MdvColor.OnSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(MdvSpace.S6))
                        MdvConnectionNodeButton(
                            isConnected = isConnected,
                            shouldPulse = isConnecting || isDisconnecting,
                            pulseScale = pulseScale,
                            statusColor = statusColor,
                            onToggle = toggleVpn
                        )
                    }

                    Spacer(modifier = Modifier.width(MdvSpace.S6))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 560.dp)
                    ) {
                        MdvConnectionTelemetryCard(
                            vpnState = vpnState,
                            scanStatus = scanStatus,
                            scannedCount = scannedCount,
                            totalResolvers = totalResolvers,
                            scanProgress = scanProgress,
                            scanEtaText = scanEtaText,
                            downBps = downBps,
                            upBps = upBps,
                            downTotalBytes = downTotalBytes,
                            upTotalBytes = upTotalBytes,
                            connectionDurationText = connectionDurationText,
                            proxyHost = proxyHost,
                            proxyPort = proxyPort,
                            socksAuthEnabled = socksAuthEnabled,
                            socksUser = socksUser,
                            socksPass = socksPass,
                            isConnecting = isConnecting
                        )
                        Spacer(modifier = Modifier.height(MdvSpace.S3))
                        MdvProfileSelectorCard(
                            profileName = selectedProfile?.name ?: stringResource(R.string.profiles_create),
                            onNavigateToProfiles = onNavigateToProfiles
                        )
                        if (!isConnected && !isConnecting && preConnectWarnings.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(MdvSpace.S3))
                            MdvPreConnectChecklist(warnings = preConnectWarnings)
                        }
                        error?.let { msg ->
                            Spacer(modifier = Modifier.height(MdvSpace.S4))
                            MdvErrorCard(msg = msg)
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = metrics.horizontalPadding, vertical = metrics.verticalPadding)
                    .widthIn(max = 640.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MdvHomeHeader(onOpenInfo = onOpenInfo)
                Spacer(modifier = Modifier.height(MdvSpace.S4))
                Text(
                    text = stringResource(R.string.home_network_status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MdvColor.OnSurfaceVariant
                )
                Spacer(modifier = Modifier.height(MdvSpace.S2))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = statusColor
                )
                Text(
                    text = selectedProfile?.name ?: stringResource(R.string.home_no_profile_selected),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MdvColor.OnSurfaceVariant
                )
                Spacer(modifier = Modifier.height(MdvSpace.S6))

                MdvConnectionNodeButton(
                    isConnected = isConnected,
                    shouldPulse = isConnecting || isDisconnecting,
                    pulseScale = pulseScale,
                    statusColor = statusColor,
                    onToggle = toggleVpn
                )

                Spacer(modifier = Modifier.height(MdvSpace.S6))

                MdvConnectionTelemetryCard(
                    vpnState = vpnState,
                    scanStatus = scanStatus,
                    scannedCount = scannedCount,
                    totalResolvers = totalResolvers,
                    scanProgress = scanProgress,
                    scanEtaText = scanEtaText,
                    downBps = downBps,
                    upBps = upBps,
                    downTotalBytes = downTotalBytes,
                    upTotalBytes = upTotalBytes,
                    connectionDurationText = connectionDurationText,
                    proxyHost = proxyHost,
                    proxyPort = proxyPort,
                    socksAuthEnabled = socksAuthEnabled,
                    socksUser = socksUser,
                    socksPass = socksPass,
                    isConnecting = isConnecting
                )

                Spacer(modifier = Modifier.height(MdvSpace.S3))

                MdvProfileSelectorCard(
                    profileName = selectedProfile?.name ?: stringResource(R.string.profiles_create),
                    onNavigateToProfiles = onNavigateToProfiles
                )

                if (!isConnected && !isConnecting && preConnectWarnings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(MdvSpace.S3))
                    MdvPreConnectChecklist(warnings = preConnectWarnings)
                }

                error?.let { msg ->
                    Spacer(modifier = Modifier.height(MdvSpace.S4))
                    MdvErrorCard(msg = msg)
                }
            }
        }
    }
}

private fun buildPreConnectWarnings(
    context: android.content.Context,
    profile: com.masterdns.vpn.data.local.ProfileEntity?,
    advanced: Map<String, String>
): List<String> {
    if (profile == null) return listOf(context.getString(R.string.home_preconnect_no_profile))
    val warnings = mutableListOf<String>()
    val imported = ResolverAnalyzer.profileImportedResolver(profile)
    if (imported != null) {
        if (!java.io.File(imported.cachedPath).isFile) {
            warnings += context.getString(R.string.home_preconnect_resolver_file_missing)
        } else if (imported.stats.uniqueUsableIps <= 0) {
            warnings += context.getString(R.string.home_preconnect_resolver_file_empty)
        }
    } else if (profile.resolvers.lineSequence().none { it.trim().isNotEmpty() }) {
        warnings += context.getString(R.string.home_preconnect_no_inline_resolvers)
    }
    val localDnsEnabled = advanced["LOCAL_DNS_ENABLED"].equals("true", ignoreCase = true)
    val localDnsPort = advanced["LOCAL_DNS_PORT"]?.toIntOrNull() ?: 5353
    if (localDnsEnabled && localDnsPort <= 1024) {
        warnings += context.getString(R.string.home_preconnect_dns_port_remap, localDnsPort)
    }
    if (advanced["SAVE_MTU_SERVERS_TO_FILE"].equals("true", ignoreCase = true) &&
        advanced["MTU_EXPORT_URI"].isNullOrBlank()
    ) {
        warnings += context.getString(R.string.home_preconnect_mtu_export_missing)
    }
    return warnings
}

private fun estimateScanEta(
    scannedCount: Int,
    totalResolvers: Int,
    startedAtMs: Long,
    updatedAtMs: Long
): String {
    if (scannedCount < 3 || totalResolvers <= scannedCount || startedAtMs <= 0L || updatedAtMs <= startedAtMs) {
        return ""
    }
    val elapsedMs = (updatedAtMs - startedAtMs).coerceAtLeast(1L)
    val remaining = totalResolvers - scannedCount
    val etaSeconds = ((elapsedMs / scannedCount.toDouble()) * remaining / 1000.0).toLong().coerceAtLeast(1L)
    return formatDuration(etaSeconds)
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
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
