package com.andyl.ignite.presentation.history

import androidx.lifecycle.viewModelScope
import com.andyl.ignite.domain.TransferRepository
import com.andyl.ignite.presentation.MviViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val repository: TransferRepository,
) : MviViewModel<HistoryEvent, HistoryState, HistoryEffect>() {

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
            sendEffect(HistoryEffect.ShowMessage("Historial borrado"))
        }
    }
}
