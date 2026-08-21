package com.andyl.ignite.domain

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import java.io.File
import kotlin.random.Random

actual class PairingManager actual constructor() {
    actual fun getPin(): String {
        return runCatching {
            val file = pinFile()
            if (file.exists()) {
                val existing = file.readText().trim()
                if (existing.matches(Regex("\\d{6}"))) return existing
            }
            regenerate()
        }.getOrElse {
            Random.nextInt(0, 1_000_000).toString().padStart(6, '0')
        }
    }

    actual fun validate(pin: String?): Boolean {
        if (pin.isNullOrBlank()) return false
        return pin.trim() == getPin()
    }

    actual fun regenerate(): String {
        val newPin = Random.nextInt(0, 1_000_000).toString().padStart(6, '0')
        runCatching {
            val file = pinFile()
            file.parentFile?.mkdirs()
            file.writeText(newPin)
        }
        return newPin
    }

    private fun pinFile(): File = File(FileKit.filesDir.path, "pairing_pin")
}
