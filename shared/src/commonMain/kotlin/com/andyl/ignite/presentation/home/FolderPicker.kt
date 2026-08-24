package com.andyl.ignite.presentation.home

import androidx.compose.runtime.Composable

/**
 * Lanza el selector de carpeta del sistema. Devuelve la ubicación elegida
 * como String (path en desktop, tree URI en Android) o null si canceló.
 */
@Composable
expect fun rememberFolderPickerLauncher(onResult: (String?) -> Unit): () -> Unit
