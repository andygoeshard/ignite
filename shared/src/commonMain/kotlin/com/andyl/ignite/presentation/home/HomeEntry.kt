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
