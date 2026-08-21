package com.andyl.ignite.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andyl.ignite.domain.model.Device
import kotlin.math.min

@Composable
fun HomeRoute(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DroppableArea(onFiles = { files ->
        files.forEach { onEvent(HomeEvent.OnFileSelected(it)) }
    }) {
        HomeScreen(
            state = state,
            onEvent = onEvent,
            onPickFile = onPickFile,
            modifier = modifier,
        )
    }
}

@Composable
fun HomeScreen(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(
            deviceName = state.deviceName,
            note = state.note,
            onRefresh = { onEvent(HomeEvent.OnRefresh) },
        )

        PinCard(
            myPin = state.myPin,
            onRegenerate = { onEvent(HomeEvent.OnRegeneratePin) },
            onToggleDialog = { onEvent(HomeEvent.OnTogglePinDialog) },
        )

        // Dialog de aprobación que bloquea hasta decidir (antes de escribir a disco)
        state.pendingApproval?.let { pending ->
            ApprovalCard(
                pending = pending,
                onApprove = { onEvent(HomeEvent.OnApproveIncoming) },
                onReject = { onEvent(HomeEvent.OnRejectIncoming) },
            )
        }

        RadarCard(
            devices = state.devices,
            selected = state.selectedDevice,
            isScanning = state.isScanning,
            onSelect = { onEvent(HomeEvent.OnDeviceSelected(it)) },
        )

        // PIN del receptor (requerido para enviar)
        if (state.selectedDevice != null) {
            OutlinedTextField(
                value = state.targetPin,
                onValueChange = { onEvent(HomeEvent.OnTargetPinChanged(it)) },
                label = { Text("PIN del receptor (6 dígitos)") },
                placeholder = { Text("Ej: ${state.myPin}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.targetPin.isNotEmpty() && state.targetPin.length != 6) {
                Text(
                    "El PIN debe ser de 6 dígitos",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (state.pendingFiles.isNotEmpty()) {
            FileQueue(
                files = state.pendingFiles,
                onClear = { onEvent(HomeEvent.OnFileCleared(it)) },
            )
        }

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(
            visible = state.recentReceived.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
        ) {
            RecentReceivedCard(
                files = state.recentReceived,
                onOpenFolder = { onEvent(HomeEvent.OnOpenReceivedFolder(it)) },
            )
        }

        if (state.isSending || state.activeFileName != null) {
            ProgressCard(
                fileName = state.activeFileName.orEmpty(),
                progress = state.progress,
                totalBytes = state.pendingFiles.sumOf { it.sizeBytes },
            )
        } else {
            state.sendOutcome?.let { outcome ->
                SendOutcomeRow(outcome)
            }
        }

        OutlinedButton(
            onClick = onPickFile,
            enabled = !state.isSending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.pendingFiles.isEmpty()) "Seleccionar archivo" else "Agregar otro archivo")
        }

        Button(
            onClick = { onEvent(HomeEvent.OnSendClick) },
            enabled = state.canSend,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    state.isSending -> "Enviando…"
                    state.selectedDevice == null -> "Elegí un dispositivo de la lista"
                    state.pendingFiles.isEmpty() -> "Seleccioná un archivo para enviar"
                    else -> "Enviar a ${state.selectedDevice.name}"
                },
            )
        }
    }
}

