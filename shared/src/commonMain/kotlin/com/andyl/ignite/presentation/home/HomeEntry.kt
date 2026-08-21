package com.andyl.ignite.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    LaunchedEffect(Unit) {
        vm.onEvent(HomeEvent.OnStart)
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
        topBar = {
            TopAppBar(
                title = { Text("Ignite", fontWeight = FontWeight.Bold) },
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
        )
    }

    state.incoming?.let { incoming ->
        IncomingDialog(
            incoming = incoming,
            downloadPath = state.downloadPath,
            onDismiss = { vm.onEvent(HomeEvent.OnDismissIncoming) },
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
}

@Composable
private fun IncomingDialog(
    incoming: IncomingFileUi,
    downloadPath: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.DownloadDone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        },
        title = {
            Text("Nuevo archivo de ${incoming.peerName}", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(
                                text = incoming.fileName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatSize(incoming.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                LinearProgressIndicator(
                    progress = { incoming.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${(incoming.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                Text(
                    text = "Se guarda en:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = downloadPath,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text("Recibiendo en segundo plano") }
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
            Text(
                if (isWelcome) "¡Bienvenido a Ignite!" else "Mi dispositivo",
                fontWeight = FontWeight.Bold,
            )
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
                    Text(
                        text = "Descargas",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = downloadPath,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onPickDirectory) { Text("Cambiar…") }
                                TextButton(onClick = onResetDirectory) { Text("Por defecto") }
                            }
                        }
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

internal fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
