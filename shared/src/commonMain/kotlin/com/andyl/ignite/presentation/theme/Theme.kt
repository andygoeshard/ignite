package com.andyl.ignite.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFE8590C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3D3),
    onPrimaryContainer = Color(0xFF3A1B00),
    secondary = Color(0xFF5B4A3F),
    background = Color(0xFFFCF8F6),
    surface = Color(0xFFFFFBF8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB686),
    onPrimary = Color(0xFF542D00),
    primaryContainer = Color(0xFF774200),
    onPrimaryContainer = Color(0xFFFFDBC5),
    secondary = Color(0xFFE0BEA9),
    background = Color(0xFF1A110C),
    surface = Color(0xFF201713),
)

@Composable
fun IgniteTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
