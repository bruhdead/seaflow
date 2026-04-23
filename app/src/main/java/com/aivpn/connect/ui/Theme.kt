package com.aivpn.connect.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cosmic purple palette inspired by the nebula image
val CosmicBlack = Color(0xFF0A0014)
val DeepPurple = Color(0xFF1A0033)
val NebulaPurple = Color(0xFF6B0F8F)
val BrightPurple = Color(0xFFBB33FF)
val NeonPink = Color(0xFFE040FB)
val StarWhite = Color(0xFFF0E6FF)
val DimStar = Color(0xFF9E8CB5)
val ConnectedGreen = Color(0xFF00E676)
val ErrorRed = Color(0xFFFF5252)
val SurfaceDark = Color(0xFF120022)

private val CosmicColorScheme = darkColorScheme(
    primary = BrightPurple,
    onPrimary = CosmicBlack,
    secondary = NeonPink,
    onSecondary = CosmicBlack,
    tertiary = ConnectedGreen,
    background = CosmicBlack,
    onBackground = StarWhite,
    surface = SurfaceDark,
    onSurface = StarWhite,
    surfaceVariant = DeepPurple,
    onSurfaceVariant = DimStar,
    error = ErrorRed,
    onError = CosmicBlack,
    outline = NebulaPurple,
)

@Composable
fun AivpnConnectTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CosmicColorScheme,
        typography = Typography(),
        content = content
    )
}
