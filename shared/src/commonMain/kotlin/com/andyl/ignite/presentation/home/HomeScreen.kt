package com.andyl.ignite.presentation.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andyl.ignite.domain.model.Device
import com.andyl.ignite.presentation.format.formatSize
import kotlin.math.min

@Composable
fun HomeRoute(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToHistory: () -> Unit = {},
) {
    DroppableArea(onFiles = { files ->
        files.forEach { onEvent(HomeEvent.OnFileSelected(it)) }
    }) {
        HomeScreen(
            state = state,
            onEvent = onEvent,
            onPickFile = onPickFile,
            modifier = modifier,
            onNavigateToHistory = onNavigateToHistory,
        )
    }
}

@Composable
fun HomeScreen(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToHistory: () -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compact = maxWidth < 720.dp
        val expanded = maxWidth >= 1200.dp
        // #30: una sola lectura por composición; desactiva spins/pulsos/slides
        val reduceMotion = remember { com.andyl.ignite.data.isReduceMotionEnabled() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(
                deviceName = state.deviceName,
                localIp = state.localIp,
                isScanning = state.isScanning,
                reduceMotion = reduceMotion,
                onRefresh = { onEvent(HomeEvent.OnRefresh) },
            )

            if (state.interrupted.isNotEmpty()) {
                InterruptedBanner(
                    interrupted = state.interrupted,
                    onDismiss = { onEvent(HomeEvent.OnDismissInterrupted) },
                    onOpenHistory = onNavigateToHistory,
                )
            }

            // Recepción unificada (#7): la aprobación queda siempre arriba y visible
            state.incoming?.let { incoming ->
                IncomingCard(
                    incoming = incoming,
                    onApprove = { onEvent(HomeEvent.OnApproveIncoming) },
                    onReject = { onEvent(HomeEvent.OnRejectIncoming) },
                    onDismiss = { onEvent(HomeEvent.OnDismissIncoming) },
                )
            }

            when {
                // Expandido (≥1200dp): dispositivos | envío | recibidos
                expanded -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PinCard(
                            myPin = state.myPin,
                            onRegenerate = { onEvent(HomeEvent.OnRegeneratePin) },
                            onToggleDialog = { onEvent(HomeEvent.OnTogglePinDialog) },
                        )
                        DevicesSection(state, onEvent, focusManualIp = true, reduceMotion = reduceMotion)
                    }
                    SendSection(state, onEvent, onPickFile, Modifier.weight(1f), reduceMotion)
                    ReceiveSection(state, onEvent, Modifier.weight(1f))
                }

                // Mediano (720–1199dp): dos columnas
                !compact -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PinCard(
                            myPin = state.myPin,
                            onRegenerate = { onEvent(HomeEvent.OnRegeneratePin) },
                            onToggleDialog = { onEvent(HomeEvent.OnTogglePinDialog) },
                        )
                        DevicesSection(state, onEvent, focusManualIp = true, reduceMotion = reduceMotion)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SendSection(state, onEvent, onPickFile, Modifier.fillMaxWidth(), reduceMotion)
                        ReceiveSection(state, onEvent, Modifier.fillMaxWidth())
                    }
                }

                // Compacto (<720dp): una sola columna
                else -> {
                    PinCard(
                        myPin = state.myPin,
                        onRegenerate = { onEvent(HomeEvent.OnRegeneratePin) },
                        onToggleDialog = { onEvent(HomeEvent.OnTogglePinDialog) },
                    )
                    DevicesSection(state, onEvent, Modifier.fillMaxWidth(), reduceMotion = reduceMotion)
                    SendSection(state, onEvent, onPickFile, Modifier.fillMaxWidth(), reduceMotion)
                    ReceiveSection(state, onEvent, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun DevicesSection(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    modifier: Modifier = Modifier,
    focusManualIp: Boolean = false,
    reduceMotion: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RadarCard(
            devices = state.devices,
            selected = state.selectedDevice,
            isScanning = state.isScanning,
            error = state.error,
            reduceMotion = reduceMotion,
            onSelect = { onEvent(HomeEvent.OnDeviceSelected(it)) },
            onRetry = { onEvent(HomeEvent.OnRefresh) },
        )
        ManualConnectCard(
            manualIp = state.manualIp,
            onIpChanged = { onEvent(HomeEvent.OnManualIpChanged(it)) },
            onConnect = { onEvent(HomeEvent.OnManualConnect) },
            requestFocus = focusManualIp,
        )
    }
}

@Composable
private fun SendSection(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

        SessionArea(state, onEvent, reduceMotion = reduceMotion)

        OutlinedButton(
            onClick = onPickFile,
            enabled = !state.isSendActive,
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
                    state.sendSession is SendSession.Preparing -> "Preparando…"
                    state.sendSession is SendSession.Cancelling -> "Cancelando…"
                    state.sendSession is SendSession.Sending -> "Enviando…"
                    state.selectedDevice == null -> "Elegí un dispositivo de la lista"
                    state.pendingFiles.isEmpty() -> "Seleccioná un archivo para enviar"
                    state.targetPin.length != 6 -> "Ingresá el PIN de 6 dígitos"
                    else -> "Enviar a ${state.selectedDevice.name}"
                },
            )
        }
    }
}

