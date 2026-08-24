package com.andyl.ignite.presentation.home

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.path

@Composable
actual fun rememberFolderPickerLauncher(onResult: (String?) -> Unit): () -> Unit {
    val launcher = rememberDirectoryPickerLauncher { directory ->
        onResult(directory?.path)
    }
    return { launcher.launch() }
}
