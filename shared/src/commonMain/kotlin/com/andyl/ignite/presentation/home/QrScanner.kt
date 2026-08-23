package com.andyl.ignite.presentation.home

import androidx.compose.runtime.Composable

/**
 * Lanza el escáner de QR nativo y devuelve el contenido crudo por [onResult]
 * (null si el usuario canceló). Devuelve null esta plataforma no escanea.
 */
@Composable
expect fun rememberQrScannerLauncher(onResult: (String?) -> Unit): (() -> Unit)?
