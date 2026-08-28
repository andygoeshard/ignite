package com.andyl.ignite.presentation.home
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andyl.ignite.domain.TrustPolicy
import com.andyl.ignite.presentation.format.formatSize
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeEntry(onNavigateToHistory: () -> Unit) {
    val vm = koinViewModel<HomeViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Effects one-shot (#28): los mensajes transitorios los muestra el Scaffold
    LaunchedEffect(vm) {
        vm.effect.collect { effect ->
            when (effect) {
                is HomeEffect.ShowSnackbar -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    val pickerLauncher = rememberFilePickerLauncher(
        mode = FileKitMode.Single,
        onResult = { file ->
            if (file != null) {
                scope.launch { vm.onEvent(HomeEvent.OnFileSelected(file.toPendingFile())) }
            }
        },
    )

    // Multiplataforma: FileKit en desktop, SAF (ACTION_OPEN_DOCUMENT_TREE) en Android.
    val directoryLauncher = rememberFolderPickerLauncher { location ->
        vm.onEvent(HomeEvent.OnDownloadDirPicked(location))
    }

    // Folder picker para enviar carpetas (Fase 3)
    val sendFolderLauncher = rememberFolderPickerLauncher { location ->
        if (location != null) {
            scope.launch { vm.onEvent(HomeEvent.OnFolderSelected(location)) }
        }
    }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { e ->
            // Atajo desktop: Ctrl+O abre el selector de archivos
            if (e.isCtrlPressed && e.key == Key.O && e.type == KeyEventType.KeyUp) {
                pickerLauncher.launch()
                true
            } else {
                false
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.andyl.ignite.presentation.branding.FlameTraceMark(size = 44.dp, animate = false)
                        Spacer(Modifier.width(8.dp))
                        Text("Ignite", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.onEvent(HomeEvent.OnToggleClipboardSync) }) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = if (state.clipboardSyncEnabled) "Desactivar sync clipboard" else "Activar sync clipboard",
                            tint = if (state.clipboardSyncEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.onEvent(HomeEvent.OnProfileClick) }) {
                        Icon(Icons.Default.Person, contentDescription = "Mi dispositivo")
                    }
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "Historial")
                    }
                },
            )
        },
    ) { padding ->
        HomeRoute(
            state = state,
            onEvent = vm::onEvent,
            onPickFile = { pickerLauncher.launch() },
            onPickFolder = { sendFolderLauncher() },
            modifier = Modifier.padding(padding),
            onNavigateToHistory = onNavigateToHistory,
        )
    }

    if (state.showWelcome || state.showProfileDialog) {
        ProfileDialog(
            isWelcome = state.showWelcome,
            initialName = state.deviceName,
            downloadPath = state.downloadPath,
            canChooseDir = state.canChooseDownloadDir,
            onConfirm = { vm.onEvent(HomeEvent.OnRenameConfirm(it)) },
            onPickDirectory = { directoryLauncher() },
            onResetDirectory = { vm.onEvent(HomeEvent.OnDownloadDirPicked(null)) },
            onDismiss = { vm.onEvent(HomeEvent.OnDialogDismiss) },
        )
    }

    // QR de emparejamiento propio: escanealo desde el otro dispositivo.
    if (state.showPairQrDialog) {
        PairQrDialog(
            qrBitmap = remember(state.myPin, state.localIp) {
                runCatching { com.andyl.ignite.data.generateQr(vm.pairingQrContent()) }.getOrNull()
            },
            deviceName = state.deviceName,
            myPin = state.myPin,
            onDismiss = { vm.onEvent(HomeEvent.OnShowPairQr) },
        )
    }

    // Aprobación de archivos entrantes como diálogo modal: Aceptar / Cancelar / Más tarde.
    // "Más tarde" cierra el diálogo y deja un banner con cuenta atrás en la pantalla.
    (state.incoming as? IncomingUi.AwaitingApproval)?.takeIf { !it.deferred }?.let { approval ->
        val alreadyAuto = approval.peerDeviceId?.let { state.devicePolicies[it] } == TrustPolicy.AUTO
        IncomingApprovalDialog(
            approval = approval,
            onApprove = { vm.onEvent(HomeEvent.OnApproveIncoming) },
            onApproveAlways = approval.peerDeviceId?.takeIf { !alreadyAuto }?.let { id ->
                { vm.onEvent(HomeEvent.OnApproveIncomingAlways(id)) }
            },
            onReject = { vm.onEvent(HomeEvent.OnRejectIncoming) },
            onDefer = { vm.onEvent(HomeEvent.OnIncomingDeferred) },
        )
    }
}

@Composable
private fun IncomingApprovalDialog(
    approval: IncomingUi.AwaitingApproval,
    onApprove: () -> Unit,
    onApproveAlways: (() -> Unit)?,
    onReject: () -> Unit,
    onDefer: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDefer,
        title = { Text("¿Aceptar archivo?", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val previewBitmap = approval.previewBytes?.let { bytes ->
                    remember(bytes) { com.andyl.ignite.data.decodePreview(bytes) }
                }
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap,
                        contentDescription = "Vista previa de ${approval.fileName}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .heightIn(max = 180.dp)
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium),
                    )
                } else {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Text("«${approval.fileName}» quiere entrar desde ${approval.peerName}.")
                Text(
                    "${formatSize(approval.sizeBytes)} · nada se guarda hasta que apruebes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Si elegís \"Más tarde\", la conexión queda abierta hasta 2 minutos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress) // #31
                    onApprove()
                },
            ) { Text("Aceptar") }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (onApproveAlways != null) {
                    TextButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onApproveAlways()
                    }) { Text("Aceptar siempre de este dispositivo") }
                }
                TextButton(onClick = onDefer) { Text("Más tarde") }
                TextButton(onClick = onReject) { Text("Cancelar") }
            }
        },
    )
}

@Composable
private fun PairQrDialog(
    qrBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    deviceName: String,
    myPin: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Emparejar por QR", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = "Código QR de emparejamiento",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Escaneá este código desde Ignite en el otro dispositivo.")
                    // El PIN viaja dentro del QR, pero mostrarlo sirve para emparejar a mano
                    Text(
                        "PIN manual: $myPin",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("No se pudo generar tu QR — usá el PIN manual: $myPin")
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Listo") }
        },
    )
}

@Composable
private fun ProfileDialog(
    isWelcome: Boolean,
    initialName: String,
    downloadPath: String,
    canChooseDir: Boolean,
    onConfirm: (String) -> Unit,
    onPickDirectory: () -> Unit,
    onResetDirectory: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initialName) { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                com.andyl.ignite.presentation.branding.FlameTraceMark(size = 96.dp, animate = false)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isWelcome) "¡Bienvenido a Ignite!" else "Mi dispositivo",
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isWelcome) {
                    Text("Elegí cómo va a verte el resto de la red.")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Nombre del dispositivo") },
                    supportingText = { Text("Así te identifican los demás dispositivos") },
                )

                if (canChooseDir && !isWelcome) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .size(22.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Descargas",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = downloadPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            TextButton(onClick = onResetDirectory) { Text("Default") }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = onPickDirectory,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cambiar carpeta")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) {
                Text(if (isWelcome) "Empezar" else "Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isWelcome) "Después" else "Cerrar") }
        },
    )
}
