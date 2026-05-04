package com.masterdns.vpn.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

val ConnectedGreen = MdvColor.PrimaryContainer
val DisconnectedRed = MdvColor.Error
val ConnectingAmber = MdvColor.PrimaryDim

private val StitchColorScheme = darkColorScheme(
    primary = MdvColor.Primary,
    onPrimary = Color(0xFF00363D),
    primaryContainer = MdvColor.PrimaryContainer,
    onPrimaryContainer = Color(0xFF001F24),
    secondary = MdvColor.Secondary,
    onSecondary = Color(0xFF243141),
    background = MdvColor.Background,
    onBackground = MdvColor.OnSurface,
    surface = MdvColor.Surface,
    onSurface = MdvColor.OnSurface,
    surfaceVariant = MdvColor.SurfaceHigh,
    onSurfaceVariant = MdvColor.OnSurfaceVariant,
    error = MdvColor.Error,
    onError = MdvColor.OnError,
    errorContainer = MdvColor.ErrorContainer,
    onErrorContainer = MdvColor.Error
)

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.5.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.4.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.2.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.5.sp
    )
)

@Composable
fun MasterDnsVPNTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StitchColorScheme,
        typography = AppTypography,
        content = content
    )
}
