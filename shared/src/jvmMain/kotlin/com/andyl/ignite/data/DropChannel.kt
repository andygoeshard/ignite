package com.andyl.ignite.data

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Puente entre la ventana drop zone (desktop, Fase 2a) y el HomeViewModel:
 * la ventana emite paths soltados y el VM los agrega a la cola y envía.
 */
object DropChannel {
    val drops = MutableSharedFlow<List<String>>(extraBufferCapacity = 16)

    fun offer(paths: List<String>) {
        if (paths.isNotEmpty()) drops.tryEmit(paths)
    }
}
