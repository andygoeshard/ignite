package com.andyl.ignite.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Matriz de módulos del QR como filas de booleans (true = oscuro).
 * Pura y multiplataforma: los renderers por plataforma (AWT / Bitmap) solo
 * pintan esto; los tests pueden validarla sin Skia ni bitmaps nativos.
 * Null si el contenido excede la capacidad del QR.
 */
fun qrModules(content: String): Array<BooleanArray>? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1)
    // ZXing ya mete su propia zona de silencio de 4 módulos en la matriz:
    // la recortamos para que el padding lo controle forEachQrPixel.
    val q = 4
    Array(matrix.height - 2 * q) { y ->
        BooleanArray(matrix.width - 2 * q) { x -> matrix.get(x + q, y + q) }
    }
}.getOrNull()

/**
 * Recorre el bitmap final del QR (fondo blanco implícito + zona de silencio
 * de 4 módulos) invocando [pixel] por cada píxel a pintar.
 * [targetPx] es el lado deseado; devuelve el lado real (>= targetPx/2).
 *
 * OJO: la escala se calcula con división entera forzada a >=1 — el bug
 * histórico era scale=0 → bitmap de 0px → "no genera el QR".
 */
inline fun forEachQrPixel(
    modules: Array<BooleanArray>,
    targetPx: Int,
    pixel: (x: Int, y: Int, isDark: Boolean) -> Unit,
): Int {
    val quiet = 4
    val side = modules.size
    val cells = side + 2 * quiet
    val scale = (targetPx / cells).coerceAtLeast(1)
    val total = cells * scale
    for (x in 0 until total) {
        for (y in 0 until total) {
            val mx = x / scale - quiet
            val my = y / scale - quiet
            val isDark = mx >= 0 && my >= 0 && mx < side && my < side && modules[my][mx]
            pixel(x, y, isDark)
        }
    }
    return total
}
