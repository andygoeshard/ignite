package com.andyl.ignite.domain

/**
 * Manages the local pairing PIN used to authorize incoming transfers.
 * PIN is a 6-digit code shown on screen; remote must send it in header X-Ignite-Pin.
 */
expect class PairingManager() {
    /** Current PIN, generates one if none exists */
    fun getPin(): String
    /** Validates [pin] against local PIN; blank/null means no auth required if no PIN set (but we always have one) */
    fun validate(pin: String?): Boolean
    /** Regenerates a new random PIN and persists it */
    fun regenerate(): String
}
