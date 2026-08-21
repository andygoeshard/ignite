package com.andyl.ignite.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Minimal MVI host: owns an immutable [State], a [Channel] of one-shot
 * [Effect]s and a single [onEvent] entry point.
 */
abstract class MviViewModel<Event, State, Effect> : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _effect = Channel<Effect>(Channel.BUFFERED)
    val effect: Flow<Effect> = _effect.receiveAsFlow()

    protected abstract fun initialState(): State

    fun onEvent(event: Event) {
        when (event) {
            else -> onEventImpl(event)
        }
    }

    protected abstract fun onEventImpl(event: Event)

    protected fun updateState(transform: (State) -> State) {
        _state.update(transform)
    }

    protected fun sendEffect(effect: Effect) {
        _effect.trySend(effect)
    }

    protected fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