@Composable
private fun ReceiveSection(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.recentReceived.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        modifier = modifier,
    ) {
        RecentReceivedCard(
            files = state.recentReceived,
            onOpenFolder = { onEvent(HomeEvent.OnOpenReceivedFolder(it)) },
        )
    }
}

/**
 * Zona de estado del envío (#24): anima transiciones entre fases sin
 * animar cada tick de progreso (la clave es la fase, no la sesión).
 */
@Composable
private fun SessionArea(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    val phase = when (val s = state.sendSession) {
        is SendSession.Preparing -> 1
        is SendSession.Sending -> 2
        is SendSession.Cancelling -> 3
        SendSession.Idle -> if (state.sendOutcome != null) 4 else 0
    }
    AnimatedContent(
        targetState = phase,
        transitionSpec = {
            // #30: con movimiento reducido, sólo fade
            if (reduceMotion) {
                fadeIn(tween(150)) togetherWith fadeOut(tween(90))
            } else {
                (fadeIn(tween(150)) + slideInVertically(tween(150)) { it / 8 }) togetherWith fadeOut(tween(90))
            }
        },
        label = "send-session",
        modifier = modifier,
    ) { _ ->
        when (val session = state.sendSession) {
            is SendSession.Sending -> ProgressCard(
                session = session,
                isCancelling = false,
                onCancel = { onEvent(HomeEvent.OnCancelSend) },
            )
            is SendSession.Cancelling -> ProgressCard(
                session = session.of,
                isCancelling = true,
                onCancel = { },
            )
            is SendSession.Preparing -> ProgressCard(
                session = SendSession.Sending(
                    targetName = session.targetName,
                    fileIndex = 0,
                    fileCount = session.fileCount,
                    fileName = "",
                    fileProgress = 0f,
                    fileSentBytes = 0L,
                    fileTotalBytes = 0L,
                    completedBytesBeforeCurrent = 0L,
                    totalBytes = session.totalBytes,
                ),
                isCancelling = false,
                onCancel = { onEvent(HomeEvent.OnCancelSend) },
                titleOverride = "Preparando envío a ${session.targetName}…",
            )
            SendSession.Idle -> state.sendOutcome?.let { outcome ->
                SendOutcomeRow(outcome)
            }
        }
    }
}

