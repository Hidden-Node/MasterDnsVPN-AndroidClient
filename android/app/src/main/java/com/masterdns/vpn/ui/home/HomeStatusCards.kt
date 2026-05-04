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
    proxyHost: String,
    proxyPort: Int,
    socksAuthEnabled: Boolean,
    socksUser: String,
    socksPass: String,
    isConnecting: Boolean
) {
    MdvCardLow(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(MdvSpace.S3)) {
            Text(
                text = stringResource(R.string.home_connection_status_title),
                style = MaterialTheme.typography.labelSmall,
                color = MdvColor.PrimaryDim
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(MdvSpace.S1))
            Text(
                text = when (vpnState) {
                    VpnManager.VpnState.CONNECTED -> stringResource(R.string.home_connection_running)
                    VpnManager.VpnState.CONNECTING -> stringResource(R.string.home_connection_preparing)
                    VpnManager.VpnState.DISCONNECTING -> stringResource(R.string.home_state_disconnecting)
                    VpnManager.VpnState.ERROR -> stringResource(R.string.home_connection_error_check_logs)
                    else -> stringResource(R.string.home_state_disconnected)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MdvColor.OnSurface
            )

            if (scanStatus.lastResolver.isNotBlank()) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(MdvSpace.S1))
                Text(
                    text = stringResource(
                        R.string.home_resolver_row,
                        scanStatus.lastResolver,
                        scanStatus.lastDecision
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MdvColor.OnSurfaceVariant
                )
            }
            if (scanStatus.validCount > 0 || scanStatus.rejectedCount > 0) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildAnnotatedString {
                        append(stringResource(R.string.home_valid_prefix))
                        pushStyle(SpanStyle(color = ConnectedGreen, fontWeight = FontWeight.Bold))
                        append(scanStatus.validCount.toString())
                        pop()
                        append(stringResource(R.string.home_rejected_prefix))
                        pushStyle(SpanStyle(color = DisconnectedRed, fontWeight = FontWeight.Bold))
                        append(scanStatus.rejectedCount.toString())
                        pop()
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (scanStatus.scanning || isConnecting) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(MdvSpace.S2))
                Text(
                    text = stringResource(R.string.home_dns_scan_progress, scannedCount, totalResolvers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MdvColor.OnSurfaceVariant
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(MdvSpace.S1))
                LinearProgressIndicator(
                    progress = { scanProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MdvColor.PrimaryContainer,
                    trackColor = MdvColor.SurfaceBright
                )
            }
            if (scanStatus.syncedUploadMtu > 0 || scanStatus.syncedDownloadMtu > 0) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.home_synced_mtu,
                        scanStatus.syncedUploadMtu,
                        scanStatus.syncedDownloadMtu
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MdvColor.OnSurfaceVariant
                )
            }
            if (scanStatus.activeResolvers > 0) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.home_active_resolvers, scanStatus.activeResolvers),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MdvColor.OnSurface
                )
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(MdvSpace.S1))
            Text(
                text = stringResource(R.string.home_speed_row, formatSpeed(downBps), formatSpeed(upBps)),
                style = MaterialTheme.typography.bodySmall,
                color = MdvColor.OnSurfaceVariant
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(MdvSpace.S2))
            Text(
                text = stringResource(R.string.home_socks_address, proxyHost, proxyPort),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MdvColor.OnSurface
            )
            if (socksAuthEnabled) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(MdvSpace.S1))
                Text(
                    text = stringResource(R.string.home_socks_auth_title),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MdvColor.OnSurface
                )
                if (socksUser.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.home_socks_username, socksUser),
                        style = MaterialTheme.typography.bodySmall,
                        color = MdvColor.OnSurfaceVariant
                    )
                }
                if (socksPass.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.home_socks_password, socksPass),
                        style = MaterialTheme.typography.bodySmall,
                        color = MdvColor.OnSurfaceVariant
                    )
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
