package com.andyl.ignite.presentation.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andyl.ignite.domain.TrustPolicy
import com.andyl.ignite.domain.model.Device
import com.andyl.ignite.presentation.format.formatSize
import kotlinx.coroutines.delay
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
        // Sin scrolling en mediano/expandido; compacto o ventana muy baja scrollean
        val scrollable = compact || maxHeight < 460.dp
        // #30: una sola lectura por composición; desactiva spins/pulsos/slides
        val reduceMotion = remember { com.andyl.ignite.data.isReduceMotionEnabled() }
        // Escáner de QR (null en Desktop): un solo lanzador para toda la pantalla
        val qrScanner = rememberQrScannerLauncher { raw ->
            if (raw != null) onEvent(HomeEvent.OnQrScanned(raw))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(
                deviceName = state.deviceName,
                localIp = state.localIp,
                isScanning = state.isScanning,
                receiverActive = state.receiverActive,
                reduceMotion = reduceMotion,
                onRefresh = { onEvent(HomeEvent.OnRefresh) },
                onToggleActive = { onEvent(HomeEvent.OnToggleReceiverActive) },
            )

            if (state.interrupted.isNotEmpty()) {
                InterruptedBanner(
                    interrupted = state.interrupted,
                    onDismiss = { onEvent(HomeEvent.OnDismissInterrupted) },
                    onOpenHistory = onNavigateToHistory,
                )
            }

            // La aprobación modal vive en HomeEntry (diálogo). Acá queda:
            // - "Más tarde" → banner no modal con cuenta atrás
            // - Recepción en curso → tarjeta de progreso
            state.incoming?.let { incoming ->
                when (incoming) {
                    is IncomingUi.AwaitingApproval -> if (incoming.deferred) {
                        DeferredApprovalBanner(
                            approval = incoming,
                            onApprove = { onEvent(HomeEvent.OnApproveIncoming) },
                            onReject = { onEvent(HomeEvent.OnRejectIncoming) },
                        )
                    }
                    is IncomingUi.Receiving -> IncomingCard(incoming)
                }
            }

            if (!scrollable) {
                // Modo fijo: las columnas reparten la altura disponible y las listas flexan.
                // Jerarquía: transmisión al centro/frente, dispositivos como panel secundario.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    if (expanded) {
                        DevicesCard(
                            state = state,
                            onEvent = onEvent,
                            scanQr = qrScanner,
                            focusManualIp = true,
                            flexList = true,
                            modifier = Modifier.weight(1f),
                        )
                        TransferCard(
                            state = state,
                            onEvent = onEvent,
                            onPickFile = onPickFile,
                            reduceMotion = reduceMotion,
                            modifier = Modifier.weight(1.15f),
                        )
                        ReceiveSection(state, onEvent, Modifier.weight(1f))
                    } else {
                        DevicesCard(
                            state = state,
                            onEvent = onEvent,
                            scanQr = qrScanner,
                            focusManualIp = true,
                            flexList = true,
                            modifier = Modifier.weight(1f),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TransferCard(
                                state = state,
                                onEvent = onEvent,
                                onPickFile = onPickFile,
                                reduceMotion = reduceMotion,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.weight(1f))
                            ReceiveSection(state, onEvent, Modifier.fillMaxWidth())
                        }
                    }
                }
            } else {
                // Compacto (<720dp): columna con scroll; lo importante primero:
                // la zona de transmisión encabeza la pantalla, los dispositivos van después.
                TransferCard(
                    state = state,
                    onEvent = onEvent,
                    onPickFile = onPickFile,
                    reduceMotion = reduceMotion,
                    modifier = Modifier.fillMaxWidth(),
                )
                DevicesCard(
                    state = state,
                    onEvent = onEvent,
                            scanQr = qrScanner,
                    focusManualIp = false,
                    flexList = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                ReceiveSection(state, onEvent, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Panel secundario de dispositivos: lista compacta, conexión manual inline y
 * el propio PIN como chip en la cabecera. Sin radar: lo decorativo compite
 * con lo importante.
 */
@Composable
private fun DevicesCard(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    modifier: Modifier = Modifier,
    focusManualIp: Boolean = false,
    /** En modo fijo, la lista ocupa la altura restante. */
    flexList: Boolean = false,
    /** Lanzador del escáner de QR; null en plataformas sin cámara. */
    scanQr: (() -> Unit)? = null,
) {
    NeoCard(
        modifier = modifier,
        container = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        // Cabecera: etiqueta + spinner + mi PIN como chip regenerable
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Dispositivos")
            Spacer(Modifier.width(6.dp))
            if (state.isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            }
            Spacer(Modifier.weight(1f))
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        text = "PIN ${state.myPin.ifBlank { "------" }}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = { onEvent(HomeEvent.OnRegeneratePin) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Regenerar PIN",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        // Contrato tri-state (#32): lista / ERROR+acción / LOADING / EMPTY+acción
        when {
            state.devices.isNotEmpty() -> {
                val listModifier =
                    // max obligatorio: dentro del scroll compacto, altura infinita crashea el LazyColumn
                    if (flexList) Modifier.weight(1f) else Modifier.heightIn(min = 96.dp, max = 260.dp)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = listModifier.fillMaxWidth(),
                ) {
                    items(state.devices, key = { it.id }) { device ->
                        val policy = state.devicePolicies[device.id] ?: TrustPolicy.ASK
                        DeviceRow(
                            device = device,
                            isSelected = device.id == state.selectedDevice?.id,
                            isTrusted = device.id in state.trustedIds,
                            policy = policy,
                            onClick = { onEvent(HomeEvent.OnDeviceSelected(device)) },
                            onForget = { onEvent(HomeEvent.OnForgetDevice(device.id, device.name)) },
                            onCyclePolicy = { onEvent(HomeEvent.OnCycleDevicePolicy(device.id)) },
                        )
                    }
                }
            }
            state.error != null -> CompactMessage(
                text = state.error,
                actionText = "Reintentar",
                onAction = { onEvent(HomeEvent.OnRefresh) },
                isError = true,
            )
            else -> CompactMessage(
                text = if (state.isScanning) {
                    "Buscando dispositivos en tu red…"
                } else {
                    "No se encontró nadie. Abrí Ignite en la otra máquina o conectá por IP."
                },
                actionText = if (state.isScanning) null else "Buscar de nuevo",
                onAction = if (state.isScanning) null else ({ onEvent(HomeEvent.OnRefresh) }),
                isError = false,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ManualIpRow(
            value = state.manualIp,
            onValueChange = { onEvent(HomeEvent.OnManualIpChanged(it)) },
            onConnect = { onEvent(HomeEvent.OnManualConnect) },
            requestFocus = focusManualIp,
        )

        // Emparejamiento por QR: mostrar propio / escanear ajeno (donde haya cámara)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { onEvent(HomeEvent.OnShowPairQr) },
                enabled = state.localIp.isNotBlank() && !state.isSendActive && state.receiverActive,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Emparejar por QR", style = MaterialTheme.typography.labelLarge)
            }
            if (scanQr != null) {
                Button(
                    onClick = scanQr,
                    enabled = !state.isSendActive,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Escanear", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Mensaje compacto del tri-state con acción opcional. */
@Composable
private fun CompactMessage(
    text: String,
    actionText: String?,
    onAction: (() -> Unit)?,
    isError: Boolean,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionText != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            ) {
                Text(actionText, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Conexión manual inline: IP + Conectar, Enter dispara. */
@Composable
private fun ManualIpRow(
    value: String,
    onValueChange: (String) -> Unit,
    onConnect: () -> Unit,
    requestFocus: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (requestFocus) runCatching { focusRequester.requestFocus() }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Conectar por IP") },
            placeholder = { Text("192.168.x.x") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        onConnect()
                        true
                    } else {
                        false
                    }
                },
        )
        TextButton(onClick = onConnect, enabled = value.isNotBlank()) {
            Text("Conectar")
        }
    }
}

/**
 * Card héroe de transmisión: el estado de la sesión arriba (archivo + barra
 * neón), cola compacta, PIN destino y acciones. Un solo vistazo dice todo.
 */
@Composable
private fun TransferCard(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    val active = state.sendSession !is SendSession.Idle

    NeoCard(
        modifier = modifier,
        container = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // Cabecera: etiqueta + chip de estado + cancelar cuando corresponde
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Transmisión")
            Spacer(Modifier.width(8.dp))
            // Toggle File | Text (Fase 3)
            if (!active) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row {
                        Text(
                            text = "📁",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clickable { if (state.isTextMode) onEvent(HomeEvent.OnToggleTextMode) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            color = if (!state.isTextMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "💬",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clickable { if (!state.isTextMode) onEvent(HomeEvent.OnToggleTextMode) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            color = if (state.isTextMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            StatusChip(
                text = when (val s = state.sendSession) {
                    is SendSession.Preparing -> "Preparando"
                    is SendSession.Sending -> "Enviando"
                    is SendSession.Cancelling -> "Cancelando"
                    SendSession.Idle -> when {
                        state.sendOutcome?.success == true -> "Completo"
                        state.sendOutcome?.cancelled == true -> "Cancelado"
                        state.sendOutcome != null -> "Error"
                        else -> "Listo"
                    }
                },
                color = when {
                    state.sendSession is SendSession.Cancelling -> MaterialTheme.colorScheme.error
                    state.sendOutcome != null && !state.sendOutcome.success && !state.sendOutcome.cancelled ->
                        MaterialTheme.colorScheme.error
                    active || state.sendOutcome?.success == true -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.weight(1f))
            if (state.sendSession is SendSession.Sending || state.sendSession is SendSession.Preparing) {
                TextButton(onClick = { onEvent(HomeEvent.OnCancelSend) }) {
                    Text("Cancelar")
                }
            }
        }

        // Zona héroe: fase de sesión animada (#24); con reduce-motion sólo fade (#30)
        val phase = when (val s = state.sendSession) {
            is SendSession.Preparing -> 1
            is SendSession.Sending -> 2
            is SendSession.Cancelling -> 3
            SendSession.Idle -> if (state.sendOutcome != null) 4 else 0
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(tween(150)) togetherWith fadeOut(tween(90))
                } else {
                    (fadeIn(tween(150)) + slideInVertically(tween(150)) { it / 8 }) togetherWith fadeOut(tween(90))
                }
            },
            label = "send-hero",
            modifier = Modifier.fillMaxWidth(),
        ) { _ ->
            when (val session = state.sendSession) {
                is SendSession.Sending -> HeroProgress(session, isCancelling = false)
                is SendSession.Cancelling -> HeroProgress(session.of, isCancelling = true)
                is SendSession.Preparing -> HeroPreparing(session.targetName)
                SendSession.Idle -> state.sendOutcome?.let { SendOutcomeRow(it) } ?: HeroIdle()
            }
        }

        // Cola compacta (sólo cuando no hay envío activo ocupando la zona héroe)
        if (state.pendingFiles.isNotEmpty() && !active && !state.isTextMode) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                state.pendingFiles.forEach { file ->
                    QueueRow(file = file, onClear = { onEvent(HomeEvent.OnFileCleared(file)) })
                }
                if (state.pendingFiles.size > 1) {
                    Text(
                        text = "${state.pendingFiles.size} archivos · ${formatSize(state.pendingFiles.sumOf { it.sizeBytes })} en total",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Campo de texto (modo texto, Fase 3)
        if (state.isTextMode && !active) {
            OutlinedTextField(
                value = state.textInput,
                onValueChange = { onEvent(HomeEvent.OnTextInputChanged(it)) },
                label = { Text("Escribí un mensaje") },
                placeholder = { Text("Hola, mirá esto…") },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${state.textInput.length} caracteres",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // PIN del receptor + pista de PIN recordado
        if (state.selectedDevice != null) {
            OutlinedTextField(
                value = state.targetPin,
                onValueChange = { onEvent(HomeEvent.OnTargetPinChanged(it)) },
                label = { Text("PIN del receptor (6 dígitos)") },
                placeholder = { Text("Ej: ${state.myPin}") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.targetPin.isNotEmpty() && state.targetPin.length != 6) {
                Text(
                    "El PIN debe ser de 6 dígitos",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (state.pinRememberedFor == state.selectedDevice.name) {
                Text(
                    "Usando el PIN recordado de ${state.selectedDevice.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Acciones
        if (state.isTextMode) {
            Button(
                onClick = { onEvent(HomeEvent.OnSendText) },
                enabled = state.canSendText && !active,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        active -> "Enviando…"
                        state.selectedDevice == null -> "Elegí un dispositivo"
                        state.textInput.isBlank() -> "Escribí un mensaje"
                        state.targetPin.length != 6 -> "Ingresá el PIN"
                        else -> "Enviar texto a ${state.selectedDevice.name}"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onPickFile,
                    enabled = !active,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.pendingFiles.isEmpty()) "Agregar archivo" else "Agregar otro")
                }
                Button(
                    onClick = { onEvent(HomeEvent.OnSendClick) },
                    enabled = state.canSend,
                    modifier = Modifier.weight(1.4f),
                ) {
                    Text(
                        when {
                            state.sendSession is SendSession.Preparing -> "Preparando…"
                            state.sendSession is SendSession.Cancelling -> "Cancelando…"
                            state.sendSession is SendSession.Sending -> "Enviando…"
                            state.selectedDevice == null -> "Elegí un dispositivo"
                            state.pendingFiles.isEmpty() -> "Seleccioná un archivo"
                            state.targetPin.length != 6 -> "Ingresá el PIN"
                            else -> "Enviar a ${state.selectedDevice.name}"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Zona héroe durante un envío: archivo grande + barra neón + bytes. */
@Composable
private fun HeroProgress(session: SendSession.Sending, isCancelling: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = session.fileName.ifBlank { "Preparando…" },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append("a ${session.targetName}")
                if (session.fileCount > 1) append(" · archivo ${session.fileIndex + 1} de ${session.fileCount}")
            },
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val preparing = session.fileTotalBytes <= 0L
        NeonProgressBar(
            progress = if (preparing) 0.04f else session.fileProgress,
            height = 14.dp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${formatSize(session.fileSentBytes)} / ${formatSize(session.fileTotalBytes)}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${(session.fileProgress * 100).toInt()}%",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (session.fileCount > 1) {
            NeonProgressBar(progress = session.globalProgress, height = 6.dp)
            Text(
                text = "Total: ${formatSize(session.globalSentBytes)} de ${formatSize(session.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isCancelling) {
            Text(
                "Cancelando…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun HeroPreparing(targetName: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = "Preparando envío a $targetName…",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun HeroIdle() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Listo para transmitir.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Agregá archivos y elegí un destino.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Fila compacta de la cola de archivos a enviar. */
@Composable
private fun QueueRow(file: PendingFile, onClear: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatSize(file.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onClear, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Quitar ${file.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
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
        visible = state.recentReceived.isNotEmpty() || state.receivedTextMessages.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.recentReceived.isNotEmpty()) {
                RecentReceivedCard(
                    files = state.recentReceived,
                    onOpenFolder = { onEvent(HomeEvent.OnOpenReceivedFolder(it)) },
                )
            }
            if (state.receivedTextMessages.isNotEmpty()) {
                ReceivedTextsCard(
                    messages = state.receivedTextMessages,
                    onDismiss = { onEvent(HomeEvent.OnDismissTextMessage(it)) },
                    onEditResend = { onEvent(HomeEvent.OnEditTextMessage(it)) },
                )
            }
        }
    }
}

/** Card base del look cyberpunk: panel oscuro, borde tenue, esquinas cortadas. */
@Composable
private fun NeoCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = container,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** Etiqueta de sección: mono, mayúsculas, tracking amplio. */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Chip de estado: cut-corner, texto mono en mayúsculas. */
@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * Barra de progreso neón: track oscuro, relleno degradado verde→cian y una
 * banda de brillo que recorre la parte llena mientras transfiere.
 */
@Composable
private fun NeonProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outlineVariant
    val fillStart = MaterialTheme.colorScheme.primary
    val fillEnd = MaterialTheme.colorScheme.tertiary
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(240),
        label = "neon-fill",
    )
    val sweep by rememberInfiniteTransition(label = "neon-sweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "sweep-pos",
    )
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = track, cornerRadius = r)
        drawRoundRect(color = outline, cornerRadius = r, style = Stroke(width = 1.dp.toPx()))
        val fillW = size.width * animated
        if (fillW > size.height / 2f) {
            clipRect(right = fillW) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(fillStart, fillEnd)),
                    cornerRadius = r,
                    size = androidx.compose.ui.geometry.Size(fillW, this.size.height),
                )
                if (animated < 0.999f) {
                    val bandX = sweep * this.size.width
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.40f), Color.Transparent),
                            startX = bandX - this.size.width * 0.05f,
                            endX = bandX + this.size.width * 0.05f,
                        ),
                        start = Offset(bandX, 2f),
                        end = Offset(bandX, this.size.height - 2f),
                        strokeWidth = 6f,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    deviceName: String,
    localIp: String,
    isScanning: Boolean,
    receiverActive: Boolean,
    reduceMotion: Boolean,
    onRefresh: () -> Unit,
    onToggleActive: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (!receiverActive) {
                    Text(
                        text = "Recepción pausada — invisible en la red",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = deviceName.ifBlank { "Ignite" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (deviceName.isNotBlank()) {
                    Text(
                        text = "Visible en la red como",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text(
                        text = "Listo para compartir",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // Pausar/reactivar receptor (estilo "Receiving Off" de AirDrop)
            IconButton(onClick = onToggleActive) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = if (receiverActive) "Pausar recepción" else "Reactivar recepción",
                    tint = if (receiverActive) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
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
private fun DeviceRow(
    device: Device,
    isSelected: Boolean,
    isTrusted: Boolean,
    policy: TrustPolicy,
    onClick: () -> Unit,
    onForget: () -> Unit,
    onCyclePolicy: () -> Unit,
) {
    val border = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    val haptic = LocalHapticFeedback.current

    Surface(
        shape = MaterialTheme.shapes.medium,
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
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
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
                    text = if (policy == TrustPolicy.ASK) device.host
                    else "${device.host} · ${policyLabel(policy)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (policy == TrustPolicy.ASK) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.tertiary,
                )
            }
            if (isTrusted) {
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCyclePolicy()
                }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = policyDescription(policy),
                        tint = when (policy) {
                            TrustPolicy.ASK -> MaterialTheme.colorScheme.tertiary
                            TrustPolicy.AUTO -> MaterialTheme.colorScheme.primary
                            TrustPolicy.SILENT -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onForget, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.VisibilityOff,
                        contentDescription = "Olvidar dispositivo ${device.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Etiqueta corta del modo de recepción, junto a la IP. */
private fun policyLabel(policy: TrustPolicy): String = when (policy) {
    TrustPolicy.ASK -> ""
    TrustPolicy.AUTO -> "auto-aceptar"
    TrustPolicy.SILENT -> "silencioso"
}

/** Accesibilidad del candado que rota la política. */
private fun policyDescription(policy: TrustPolicy): String = when (policy) {
    TrustPolicy.ASK -> "Te pregunta antes de aceptar. Tocá para auto-aceptar."
    TrustPolicy.AUTO -> "Auto-acepta sus archivos. Tocá para modo silencioso."
    TrustPolicy.SILENT -> "Modo silencioso. Tocá para volver a preguntar."
}

@Composable
private fun RecentReceivedCard(
    files: List<ReceivedFileUi>,
    onOpenFolder: (ReceivedFileUi) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
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

/** Banner de mensajes de texto recibidos (Fase 3): copy, edit & resend, dismiss. */
@Composable
private fun ReceivedTextsCard(
    messages: List<TextMessageUi>,
    onDismiss: (Int) -> Unit,
    onEditResend: (String) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp, end = 8.dp)) {
            Text(
                "Mensajes recibidos",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            messages.take(5).forEachIndexed { index, msg ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${msg.senderName} · ${msg.senderHost}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(msg.text))
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("Copiar", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { onEditResend(msg.text) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("Reenviar", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = { onDismiss(index) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("Descartar", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tarjeta de progreso de recepción (#7): no modal, descartable. La
 * aprobación vive en el diálogo de HomeEntry / banner "Más tarde".
 */
@Composable
private fun IncomingCard(incoming: IncomingUi.Receiving) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Recibiendo «${incoming.fileName}»",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "De ${incoming.peerName} · ${formatSize(incoming.receivedBytes)} de ${formatSize(incoming.sizeBytes)} · ${(incoming.progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            NeonProgressBar(
                progress = incoming.progress,
                height = 8.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Banner no modal para aprobaciones en "Más tarde": muestra cuenta atrás y
 * permite resolver sin volver a abrir el diálogo. La conexión sigue abierta
 * hasta decidir o vencer (2 min).
 */
@Composable
private fun DeferredApprovalBanner(
    approval: IncomingUi.AwaitingApproval,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    // Cuenta atrás visual-local: ticker de 1s derivado del expiresAt del estado
    val remainingSecs by produceState((approval.expiresAtMillis - System.currentTimeMillis()) / 1000) {
        while (true) {
            value = ((approval.expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            if (value <= 0L) break
            delay(1_000)
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "«${approval.fileName}» de ${approval.peerName} espera tu respuesta",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${formatSize(approval.sizeBytes)} · se cancela en ${remainingSecs / 60}:${"%02d".format(remainingSecs % 60)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove) { Text("Aceptar") }
                OutlinedButton(onClick = onReject) { Text("Rechazar") }
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
        shape = MaterialTheme.shapes.large,
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
