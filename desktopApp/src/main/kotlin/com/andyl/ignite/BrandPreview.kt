package com.andyl.ignite

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.andyl.ignite.presentation.branding.BarsFlameMark
import com.andyl.ignite.presentation.branding.ScanFlameMark
import com.andyl.ignite.presentation.branding.FlameLeafMark
import com.andyl.ignite.presentation.branding.FlameMark

private val Bg = Color(0xFF000000)
private val Panel = Color(0xFF0A120D)
private val Muted = Color(0xFF6FA88A)

/**
 * Preview de las marcas candidatas (ronda logo v2). Correr con:
 *   ./gradlew :desktopApp:run -DmainClass=com.andyl.ignite.BrandPreviewKt
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Ignite · marcas v4 fueguitos",
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // La del usuario primero: es la que interesa
                FlameTraceSection()
                MarkSection("G · Tu llama v2", FlameMark)
                FlameLeafSection()
                MarkSection("E · Llama scanline", ScanFlameMark)
                MarkSection("F · Llama equalizer", BarsFlameMark)
            }
        }
    }
}

@Composable
private fun FlameTraceSection() {
    Text(
        "I · Tu diseño trazado desde Inkscape",
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Monospace,
        color = Muted,
    )
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf(24, 36, 48, 72).forEach { dpSize ->
            com.andyl.ignite.presentation.branding.FlameTraceMark(size = dpSize.dp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        AppTile(size = 96.dp) { com.andyl.ignite.presentation.branding.FlameTraceMark(size = 56.dp) }
        AppTile(size = 64.dp) { com.andyl.ignite.presentation.branding.FlameTraceMark(size = 38.dp) }
        AppTile(size = 44.dp) { com.andyl.ignite.presentation.branding.FlameTraceMark(size = 26.dp) }
    }
}

@Composable
private fun FlameLeafSection() {
    Text(
        "H · Hoja-flama (tu diseño)",
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Monospace,
        color = Muted,
    )
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf(16, 22, 32, 48).forEach { dpSize ->
            com.andyl.ignite.presentation.branding.FlameLeafMark(size = dpSize.dp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        AppTile(size = 96.dp) { com.andyl.ignite.presentation.branding.FlameLeafMark(size = 56.dp) }
        AppTile(size = 64.dp) { com.andyl.ignite.presentation.branding.FlameLeafMark(size = 38.dp) }
        AppTile(size = 44.dp) { com.andyl.ignite.presentation.branding.FlameLeafMark(size = 26.dp) }
    }
}

@Composable
private fun MarkSection(title: String, mark: ImageVector) {    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Monospace,
        color = Muted,
    )
    // Escala cruda: cómo se degrada el trazo en tamaños reales
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf(16, 22, 32, 48).forEach { size ->
            GlowBox(size = size.dp) {
                Icon(mark, contentDescription = null, modifier = Modifier.size(size.dp))
            }
        }
    }
    // Como tile de launcher (lo que verías instalado)
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        AppTile(size = 96.dp) { Icon(mark, contentDescription = null, modifier = Modifier.size(56.dp)) }
        AppTile(size = 64.dp) { Icon(mark, contentDescription = null, modifier = Modifier.size(38.dp)) }
        AppTile(size = 44.dp) { Icon(mark, contentDescription = null, modifier = Modifier.size(26.dp)) }
    }
    // Lockup con wordmark
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlowBox(size = 34.dp) {
            Icon(mark, contentDescription = null, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "IGNITE",
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 6.sp,
            color = Color.White,
        )
    }
}

/** Halo radial verde detrás del ícono (glow barato). */
@Composable
private fun GlowBox(size: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size * 1.9f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x3300FF87), Color.Transparent),
                    ),
                ),
        )
        content()
    }
}

/** Tile cuadrado estilo launcher: panel oscuro + borde tenue + esquinas cortadas suaves. */
@Composable
private fun AppTile(size: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .background(Panel, RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFF1D3328), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
