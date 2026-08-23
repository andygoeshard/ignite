package com.andyl.ignite.presentation.home

import androidx.compose.runtime.Composable

/** Desktop no escanea QR en v1 (mostrar código sí puede). */
@Composable
actual fun rememberQrScannerLauncher(onResult: (String?) -> Unit): (() -> Unit)? = null
