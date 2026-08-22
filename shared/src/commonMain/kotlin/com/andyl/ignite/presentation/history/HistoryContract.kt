package com.andyl.ignite.presentation.history

import com.andyl.ignite.domain.model.Transfer

sealed interface HistoryEvent {
    data object OnRefresh : HistoryEvent
    data object OnClearHistory : HistoryEvent
}

data class HistoryState(
    val transfers: List<Transfer> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

sealed interface HistoryEffect {
    data class ShowSnackbar(val message: String) : HistoryEffect
}
