package com.masterdns.vpn.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.masterdns.vpn.R
import com.masterdns.vpn.ui.components.mdv.cards.MdvCardHigh
import com.masterdns.vpn.ui.components.mdv.cards.MdvCardLow
import com.masterdns.vpn.ui.theme.ConnectedGreen
import com.masterdns.vpn.ui.theme.DisconnectedRed
import com.masterdns.vpn.ui.theme.MdvColor
import com.masterdns.vpn.ui.theme.MdvSpace
import com.masterdns.vpn.util.VpnManager

@Composable
fun MdvConnectionTelemetryCard(
    vpnState: VpnManager.VpnState,
    scanStatus: VpnManager.ScanStatus,
    scannedCount: Int,
    totalResolvers: Int,
    scanProgress: Float,
    downBps: Long,
    upBps: Long,
    downloadTotalBytes: Long,
    uploadTotalBytes: Long,
    connectedDurationSeconds: Long,
    proxyHost: String,
    proxyPort: Int,
    socksAuthEnabled: Boolean,
    socksUser: String,
    socksPass: String,
    isConnecting: Boolean
) {
    MdvCardLow(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MdvSpace.S4),
            verticalArrangement = Arrangement.spacedBy(MdvSpace.S3)
        ) {
            // Header Row: Status and Duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.home_connection_status_title).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MdvColor.PrimaryDim,
                        fontWeight = FontWeight.Bold
                    )
                    val statusText = when (vpnState) {
                        VpnManager.VpnState.CONNECTED -> stringResource(R.string.home_connection_running)
                        VpnManager.VpnState.CONNECTING -> stringResource(R.string.home_connection_preparing)
                        VpnManager.VpnState.DISCONNECTING -> stringResource(R.string.home_state_disconnecting)
                        VpnManager.VpnState.ERROR -> stringResource(R.string.home_connection_error_check_logs)
                        else -> stringResource(R.string.home_state_disconnected)
                    }
                    val statusColor = when (vpnState) {
                        VpnManager.VpnState.CONNECTED -> ConnectedGreen
                        VpnManager.VpnState.ERROR -> DisconnectedRed
                        else -> MdvColor.OnSurface
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (connectedDurationSeconds > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "SESSION",
                            style = MaterialTheme.typography.labelSmall,
                            color = MdvColor.OnSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatDuration(connectedDurationSeconds),
                            style = MaterialTheme.typography.titleMedium,
                            color = MdvColor.OnSurface,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Scanning Progress Section
            if (scanStatus.scanning || isConnecting) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MdvColor.SurfaceHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(MdvSpace.S3),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "DNS Scan",
                                style = MaterialTheme.typography.labelMedium,
                                color = MdvColor.OnSurfaceVariant
                            )
                            Text(
                                text = "$scannedCount / $totalResolvers",
                                style = MaterialTheme.typography.labelMedium,
                                color = MdvColor.PrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { scanProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MdvColor.PrimaryContainer,
                            trackColor = MdvColor.SurfaceBright,
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                        if (scanStatus.validCount > 0 || scanStatus.rejectedCount > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = buildAnnotatedString {
                                        append("Valid: ")
                                        pushStyle(SpanStyle(color = ConnectedGreen, fontWeight = FontWeight.Bold))
                                        append(scanStatus.validCount.toString())
                                        pop()
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = buildAnnotatedString {
                                        append("Rejected: ")
                                        pushStyle(SpanStyle(color = DisconnectedRed, fontWeight = FontWeight.Bold))
                                        append(scanStatus.rejectedCount.toString())
                                        pop()
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (scanStatus.lastResolver.isNotBlank()) {
                            Text(
                                text = "${scanStatus.lastResolver} • ${scanStatus.lastDecision}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MdvColor.OnSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Network Traffic Grid
            if (downloadTotalBytes > 0 || uploadTotalBytes > 0 || downBps > 0 || upBps > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MdvSpace.S3)
                ) {
                    // Download Column
                    androidx.compose.material3.Surface(
                        modifier = Modifier.weight(1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = MdvColor.SurfaceHigh
                    ) {
                        Column(
                            modifier = Modifier.padding(MdvSpace.S3),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "↓ DOWNLOAD",
                                style = MaterialTheme.typography.labelSmall,
                                color = ConnectedGreen,
                                fontWeight = FontWeight.Bold
                            )
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formatSpeed(downBps),
                                style = MaterialTheme.typography.titleMedium,
                                color = MdvColor.OnSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = formatBytes(downloadTotalBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurfaceVariant
                            )
                        }
                    }

                    // Upload Column
                    androidx.compose.material3.Surface(
                        modifier = Modifier.weight(1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = MdvColor.SurfaceHigh
                    ) {
                        Column(
                            modifier = Modifier.padding(MdvSpace.S3),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "↑ UPLOAD",
                                style = MaterialTheme.typography.labelSmall,
                                color = MdvColor.PrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formatSpeed(upBps),
                                style = MaterialTheme.typography.titleMedium,
                                color = MdvColor.OnSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = formatBytes(uploadTotalBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Extra Info (MTU, Active Resolvers, SOCKS)
            if (vpnState == VpnManager.VpnState.CONNECTED) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (scanStatus.activeResolvers > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Active Resolvers", style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurfaceVariant)
                            Text(scanStatus.activeResolvers.toString(), style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (scanStatus.syncedUploadMtu > 0 || scanStatus.syncedDownloadMtu > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Synced MTU (Up/Down)", style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurfaceVariant)
                            Text("${scanStatus.syncedUploadMtu} / ${scanStatus.syncedDownloadMtu}", style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("SOCKS5 Proxy", style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurfaceVariant)
                        Text("$proxyHost:$proxyPort", style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurface, fontWeight = FontWeight.Bold)
                    }
                    if (socksAuthEnabled && socksUser.isNotBlank()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("SOCKS5 Auth", style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurfaceVariant)
                            Text("Enabled ($socksUser)", style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MdvProfileSelectorCard(
    profileName: String,
    onNavigateToProfiles: () -> Unit
) {
    MdvCardHigh(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onNavigateToProfiles)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MdvSpace.S4),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.home_profile_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MdvColor.OnSurfaceVariant
                )
                Text(
                    text = profileName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MdvColor.OnSurface
                )
            }
            Text(
                text = "→",
                style = MaterialTheme.typography.titleLarge,
                color = MdvColor.PrimaryContainer
            )
        }
    }
}

@Composable
fun MdvErrorCard(msg: String) {
    MdvCardLow(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = msg,
            style = MaterialTheme.typography.bodyMedium,
            color = DisconnectedRed,
            modifier = Modifier.padding(MdvSpace.S3),
            textAlign = TextAlign.Center
        )
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

private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.2f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun formatDuration(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    val hours = safeSeconds / 3600L
    val minutes = (safeSeconds % 3600L) / 60L
    val secs = safeSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}
