package com.andyl.ignite.presentation.branding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Marcas de Ignite (ronda v4 — "fueguitos" cuantizados).
 *
 * El fuego sí, pero NUNCA como silueta orgánica facetada (Firebase). Acá el
 * fuego se dibuja como lo dibujaría una terminal: cuantizado, digital.
 *
 * E) [ScanFlameMark] — llama cortada en franjas horizontales con huecos
 *    (scanlines CRT). El calor sube: verde abajo → cian arriba.
 * F) [BarsFlameMark] — barras verticales cuya envolvente forma una llama:
 *    es fuego y es medidor de actividad/progreso a la vez.
 */

private val NeonGreen = Color(0xFF00FF87)
private val NeonCyan = Color(0xFF00E5FF)

/** Calor que sube: verde en la base, cian en la punta. */
private fun heatGradient() = Brush.linearGradient(
    colors = listOf(NeonGreen, NeonCyan),
    start = Offset(0f, 24f),
    end = Offset(0f, 0f),
)

/**
 * E) Llama scanline: 6 franjas horizontales que dibujan la silueta (base
 * ancha, punta inclinada a la derecha). La franja del medio está partida en
 * dos segmentos: la "lengua" de la llama. Huecos de 1px entre franjas.
 */
val ScanFlameMark: ImageVector by lazy {
    ImageVector.Builder(
        name = "ScanFlameMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = heatGradient()) {
            // Franjas de abajo hacia arriba: (yTop, alto, tramos [xIni, xFin])
            band(yTop = 20f, h = 3f, segments = listOf(5f to 19f))
            band(yTop = 16f, h = 3f, segments = listOf(6f to 18f))
            // Lengua partida: hueco central = carácter de llama
            band(yTop = 12f, h = 3f, segments = listOf(7f to 11f, 13f to 17f))
            band(yTop = 8f, h = 3f, segments = listOf(9f to 16f))
            band(yTop = 4f, h = 3f, segments = listOf(11f to 15f))
            band(yTop = 1f, h = 2f, segments = listOf(13f to 15f))
        }
    }.build()
}

/**
 * F) Llama equalizer: 5 barras alineadas a la base cuya envolvente dibuja la
 * llama (centro alto, hombro derecho más largo = inclinación). Una brasa
 * suelta flota sobre el hombro derecho.
 */
val BarsFlameMark: ImageVector by lazy {
    ImageVector.Builder(
        name = "BarsFlameMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = heatGradient()) {
            // Barras de 3 de ancho, separadas 1.25, base común en y=22
            bar(x = 2f, yTop = 13f, h = 9f)
            bar(x = 6.25f, yTop = 7f, h = 15f)
            bar(x = 10.5f, yTop = 1f, h = 21f)
            bar(x = 14.75f, yTop = 6f, h = 16f)
            bar(x = 19f, yTop = 12f, h = 10f)
        }
        // Brasa suelta (destacada en cian puro)
        path(fill = SolidColor(NeonCyan)) {
            rect(x = 15.5f, y = 2.5f, w = 2f, h = 2f)
        }
    }.build()
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.band(
    yTop: Float,
    h: Float,
    segments: List<Pair<Float, Float>>,
) {
    segments.forEach { (x0, x1) -> rect(x = x0, y = yTop, w = x1 - x0, h = h) }
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.bar(x: Float, yTop: Float, h: Float) {
    rect(x = x, y = yTop, w = 3f, h = h)
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.rect(x: Float, y: Float, w: Float, h: Float) {
    moveTo(x, y)
    lineTo(x + w, y)
    lineTo(x + w, y + h)
    lineTo(x, y + h)
    close()
}

// ── G · Llama del usuario (Canvas, cortes en negativo real) ─────────────────

val UserFlameGreen = Color(0xFF00FF87)
val UserFlameCyan = Color(0xFF00E5FF)
private val UserFlameDeep = Color(0xFF004D40)

/**
 * Llama orgánica simplificada con canales de separación en NEGATIVO REAL
 * (BlendMode.Clear sobre capa offscreen: quedan transparentes, no blancos).
 * Grid de diseño 200x200 escalado al tamaño del composable.
 */
@Composable
fun UserFlameMark(modifier: Modifier = Modifier, size: Dp = 200.dp) {
    Box(modifier = modifier.size(size)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Offscreen: el Clear sólo afecta esta capa, no lo de atrás
                    compositingStrategy = CompositingStrategy.Offscreen
                },
        ) {
            val s = this.size.minDimension / 200f

            val flameGradient = Brush.verticalGradient(
                colors = listOf(UserFlameGreen, UserFlameCyan, UserFlameDeep),
                startY = 0f,
                endY = this.size.height,
            )

            val outerPath = Path().apply {
                moveTo(100f * s, 200f * s)
                cubicTo(25f * s, 175f * s, 15f * s, 110f * s, 90f * s, 45f * s)
                cubicTo(95f * s, 35f * s, 92f * s, 15f * s, 100f * s, 5f * s)
                cubicTo(110f * s, 30f * s, 185f * s, 90f * s, 170f * s, 145f * s)
                cubicTo(160f * s, 185f * s, 120f * s, 205f * s, 100f * s, 200f * s)
                close()
            }
            drawPath(outerPath, flameGradient)

            val cutStroke = Stroke(width = 4f * s, cap = StrokeCap.Square, join = StrokeJoin.Miter)

            fun cut(block: Path.() -> Unit) =
                drawPath(Path().apply(block), Color.Black, style = cutStroke, blendMode = BlendMode.Clear)

            cut {
                moveTo(75f * s, 202f * s)
                cubicTo(65f * s, 150f * s, 85f * s, 100f * s, 112f * s, 75f * s)
            }
            cut {
                moveTo(110f * s, 204f * s)
                cubicTo(100f * s, 140f * s, 120f * s, 90f * s, 145f * s, 55f * s)
            }
            cut {
                moveTo(142f * s, 175f * s)
                cubicTo(135f * s, 140f * s, 150f * s, 110f * s, 172f * s, 95f * s)
            }
        }
    }
}

