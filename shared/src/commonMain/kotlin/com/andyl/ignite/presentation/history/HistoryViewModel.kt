package com.andyl.ignite.presentation.history

import androidx.lifecycle.viewModelScope
import com.andyl.ignite.domain.TransferRepository
import com.andyl.ignite.presentation.MviViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val repository: TransferRepository,
) : MviViewModel<HistoryEvent, HistoryState, HistoryEffect>() {

    private var noteJob: Job? = null

    override fun initialState(): HistoryState = HistoryState()

    init {
        observeTransfers()
    }

    private fun observeTransfers() {
        repository.observeTransfers()
            .onEach { list ->
                updateState { it.copy(transfers = list, isLoading = false) }
            }
            .catch { _ ->
                updateState { it.copy(isLoading = false) }
            }
            .launchIn(viewModelScope)
    }

    override fun onEventImpl(event: HistoryEvent) {
        when (event) {
            HistoryEvent.OnRefresh -> observeTransfers()
            HistoryEvent.OnClearHistory -> clearHistory()
        }
    }

    private fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            noteJob?.cancel()
            updateState { it.copy(note = "Historial borrado") }
            noteJob = viewModelScope.launch {
                delay(NOTE_DURATION_MS)
                updateState { it.copy(note = null) }
            }
        }
    }

    private companion object {
        const val NOTE_DURATION_MS = 4_000L
    }
}
