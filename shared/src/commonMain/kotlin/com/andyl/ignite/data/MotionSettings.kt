package com.andyl.ignite.data

/**
 * #30: true si el sistema pide reducir movimiento (ej. escalas de animación
 * en 0 en Android). En desktop no hay setting equivalente: false.
 */
expect fun isReduceMotionEnabled(): Boolean
