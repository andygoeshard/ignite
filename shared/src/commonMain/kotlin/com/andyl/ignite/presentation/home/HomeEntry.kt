package com.andyl.ignite.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andyl.ignite.presentation.format.formatSize
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
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

    val directoryLauncher = rememberDirectoryPickerLauncher { directory ->
        vm.onEvent(HomeEvent.OnDownloadDirPicked(directory?.path))
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
                        com.andyl.ignite.presentation.branding.FlameTraceMark(size = 44.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Ignite", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
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
            onPickDirectory = { directoryLauncher.launch() },
            onResetDirectory = { vm.onEvent(HomeEvent.OnDownloadDirPicked(null)) },
            onDismiss = { vm.onEvent(HomeEvent.OnDialogDismiss) },
        )
    }

    // Aprobación de archivos entrantes como diálogo modal: Aceptar / Cancelar / Más tarde.
    // "Más tarde" cierra el diálogo y deja un banner con cuenta atrás en la pantalla.
    (state.incoming as? IncomingUi.AwaitingApproval)?.takeIf { !it.deferred }?.let { approval ->
        IncomingApprovalDialog(
            approval = approval,
            onApprove = { vm.onEvent(HomeEvent.OnApproveIncoming) },
            onReject = { vm.onEvent(HomeEvent.OnRejectIncoming) },
            onDefer = { vm.onEvent(HomeEvent.OnIncomingDeferred) },
        )
    }
}

@Composable
private fun IncomingApprovalDialog(
    approval: IncomingUi.AwaitingApproval,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDefer: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDefer,
        title = { Text("¿Aceptar archivo?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                TextButton(onClick = onDefer) { Text("Más tarde") }
                TextButton(onClick = onReject) { Text("Cancelar") }
            }
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
                com.andyl.ignite.presentation.branding.FlameTraceMark(size = 96.dp)
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
