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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
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
import com.masterdns.vpn.util.VpnManager

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
    val uploadTotalBytes by VpnManager.uploadTotalBytes.collectAsState()
    val downloadTotalBytes by VpnManager.downloadTotalBytes.collectAsState()
    val connectedDurationSeconds by VpnManager.connectedDurationSeconds.collectAsState()
    val scanStatus by VpnManager.scanStatus.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val error by VpnManager.errorMessage.collectAsState()
    val connectionWarning by VpnManager.connectionWarning.collectAsState()
    val context = LocalContext.current

    val advanced = remember(selectedProfile?.advancedJson) {
        parseAdvanced(selectedProfile?.advancedJson.orEmpty())
    }
    val proxyHost = advanced["LISTEN_IP"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "127.0.0.1"
    val proxyPort = selectedProfile?.listenPort ?: 18000
    val socksAuthEnabled = advanced["SOCKS5_AUTH"].equals("true", ignoreCase = true)
    val socksUser = advanced["SOCKS5_USER"]?.trim().orEmpty()
    val socksPass = advanced["SOCKS5_PASS"]?.trim().orEmpty()

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
    var showHowToUse by remember { mutableStateOf(false) }

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
                            downBps = downBps,
                            upBps = upBps,
                            downloadTotalBytes = downloadTotalBytes,
                            uploadTotalBytes = uploadTotalBytes,
                            connectedDurationSeconds = connectedDurationSeconds,
                            proxyHost = proxyHost,
                            proxyPort = proxyPort,
                            socksAuthEnabled = socksAuthEnabled,
                            socksUser = socksUser,
                            socksPass = socksPass,
                            isConnecting = isConnecting,
                            connectionWarning = connectionWarning
                        )
                        Spacer(modifier = Modifier.height(MdvSpace.S3))
                        MdvProfileSelectorCard(
                            profileName = selectedProfile?.name ?: stringResource(R.string.profiles_create),
                            onNavigateToProfiles = onNavigateToProfiles
                        )
                        error?.let { msg ->
                            Spacer(modifier = Modifier.height(MdvSpace.S4))
                            MdvErrorCard(msg = msg)
                        }
                        Spacer(modifier = Modifier.height(MdvSpace.S3))
                        HowToUseCard(onOpen = { showHowToUse = true })
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
                    downBps = downBps,
                    upBps = upBps,
                    downloadTotalBytes = downloadTotalBytes,
                    uploadTotalBytes = uploadTotalBytes,
                    connectedDurationSeconds = connectedDurationSeconds,
                    proxyHost = proxyHost,
                    proxyPort = proxyPort,
                    socksAuthEnabled = socksAuthEnabled,
                    socksUser = socksUser,
                    socksPass = socksPass,
                    isConnecting = isConnecting,
                    connectionWarning = connectionWarning
                )

                Spacer(modifier = Modifier.height(MdvSpace.S3))

                MdvProfileSelectorCard(
                    profileName = selectedProfile?.name ?: stringResource(R.string.profiles_create),
                    onNavigateToProfiles = onNavigateToProfiles
                )

                error?.let { msg ->
                    Spacer(modifier = Modifier.height(MdvSpace.S4))
                    MdvErrorCard(msg = msg)
                }
                Spacer(modifier = Modifier.height(MdvSpace.S3))
                HowToUseCard(onOpen = { showHowToUse = true })
            }
        }
    }

    if (showHowToUse) {
        HowToUseDialog(onDismiss = { showHowToUse = false })
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

private enum class HowToLang { EN, FA }

@Composable
private fun HowToUseCard(onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(modifier = Modifier.fillMaxWidth().padding(MdvSpace.S3)) {
            Text(
                text = "How to Use / راهنمای استفاده",
                style = MaterialTheme.typography.titleMedium,
                color = MdvColor.OnSurface
            )
            Spacer(modifier = Modifier.height(MdvSpace.S1))
            Text(
                text = "Quick setup and usage guide (English / Persian).",
                style = MaterialTheme.typography.bodySmall,
                color = MdvColor.OnSurfaceVariant
            )
        }
    }
}

@Composable
private fun HowToUseDialog(onDismiss: () -> Unit) {
    var lang by remember { mutableStateOf(HowToLang.EN) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxDialogContentHeight = (screenHeight * 0.65f)
    val textEn = """
        MasterDnsVPN is an advanced DNS tunneling VPN designed to bypass strict network censorship. It tunnels your traffic inside standard DNS queries (port 53) to keep you connected even during severe shutdowns.

        Note: To set up your own custom configuration, you must purchase a VPS and a domain name first.

        To ensure high resilience, the core uses:
        - Multipath transmission & packet duplication.
        - Load balancing across multiple DNS resolvers.
        - Low-overhead ARQ to handle data loss.

        Quick setup:
        1) Go to the Profiles tab.
        2) Create a new profile or import an existing one.
        3) Fill in the DOMAINS and ENCRYPTION_KEY fields (from your server configuration).
        4) Add DNS resolvers (IP or IP:PORT, one per line).
        5) Save and select the profile.
        6) Go back to the Home tab and tap Connect.
    """.trimIndent()

    val textFa = """
        برنامه MasterDnsVPN یک VPN پیشرفته مبتنی بر DNS Tunneling است که برای دور زدن فیلترینگ شدید طراحی شده است. این ابزار ترافیک شما را درون DNS Queryهای استاندارد (پورت 53) تونل می‌کند تا حتی در زمان قطعی‌های گسترده نیز اتصال شما برقرار بماند.

        توجه: در صورتی که قصد دارید کانفیگ شخصی خودتان را راه‌اندازی کنید، ابتدا نیاز به تهیه یک VPS و یک Domain دارید.

        هسته برنامه برای پایداری و Resilience بالا از قابلیت‌های زیر استفاده می‌کند:
        - ارسال Multipath و Packet Duplication.
        - توزیع بار (Load Balancing) روی چندین DNS Resolver.
        - سیستم کم‌هزینه ARQ برای مدیریت Packet Loss شبکه.

        راه‌اندازی سریع:
        ۱) به تب Profiles بروید.
        ۲) یک Profile جدید بسازید یا Profile موجود را وارد کنید.
        ۳) مقادیر DOMAINS و ENCRYPTION_KEY را بر اساس سرور خود پر کنید.
        ۴) لیست DNS Resolverها را وارد کنید (هر IP یا IP:PORT در یک خط).
        ۵) Profile را ذخیره و انتخاب کنید.
        ۶) به تب اصلی (Home) برگردید و دکمه Connect را لمس کنید.
    """.trimIndent()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
        title = { Text("How to Use") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxDialogContentHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(MdvSpace.S2)) {
                    AssistChip(
                        onClick = { lang = HowToLang.EN },
                        label = { Text("English") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (lang == HowToLang.EN) {
                                MdvColor.PrimaryContainer.copy(alpha = 0.2f)
                            } else MdvColor.SurfaceHigh
                        )
                    )
                    AssistChip(
                        onClick = { lang = HowToLang.FA },
                        label = { Text("فارسی") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (lang == HowToLang.FA) {
                                MdvColor.PrimaryContainer.copy(alpha = 0.2f)
                            } else MdvColor.SurfaceHigh
                        )
                    )
                }
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (lang == HowToLang.FA) {
                        LayoutDirection.Rtl
                    } else {
                        LayoutDirection.Ltr
                    }
                ) {
                    Text(
                        text = if (lang == HowToLang.EN) textEn else textFa,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MdvColor.OnSurface,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = if (lang == HowToLang.FA) TextAlign.Right else TextAlign.Left
                    )
                }
            }
        }
    )
}
