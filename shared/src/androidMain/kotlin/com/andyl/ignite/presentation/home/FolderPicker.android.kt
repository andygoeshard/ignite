package com.andyl.ignite.presentation.home

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** SAF: ACTION_OPEN_DOCUMENT_TREE con permiso persistente (sobrevive reinicios). */
@Composable
actual fun rememberFolderPickerLauncher(onResult: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure { println("[Ignite][ERROR] sin permiso persistente para $uri: ${it.message}") }
        }
        onResult(uri?.toString())
    }
    return {
        // Abrir directo en Descargas (algunas ROMs bloquean elegir la raíz,
        // pero verla de entrada evita andar navegando).
        val initial: Uri? = if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching { MediaStore.Downloads.EXTERNAL_CONTENT_URI }.getOrNull()
        } else {
            null
        }
        launcher.launch(initial)
    }
}