@Composable
private fun Header(deviceName: String, note: String?, onRefresh: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Ignite",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Buscar de nuevo")
            }
        }
        if (deviceName.isNotBlank()) {
            Text(
                text = "Visible en la red como «$deviceName»",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = !note.isNullOrBlank(), enter = fadeIn() + expandVertically()) {
            Text(
                text = note.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RadarCard(
    devices: List<Device>,
    selected: Device?,
    isScanning: Boolean,
    onSelect: (Device) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = if (devices.isEmpty()) "Dispositivos" else "Dispositivos (${devices.size})"
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            RadarGraph(devices)

            if (devices.isEmpty()) {
                Text(
                    text = if (isScanning) "Buscando dispositivos en la red local…"
                    else "No hay dispositivos. Tocá ↻ para buscar de nuevo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (isScanning) {
                    Text(
                        text = "Tip: ambos dispositivos tienen que estar en la misma red Wi-Fi y con la app abierta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(160.dp),
                ) {
                    items(devices, key = { it.id }) { device ->
                        DeviceRow(
                            device = device,
                            isSelected = device.id == selected?.id,
                            onClick = { onSelect(device) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: Device, isSelected: Boolean, onClick: () -> Unit) {
    val border = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device.host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RadarGraph(devices: List<Device>) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sizePx = min(size.width, size.height)
            val center = Offset(size.width / 2, size.height / 2)
            val maxR = sizePx / 2f

            val ringColor = primary.copy(alpha = 0.2f)
            for (i in 1..3) {
                drawCircle(
                    color = ringColor,
                    radius = maxR * i / 3f,
                    center = center,
                    style = Stroke(width = 2f),
                )
            }

            val n = devices.size
            devices.forEachIndexed { index, _ ->
                val angle = (index.toDouble() / n.toDouble()) * Math.PI * 2
                val radius = maxR * 0.5f
                val blip = Offset(
                    center.x + (Math.cos(angle).toFloat() * radius),
                    center.y + (Math.sin(angle).toFloat() * radius),
                )
                drawCircle(
                    color = tertiary,
                    radius = 10f,
                    center = blip,
                )
                drawCircle(
                    color = tertiary.copy(alpha = 0.3f),
                    radius = 16f,
                    center = blip,
                    style = Stroke(width = 2f, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun FileQueue(
    files: List<PendingFile>,
    onClear: (PendingFile) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Archivos a enviar", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            files.forEach { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatSize(file.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { onClear(file) }, modifier = Modifier.size(28.dp)) {
                        Text(
                            "✕",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (files.size > 1) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${files.size} archivos · ${formatSize(files.sumOf { it.sizeBytes })} en total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RecentReceivedCard(
    files: List<ReceivedFileUi>,
    onOpenFolder: (ReceivedFileUi) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp, end = 8.dp)) {
            Text(
                "Recibidos recientemente",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            files.take(3).forEach { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatSize(file.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    IconButton(onClick = { onOpenFolder(file) }) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = "Abrir carpeta",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(fileName: String, progress: Float, totalBytes: Long) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Enviando a… $fileName",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            val detail = if (totalBytes > 0) {
                "${formatSize((totalBytes * progress).toLong())} de ${formatSize(totalBytes)}"
            } else {
                "${(progress * 100).toInt()}%"
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PinCard(myPin: String, onRegenerate: () -> Unit, onToggleDialog: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Tu PIN de emparejamiento", style = MaterialTheme.typography.labelMedium)
                Text(
                    myPin.ifBlank { "------" },
                    style = MaterialTheme.typography.headlineMedium,
                    letterSpacing = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp),
                )
                Text(
                    "Compartí este código con quien te envía. Valida el header X-Ignite-Pin.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            IconButton(onClick = onRegenerate) {
                Icon(Icons.Default.Refresh, contentDescription = "Regenerar PIN")
            }
        }
    }
}

@Composable
private fun ApprovalCard(pending: PendingApprovalUi, onApprove: () -> Unit, onReject: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("¿Aceptar archivo?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(4.dp))
            Text(
                "«${pending.fileName}» de ${pending.peerName} (${formatSize(pending.sizeBytes)}) quiere escribir en tu carpeta. Nada se guarda hasta que apruebes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onApprove, modifier = Modifier.weight(1f)) { Text("Aceptar") }
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("Rechazar") }
            }
        }
    }
}

@Composable
private fun SendOutcomeRow(outcome: SendOutcome) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (outcome.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (outcome.success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (outcome.success) {
                if (outcome.count > 1) "${outcome.count} archivos enviados a ${outcome.targetName}"
                else "«${outcome.fileName}» enviado a ${outcome.targetName}"
            } else {
                "No se pudo enviar «${outcome.fileName}»"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (outcome.success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
        )
    }
}
