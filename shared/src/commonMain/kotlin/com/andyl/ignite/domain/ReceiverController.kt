package com.andyl.ignite.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dueño del estado encendido/apagado del receptor. Lo comparten la UI (botón
 * ⏻ de la cabecera) y el ícono de bandeja en desktop, así el estado nunca
 * diverge entre ambos controles. Fase 2a.
 */
class ReceiverController(
    private val receiver: FileReceiver,
    private val discovery: DeviceDiscovery,
) {
    private val mutex = Mutex()
    private val _active = MutableStateFlow(true)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    suspend fun pause(): Boolean = mutex.withLock {
        val stopped = runCatching { receiver.stop() }.isSuccess
        val silenced = runCatching { discovery.stop() }.isSuccess
        if (stopped && silenced) _active.value = false
        stopped && silenced
    }

    suspend fun resume(): Boolean = mutex.withLock {
        val listening = runCatching { receiver.start() }.isSuccess
        val announcing = runCatching { discovery.start() }.isSuccess
        if (listening && announcing) _active.value = true
        listening && announcing
    }

    suspend fun toggle(): Boolean =
        if (_active.value) pause() else resume()
}
