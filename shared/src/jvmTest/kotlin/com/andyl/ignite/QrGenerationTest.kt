package com.andyl.ignite

import com.andyl.ignite.data.forEachQrPixel
import com.andyl.ignite.data.qrModules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regresión del generador de QR a nivel de matriz pura (qrModules), que es
 * lo que ambas plataformas pintan. El bug histórico: división entera
 * scale=0 → bitmap 0px → "no genera el QR".
 */
class QrGenerationTest {

    private val pairingPayload =
        """{"id":"a1b2c3d4-1234","name":"Andy Mac","host":"192.168.1.42","port":48233,"pin":"482913"}"""

    @Test
    fun qrModules_producesValidMatrix() {
        val modules = qrModules(pairingPayload)
        assertNotNull(modules, "La matriz no debe ser null para payloads normales")
        assertTrue(modules.size >= 21, "Un QR v1 mínimo tiene 21 módulos por lado")
        assertEquals(modules.size, modules.first().size, "Debe ser cuadrada")

        // Patrones de posición: siempre hay cuadrados oscuros en las 3 esquinas
        fun finderDarkAt(ox: Int, oy: Int) = (0..6).all { dy -> (0..6).all { dx ->
            val edge = dx == 0 || dx == 6 || dy == 0 || dy == 6
            val center = dx in 2..4 && dy in 2..4
            modules[oy + dy][ox + dx] == (edge || center)
        } }
        assertTrue(finderDarkAt(0, 0), "Finder pattern sup-izq")
        assertTrue(finderDarkAt(modules.size - 7, 0), "Finder pattern sup-der")
        assertTrue(finderDarkAt(0, modules.size - 7), "Finder pattern inf-izq")
    }

    @Test
    fun forEachQrPixel_scalesUpWithoutCollapsing() {
        val modules = qrModules("hola")!!
        var painted = 0
        var total = 0
        total = forEachQrPixel(modules, 512) { _, _, isDark ->
            if (isDark) painted++
        }
        // Con target 512 y ~29+8 celdas, scale >= 1 → total > 0 y proporción oscura razonable
        assertTrue(total > 0, "El lado total nunca debe ser 0")
        assertTrue(painted > 0, "Debe haber módulos oscuros pintados")
        val ratio = painted.toDouble() / (total * total)
        assertTrue(ratio in 0.05..0.6, "Proporción de oscuros implausible: $ratio")
    }

    @Test
    fun qrModules_nullForOversizedContent() {
        // Un QR no puede contener megabytes
        val huge = "x".repeat(5_000_000)
        assertEquals(null, qrModules(huge))
    }
}
