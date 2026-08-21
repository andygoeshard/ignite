package com.andyl.ignite.presentation.home

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Refresh
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
) {
    DroppableArea(onFiles = { files ->
        files.forEach { onEvent(HomeEvent.OnFileSelected(it)) }
    }) {
        HomeScreen(
            state = state,
            onEvent = onEvent,
            onPickFile = onPickFile,
        )
    }
}

@Composable
fun HomeScreen(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    onPickFile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(onRefresh = { onEvent(HomeEvent.OnRefresh) })

        RadarCard(
            devices = state.devices,
            selected = state.selectedDevice,
            isScanning = state.isScanning,
            onSelect = { onEvent(HomeEvent.OnDeviceSelected(it)) },
        )

        if (state.pendingFiles.isNotEmpty()) {
            FileQueue(
                files = state.pendingFiles,
                onClear = { onEvent(HomeEvent.OnFileCleared(it)) },
            )
        }

        if (state.isSending || state.activeFileName != null) {
            ProgressCard(
                fileName = state.activeFileName.orEmpty(),
                progress = state.progress,
            )
        }

        Spacer(Modifier.weight(1f))

        OutlinedButton(
            onClick = onPickFile,
            enabled = !state.isSending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Seleccionar archivo")
        }

        Button(
            onClick = { onEvent(HomeEvent.OnSendClick) },
            enabled = state.canSend,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.isSending) "Enviando..."
                else "Enviar a ${state.selectedDevice?.name ?: "dispositivo"}",
            )
        }
    }
}

@Composable
private fun Header(onRefresh: () -> Unit) {
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
                Text("Dispositivos", style = MaterialTheme.typography.titleMedium)
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
                    text = "Buscando dispositivos en la red local...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
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

            // Radar rings
            val ringColor = primary.copy(alpha = 0.2f)
            for (i in 1..3) {
                drawCircle(
                    color = ringColor,
                    radius = maxR * i / 3f,
                    center = center,
                    style = Stroke(width = 2f),
                )
            }

            // Device blips positioned radially.
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
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(fileName: String, progress: Float) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Enviando: $fileName",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
