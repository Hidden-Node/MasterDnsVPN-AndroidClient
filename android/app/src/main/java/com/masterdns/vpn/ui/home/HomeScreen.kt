package com.masterdns.vpn.ui.home

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.masterdns.vpn.ui.theme.*
import com.masterdns.vpn.util.VpnManager

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToProfiles: () -> Unit
) {
    val vpnState by VpnManager.state.collectAsState()
    val upBps by VpnManager.uploadSpeedBps.collectAsState()
    val downBps by VpnManager.downloadSpeedBps.collectAsState()
    val scanStatus by VpnManager.scanStatus.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val context = LocalContext.current

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
    val totalResolvers = selectedProfile?.resolvers
        ?.lineSequence()
        ?.map { it.trim() }
        ?.count { it.isNotEmpty() }
        ?.coerceAtLeast(1)
        ?: 1
    val scannedCount = (scanStatus.validCount + scanStatus.rejectedCount).coerceAtMost(totalResolvers)
    val scanProgress by animateFloatAsState(
        targetValue = (scannedCount.toFloat() / totalResolvers.toFloat()).coerceIn(0f, 1f),
        animationSpec = tween(350),
        label = "scanProgress"
    )

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

    // Pulse animation for connecting state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Status text
        Text(
            text = when (vpnState) {
                VpnManager.VpnState.CONNECTED -> "Connected"
                VpnManager.VpnState.CONNECTING -> "Connecting..."
                VpnManager.VpnState.DISCONNECTING -> "Disconnecting..."
                VpnManager.VpnState.ERROR -> "Error"
                else -> "Disconnected"
            },
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            ),
            color = statusColor,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Selected profile name
        Text(
            text = selectedProfile?.name ?: "No profile selected",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 48.dp)
        )

        // Power button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(160.dp)
                .scale(if (isConnecting || isDisconnecting) pulseScale else 1f)
        ) {
            // Outer glow ring
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .shadow(
                        elevation = if (isConnected) 24.dp else 8.dp,
                        shape = CircleShape,
                        ambientColor = statusColor.copy(alpha = 0.3f),
                        spotColor = statusColor.copy(alpha = 0.5f)
                    )
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                statusColor.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )

            // Button
            FilledIconButton(
                onClick = {
                    when (vpnState) {
                        VpnManager.VpnState.CONNECTED -> {
                            VpnManager.disconnect(context)
                        }
                        VpnManager.VpnState.CONNECTING,
                        VpnManager.VpnState.DISCONNECTING -> {
                            VpnManager.disconnect(context)
                        }
                        VpnManager.VpnState.DISCONNECTED, VpnManager.VpnState.ERROR -> {
                            val profile = selectedProfile
                            if (profile == null) {
                                onNavigateToProfiles()
                                return@FilledIconButton
                            }
                            val vpnIntent = VpnService.prepare(context)
                            if (vpnIntent != null) {
                                vpnPermissionLauncher.launch(vpnIntent)
                            } else {
                                VpnManager.connect(context, profile)
                            }
                        }
                    }
                },
                modifier = Modifier.size(120.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isConnected) ConnectedGreen else MaterialTheme.colorScheme.primary
                ),
                enabled = true
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = if (isConnected) "Disconnect" else "Connect",
                    modifier = Modifier.size(56.dp),
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Profile selector card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    text = "Connection Status",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when (vpnState) {
                        VpnManager.VpnState.CONNECTED -> "Connected and running"
                        VpnManager.VpnState.CONNECTING -> "Preparing tunnel (tap again to cancel)"
                        VpnManager.VpnState.DISCONNECTING -> "Disconnecting..."
                        VpnManager.VpnState.ERROR -> "Error - check logs"
                        else -> "Disconnected"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (scanStatus.lastResolver.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Resolver: ${scanStatus.lastResolver}  ${scanStatus.lastDecision}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (scanStatus.validCount > 0 || scanStatus.rejectedCount > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = buildAnnotatedString {
                            append("Valid: ")
                            pushStyle(SpanStyle(color = ConnectedGreen, fontWeight = FontWeight.Bold))
                            append(scanStatus.validCount.toString())
                            pop()
                            append("   Rejected: ")
                            pushStyle(SpanStyle(color = DisconnectedRed, fontWeight = FontWeight.Bold))
                            append(scanStatus.rejectedCount.toString())
                            pop()
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (scanStatus.scanning || isConnecting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "DNS Scan Progress: $scannedCount / $totalResolvers",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { scanProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.22f)
                    )
                }
                if (scanStatus.syncedUploadMtu > 0 || scanStatus.syncedDownloadMtu > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Synced MTU: UP ${scanStatus.syncedUploadMtu} / DOWN ${scanStatus.syncedDownloadMtu}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Download: ${formatSpeed(downBps)}   Upload: ${formatSpeed(upBps)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Profile selector card
        Card(
            onClick = onNavigateToProfiles,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Profile",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = selectedProfile?.name ?: "Tap to create a profile",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "→",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Error message
        val error by VpnManager.errorMessage.collectAsState()
        error?.let { msg ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DisconnectedRed.copy(alpha = 0.15f))
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DisconnectedRed,
                    modifier = Modifier.padding(12.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun formatSpeed(bps: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        bps >= mb -> String.format("%.2f MB/s", bps / mb)
        bps >= kb -> String.format("%.1f KB/s", bps / kb)
        else -> "${bps} B/s"
    }
}