// ── H · Llama con anatomía real (v2 del diseño del usuario) ─────────────────

/**
 * Llama con estructura de verdad: punta que se ladea a la derecha (el "lick")
 * y llama interior en negativo vía EvenOdd. Nada de gota ni rayones.
 */
val FlameMark: ImageVector by lazy {
    ImageVector.Builder(
        name = "FlameMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = heatGradient(),
            pathFillType = PathFillType.EvenOdd,
        ) {
            // Contorno exterior: base redondeada, izquierda en S,
            // punta doblada a la derecha con muesca debajo
            moveTo(6.5f, 21.5f)
            curveTo(4.2f, 19.2f, 4.0f, 15.2f, 6.8f, 11.8f)
            curveTo(8.6f, 9.6f, 9.4f, 7.2f, 9.6f, 4.6f)
            curveTo(9.7f, 2.6f, 11.2f, 1.2f, 13.2f, 1.6f)
            curveTo(14.0f, 3.6f, 15.2f, 5.4f, 16.8f, 7.0f)
            curveTo(19.2f, 9.8f, 20.6f, 13.0f, 20.0f, 16.2f)
            curveTo(19.4f, 19.2f, 17.2f, 21.2f, 14.0f, 21.8f)
            curveTo(11.5f, 22.3f, 8.8f, 22.2f, 6.5f, 21.5f)
            close()
            // Llama interior (hueco): misma gramática, más chica y abajo
            moveTo(11.0f, 19.6f)
            curveTo(9.6f, 18.0f, 9.6f, 15.4f, 11.2f, 13.2f)
            curveTo(12.0f, 12.1f, 12.5f, 10.9f, 12.7f, 9.6f)
            curveTo(13.6f, 10.9f, 14.9f, 12.6f, 15.3f, 14.4f)
            curveTo(15.7f, 16.2f, 15.0f, 18.3f, 13.3f, 19.4f)
            curveTo(12.6f, 19.9f, 11.7f, 20.0f, 11.0f, 19.6f)
            close()
        }
    }.build()
}

// ── I · Hoja-flama del usuario (4 lóbulos + venas en negativo) ──────────────

private val LeafDarkStart = Color(0xFF0A7C46)
private val LeafDarkEnd = Color(0xFF044222)
private val LeafMidStart = Color(0xFF3CB043)
private val LeafMidEnd = Color(0xFF147238)
private val LeafLightStart = Color(0xFF8CE769)
private val LeafLightEnd = Color(0xFF2CA64E)
private val LeafBrightStart = Color(0xFFA4F274)
private val LeafBrightEnd = Color(0xFF44B84B)

/**
 * Diseño del usuario: hoja/flama de 4 lóbulos (oscuro izq, claro der, medio
 * centro, brote brillante arriba) con tallo y venas que separan en negativo
 * real. Grid de diseño 512x512 escalado al tamaño del composable.
 */
