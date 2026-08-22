package com.andyl.ignite.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andyl.ignite.domain.model.Transfer
import com.andyl.ignite.presentation.format.formatRelativeTime
import com.andyl.ignite.presentation.format.formatSize
import kotlinx.coroutines.delay

@Composable
fun HistoryScreen(
    state: HistoryState,
    onEvent: (HistoryEvent) -> Unit,
) {
    // Ticker compartido (#33): un solo reloj para todos los "hace X min".
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000L)
            value = System.currentTimeMillis()
        }
    }
    var showClearConfirm by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .padding(16.dp),
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Historial",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showClearConfirm = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Borrar historial")
            }
        }

        Spacer(Modifier.height(12.dp))

        if (state.transfers.isEmpty()) {
            // Contrato tri-state (#32): LOADING / EMPTY / ERROR con acción
            Column {
                when {
                    state.error != null -> {
                        Text(
                            text = "No se pudo cargar el historial: ${state.error}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { onEvent(HistoryEvent.OnRefresh) }) { Text("Reintentar") }
                    }
                    state.isLoading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Cargando…",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = "Todavía no hay transferencias",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Cuando envíes o recibas un archivo, va a aparecer acá.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.transfers, key = { it.id }) { transfer ->
                    TransferRow(transfer, now)
                    HorizontalDivider()
                }
            }
        }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("¿Borrar historial?") },
            text = { Text("Se elimina el registro de todas las transferencias. Los archivos que ya guardaste no se borran.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onEvent(HistoryEvent.OnClearHistory)
                }) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun TransferRow(transfer: Transfer, now: Long) {
    val isSent = transfer.direction == Transfer.Direction.SENT
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isSent) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
            contentDescription = null,
            tint = if (isSent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transfer.fileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${if (isSent) "A" else "De"} ${transfer.peerName} · ${formatSize(transfer.sizeBytes)} · ${formatRelativeTime(transfer.createdAt, now)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TransferStatusBadge(transfer)
    }
}

@Composable
private fun TransferStatusBadge(transfer: Transfer) {
    val (label, color) = when (transfer.status) {
        Transfer.Status.COMPLETED -> "OK" to MaterialTheme.colorScheme.primary
        Transfer.Status.FAILED -> "Error" to MaterialTheme.colorScheme.error
        Transfer.Status.CANCELLED -> "Cancelada" to MaterialTheme.colorScheme.onSurfaceVariant
        Transfer.Status.INTERRUPTED -> "Interrumpida" to MaterialTheme.colorScheme.secondary
        Transfer.Status.IN_PROGRESS -> "${(transfer.progress * 100).toInt()}%" to MaterialTheme.colorScheme.tertiary
        Transfer.Status.QUEUED -> "En cola" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
