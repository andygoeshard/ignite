package com.andyl.ignite.presentation.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity

/** Escaneo con la CaptureActivity de zxing-android-embedded. */
@Composable
actual fun rememberQrScannerLauncher(onResult: (String?) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val activity = context as? ComponentActivity ?: return null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onResult(result.data?.getStringExtra("SCAN_RESULT"))
    }
    return {
        launcher.launch(Intent(activity, com.journeyapps.barcodescanner.CaptureActivity::class.java))
    }
}