@Composable
fun FlameLeafMark(modifier: Modifier = Modifier, size: Dp = 200.dp) {
    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val sx = this.size.width / 512f
        val sy = this.size.height / 512f
        fun Float.x() = this * sx
        fun Float.y() = this * sy

        // Lóbulo izquierdo (gota base)
        drawPath(
            Path().apply {
                moveTo(256f.x(), 480f.y())
                cubicTo(170f.x(), 480f.y(), 110f.x(), 410f.y(), 100f.x(), 310f.y())
                cubicTo(90f.x(), 210f.y(), 160f.x(), 186f.y(), 216f.x(), 190f.y())
                cubicTo(230f.x(), 191f.y(), 245f.x(), 200f.y(), 256f.x(), 220f.y())
                lineTo(256f.x(), 480f.y())
                close()
            },
            Brush.linearGradient(
                listOf(LeafDarkStart, LeafDarkEnd),
                start = Offset(100f.x(), 190f.y()),
                end = Offset(256f.x(), 480f.y()),
            ),
        )

        // Lóbulo derecho principal
        drawPath(
            Path().apply {
                moveTo(256f.x(), 480f.y())
                cubicTo(256f.x(), 480f.y(), 256f.x(), 270f.y(), 258f.x(), 250f.y())
                cubicTo(275f.x(), 225f.y(), 310f.x(), 190f.y(), 350f.x(), 240f.y())
                cubicTo(385f.x(), 285f.y(), 435f.x(), 260f.y(), 442f.x(), 305f.y())
                cubicTo(450f.x(), 355f.y(), 370f.x(), 460f.y(), 256f.x(), 480f.y())
                close()
            },
            Brush.linearGradient(
                listOf(LeafLightStart, LeafLightEnd),
                start = Offset(256f.x(), 250f.y()),
                end = Offset(450f.x(), 480f.y()),
            ),
        )

        // Hoja central / interior
        drawPath(
            Path().apply {
                moveTo(256f.x(), 440f.y())
                cubicTo(256f.x(), 440f.y(), 245f.x(), 310f.y(), 230f.x(), 270f.y())
                cubicTo(210f.x(), 225f.y(), 180f.x(), 210f.y(), 205f.x(), 160f.y())
                cubicTo(235f.x(), 100f.y(), 295f.x(), 130f.y(), 320f.x(), 180f.y())
                cubicTo(335f.x(), 210f.y(), 320f.x(), 260f.y(), 280f.x(), 310f.y())
                cubicTo(265f.x(), 330f.y(), 256f.x(), 440f.y(), 256f.x(), 440f.y())
                close()
            },
            Brush.linearGradient(
                listOf(LeafMidStart, LeafMidEnd),
                start = Offset(200f.x(), 160f.y()),
                end = Offset(320f.x(), 440f.y()),
            ),
        )

        // Punta superior (brote/flama)
        drawPath(
            Path().apply {
                moveTo(256f.x(), 330f.y())
                cubicTo(256f.x(), 330f.y(), 240f.x(), 240f.y(), 250f.x(), 200f.y())
                cubicTo(260f.x(), 160f.y(), 236f.x(), 90f.y(), 256f.x(), 60f.y())
                cubicTo(285f.x(), 100f.y(), 340f.x(), 150f.y(), 325f.x(), 205f.y())
                cubicTo(315f.x(), 240f.y(), 280f.x(), 280f.y(), 256f.x(), 330f.y())
                close()
            },
            Brush.linearGradient(
                listOf(LeafBrightStart, LeafBrightEnd),
                start = Offset(256f.x(), 60f.y()),
                end = Offset(325f.x(), 330f.y()),
            ),
        )

        // Tallo + venas: cortes reales (transparencia), no líneas blancas
        val clearStyle = androidx.compose.ui.graphics.drawscope.Fill
        fun vein(block: Path.() -> Unit) =
            drawPath(Path().apply(block), Color.Black, style = clearStyle, blendMode = BlendMode.Clear)

        vein {
            moveTo(244f.x(), 488f.y())
            cubicTo(244f.x(), 488f.y(), 248f.x(), 390f.y(), 256f.x(), 350f.y())
            cubicTo(264f.x(), 390f.y(), 268f.x(), 488f.y(), 268f.x(), 488f.y())
            close()
        }
        vein {
            moveTo(256f.x(), 350f.y())
            cubicTo(245f.x(), 310f.y(), 215f.x(), 260f.y(), 178f.x(), 245f.y())
            cubicTo(174f.x(), 258f.y(), 170f.x(), 272f.y(), 168f.x(), 285f.y())
            cubicTo(200f.x(), 300f.y(), 230f.x(), 330f.y(), 256f.x(), 350f.y())
            close()
        }
        vein {
            moveTo(256f.x(), 315f.y())
            cubicTo(268f.x(), 285f.y(), 290f.x(), 250f.y(), 318f.x(), 228f.y())
            cubicTo(310f.x(), 215f.y(), 300f.x(), 204f.y(), 290f.x(), 195f.y())
            cubicTo(265f.x(), 215f.y(), 240f.x(), 250f.y(), 228f.x(), 280f.y())
            cubicTo(238f.x(), 290f.y(), 247f.x(), 302f.y(), 256f.x(), 315f.y())
            close()
        }
        vein {
            moveTo(256f.x(), 220f.y())
            cubicTo(250f.x(), 195f.y(), 240f.x(), 165f.y(), 225f.x(), 140f.y())
            cubicTo(238f.x(), 130f.y(), 252f.x(), 122f.y(), 265f.x(), 115f.y())
            cubicTo(275f.x(), 145f.y(), 285f.x(), 180f.y(), 288f.x(), 215f.y())
            cubicTo(276f.x(), 210f.y(), 266f.x(), 213f.y(), 256f.x(), 220f.y())
            close()
        }
    }
}