@Composable
private fun Header(
    deviceName: String,
    localIp: String,
    isScanning: Boolean,
    reduceMotion: Boolean,
    onRefresh: () -> Unit,
) {
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
                val spin = rememberInfiniteTransition(label = "refresh-spin")
                val angle by spin.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                    label = "refresh-angle",
                )
                // #30: sin movimiento reducido, gira mientras escanea; si no, estático
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Buscar de nuevo",
                    modifier = Modifier.rotate(if (isScanning && !reduceMotion) angle else 0f),
                )
            }
        }
        if (deviceName.isNotBlank()) {
            Text(
                text = "Visible en la red como «$deviceName»",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (localIp.isNotBlank()) {
            Text(
                text = "Tu IP local: $localIp — el otro debe estar en la misma (192.168.x)",
                style = MaterialTheme.typography.labelSmall,
                // WCAG AA (#Fase 3): texto pequeño en onSurfaceVariant, no terciario
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun RadarCard(
    devices: List<Device>,
    selected: Device?,
    isScanning: Boolean,
    error: String?,
    reduceMotion: Boolean,
    onSelect: (Device) -> Unit,
    onRetry: () -> Unit,
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

            RadarGraph(devices, pulse = isScanning && !reduceMotion)

            // Contrato tri-state (#32): LOADING / EMPTY / ERROR con acción
            if (devices.isNotEmpty()) {
                // heightIn hace que sea responsive y no corte en pantallas chicas dentro de un scroll padre
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(min = 120.dp, max = 260.dp).fillMaxWidth(),
                ) {
                    items(devices, key = { it.id }) { device ->
                        DeviceRow(
                            device = device,
                            isSelected = device.id == selected?.id,
                            onClick = { onSelect(device) },
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    when {
                        error != null -> {
                            Text(
                                text = "No se pudo buscar dispositivos: $error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onRetry) { Text("Reintentar") }
                        }
                        isScanning -> {
                            Text(
                                text = "Buscando dispositivos en la red local…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Tip: ambos dispositivos tienen que estar en la misma red Wi-Fi y con la app abierta.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        else -> {
                            Text(
                                text = "No hay dispositivos. Tocá ↻ para buscar de nuevo.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onRetry) { Text("Buscar de nuevo") }
                        }
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
    val haptic = LocalHapticFeedback.current

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress) // #31
                    onClick()
                },
            ),
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
private fun RadarGraph(devices: List<Device>, pulse: Boolean = false) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    // #30: pulso sutil sólo mientras escanea y si el sistema no pidió reducir movimiento
    val pulseAlpha by rememberInfiniteTransition(label = "radar-pulse").animateFloat(
        initialValue = 0.12f,
        targetValue = 0.32f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "radar-pulse-alpha",
    )
    val ringAlpha = if (pulse) pulseAlpha else 0.2f
    // #29: el radar es decorativo; la info real vive en el título y la lista
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics {
                contentDescription = "Radar: ${devices.size} ${if (devices.size == 1) "dispositivo" else "dispositivos"} detectado${if (devices.size == 1) "" else "s"}"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sizePx = min(size.width, size.height)
            val center = Offset(size.width / 2, size.height / 2)
            val maxR = sizePx / 2f

            val ringColor = primary.copy(alpha = ringAlpha)
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
private fun ProgressCard(
    session: SendSession.Sending,
    isCancelling: Boolean,
    onCancel: () -> Unit,
    titleOverride: String? = null,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = titleOverride ?: if (session.fileCount > 1) {
                    "Enviando archivo ${session.fileIndex + 1} de ${session.fileCount} a ${session.targetName}"
                } else {
                    "Enviando «${session.fileName}» a ${session.targetName}"
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            val preparing = session.fileTotalBytes <= 0L
            if (preparing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                if (session.fileCount > 1) {
                    Text(
                        text = "${session.fileName} · ${formatSize(session.fileSentBytes)} de ${formatSize(session.fileTotalBytes)} · ${(session.fileProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Text(
                        text = "${formatSize(session.fileSentBytes)} de ${formatSize(session.fileTotalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { session.fileProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (session.fileCount > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Total: ${formatSize(session.globalSentBytes)} de ${formatSize(session.totalBytes)} · ${(session.globalProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { session.globalProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onCancel,
                enabled = !isCancelling,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isCancelling) "Cancelando…" else "Cancelar")
            }
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
                    letterSpacing = 8.sp,
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
private fun ManualConnectCard(
    manualIp: String,
    onIpChanged: (String) -> Unit,
    onConnect: () -> Unit,
    requestFocus: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (requestFocus) runCatching { focusRequester.requestFocus() }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Conexión manual (si no se ven)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Si Mac no ve a Windows (firewall/VLAN), ingresá la IP del otro. En Windows: ipconfig → IPv4. En Mac: ifconfig | grep inet",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = onIpChanged,
                    label = { Text("IP del otro") },
                    placeholder = { Text("192.168.1.10") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { e ->
                            if ((e.key == Key.Enter || e.key == Key.NumPadEnter) && e.type == KeyEventType.KeyUp && manualIp.isNotBlank()) {
                                onConnect()
                                true
                            } else {
                                false
                            }
                        },
                )
                Button(onClick = onConnect, enabled = manualIp.isNotBlank()) { Text("Conectar") }
            }
        }
    }
}

/**
 * Tarjeta única de recepción (#7): fase de aprobación explícita (nada se
 * escribe hasta aceptar) y fase de progreso no modal, descartable.
 */
@Composable
private fun IncomingCard(
    incoming: IncomingUi,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when (incoming) {
            is IncomingUi.AwaitingApproval -> MaterialTheme.colorScheme.errorContainer
            is IncomingUi.Receiving -> MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (incoming) {
                is IncomingUi.AwaitingApproval -> {
                    Text(
                        "¿Aceptar archivo?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "«${incoming.fileName}» de ${incoming.peerName} (${formatSize(incoming.sizeBytes)}) quiere escribir en tu carpeta. Nada se guarda hasta que apruebes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(12.dp))
                    val haptic = LocalHapticFeedback.current
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress) // #31
                                onApprove()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Aceptar") }
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onReject()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Rechazar") }
                    }
                }

                is IncomingUi.Receiving -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Recibiendo «${incoming.fileName}»",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Ocultar progreso",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "De ${incoming.peerName} · ${formatSize(incoming.receivedBytes)} de ${formatSize(incoming.sizeBytes)} · ${(incoming.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { incoming.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SendOutcomeRow(outcome: SendOutcome) {
    val successColor = MaterialTheme.colorScheme.primary
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(outcome) {
        if (!outcome.cancelled) haptic.performHapticFeedback(HapticFeedbackType.LongPress) // #31
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (outcome.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (outcome.success) successColor else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = when {
                    outcome.cancelled -> "Envío cancelado — los archivos siguen en la cola"
                    outcome.success && outcome.count > 1 -> "${outcome.count} archivos enviados a ${outcome.targetName}"
                    outcome.success -> "«${outcome.fileName}» enviado a ${outcome.targetName}"
                    else -> "No se pudo enviar «${outcome.fileName}»"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (outcome.success) successColor else MaterialTheme.colorScheme.error,
            )
            if (!outcome.success && !outcome.cancelled && outcome.error != null) {
                Text(
                    text = outcome.error.userMessage(outcome.targetName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** #27: aviso de transferencias que quedaron a mitad cuando se cerró la app. */
@Composable
private fun InterruptedBanner(
    interrupted: List<InterruptedTransferUi>,
    onDismiss: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
            Text(
                text = if (interrupted.size == 1) {
                    "Una transferencia quedó interrumpida"
                } else {
                    "${interrupted.size} transferencias quedaron interrumpidas"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            val first = interrupted.first()
            val detail = if (interrupted.size == 1) {
                first
            } else {
                first.copy(fileName = "${first.fileName} y ${interrupted.size - 1} más")
            }
            Text(
                text = "«${detail.fileName}» con ${detail.peerName} · ${formatSize(detail.sizeBytes)} · ${formatSize((detail.sizeBytes * detail.progress).toLong())} transferidos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Descartar") }
                TextButton(onClick = onOpenHistory) { Text("Ver en historial") }
            }
        }
    }
}
