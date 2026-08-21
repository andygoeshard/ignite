package com.andyl.ignite.data.network

import java.io.File
import java.security.KeyStore

/**
 * TLS helper for wrapping EmbeddedServer in HTTPS with a self-signed cert.
 * The cert is generated on first pairing and exchanged via the PIN channel (out-of-band).
 * For LAN with shared Wi-Fi, HTTPS prevents passive sniffing even with self-signed.
 *
 * Usage (JVM/Desktop):
 *   val keyStore = loadOrCreateKeyStore(File(FileKit.filesDir.path, "ignite_keystore.jks"), "ignite-pass")
 *   embeddedServer(Netty, port, module).apply { sslConnector(keyStore, "ignite", { password = "ignite-pass" }) }
 *
 * Android CIO currently has limited TLS server support – for now we expose HTTP and document the upgrade path:
 * 1) Generate EC key pair + self-signed cert on first launch (PairingManager).
 * 2) On pairing, exchange SHA-256 fingerprint via PIN/QR.
 * 3) Sender's HttpClient uses fingerprint pinning (CertificatePinner on OkHttp, or custom TrustManager on CIO).
 */
object TlsConfig {
    const val KEYSTORE_FILE = "ignite_keystore.jks"
    const val KEY_ALIAS = "ignite"
    const val KEYSTORE_PASSWORD = "ignite-pass"

    fun keyStoreFile(baseDir: String): File = File(baseDir, KEYSTORE_FILE)

    /**
     * Returns existing KeyStore or null if not yet created (caller should create on first pairing).
     * Creation of self-signed cert requires BouncyCastle or JDK's CertAndKeyGen – left as extension point to avoid heavy deps.
     * Stub returns null -> server falls back to plain HTTP.
     */
    fun loadOrNull(baseDir: String): KeyStore? {
        val f = keyStoreFile(baseDir)
        if (!f.exists()) return null
        return runCatching {
            KeyStore.getInstance("JKS").apply {
                f.inputStream().use { load(it, KEYSTORE_PASSWORD.toCharArray()) }
            }
        }.getOrNull()
    }
}
