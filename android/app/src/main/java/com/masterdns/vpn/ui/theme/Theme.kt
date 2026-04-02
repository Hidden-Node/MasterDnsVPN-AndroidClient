package com.masterdns.vpn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Custom color palette
val DarkPrimary = Color(0xFF6C63FF)
val DarkSecondary = Color(0xFF03DAC6)
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E2E)
val DarkSurfaceVariant = Color(0xFF2A2A3E)
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkOnSurface = Color(0xFFE1E1E6)
val DarkError = Color(0xFFCF6679)

val LightPrimary = Color(0xFF6C63FF)
val LightSecondary = Color(0xFF03DAC6)
val LightBackground = Color(0xFFF8F9FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF0F0F5)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF1A1A2E)

val ConnectedGreen = Color(0xFF4CAF50)
val DisconnectedRed = Color(0xFFE53935)
val ConnectingAmber = Color(0xFFFFB300)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    secondary = DarkSecondary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onPrimary = DarkOnPrimary,
    onSurface = DarkOnSurface,
    error = DarkError,
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    secondary = LightSecondary,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onPrimary = LightOnPrimary,
    onSurface = LightOnSurface,
)

@Composable
fun MasterDnsVPNTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
