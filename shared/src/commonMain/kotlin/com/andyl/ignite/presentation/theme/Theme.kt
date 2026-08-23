package com.andyl.ignite.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ── Paleta cyberpunk: verde neón sobre negro puro ───────────────────────────

private val NeonGreen = Color(0xFF00FF87)
private val NeonCyan = Color(0xFF00E5FF)
private val PureBlack = Color(0xFF000000)
private val NearBlack = Color(0xFF050705)
private val DarkPanel = Color(0xFF0A120D)
private val DarkPanelAlt = Color(0xFF0E1A13)

private val CyberColors = darkColorScheme(
    primary = NeonGreen,
    onPrimary = Color(0xFF00110A),
    primaryContainer = Color(0xFF00331E),
    onPrimaryContainer = Color(0xFF6BFFB0),

    secondary = Color(0xFF35E0A1),
    onSecondary = Color(0xFF00291A),
    secondaryContainer = Color(0xFF0B2E20),
    onSecondaryContainer = Color(0xFF86F2C4),

    tertiary = NeonCyan,
    onTertiary = Color(0xFF00252B),
    tertiaryContainer = Color(0xFF06333A),
    onTertiaryContainer = Color(0xFF88F4FF),

    error = Color(0xFFFF4D6A),
    onError = Color(0xFF2B0009),
    errorContainer = Color(0xFF3A0716),
    onErrorContainer = Color(0xFFFFB3C2),

    background = PureBlack,
    onBackground = Color(0xFFC9F5DB),
    surface = NearBlack,
    onSurface = Color(0xFFBEF2D1),
    surfaceVariant = DarkPanel,
    onSurfaceVariant = Color(0xFF6FA88A),
    surfaceContainer = DarkPanel,
    surfaceContainerLow = Color(0xFF070B08),
    surfaceContainerHigh = DarkPanelAlt,
    surfaceContainerHighest = Color(0xFF13221A),

    outline = Color(0xFF1D3328),
    outlineVariant = Color(0xFF14241C),
    inverseSurface = Color(0xFFC9F5DB),
    inverseOnSurface = Color(0xFF06120B),
    scrim = PureBlack,
)

/** Esquinas redondeadas y suaves: cards, botones, chips y diálogos. */
private val CyberShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Tema de la app. Siempre oscuro: el look cyberpunk verde/negro es la
 * identidad del producto, no un modo.
 */
@Composable
fun IgniteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = CyberColors,
        shapes = CyberShapes,
        content = content,
    )
}
