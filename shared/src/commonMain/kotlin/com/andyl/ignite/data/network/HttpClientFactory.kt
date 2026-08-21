package com.andyl.ignite.data.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Creates a shared [HttpClient]. No engine is specified: Ktor auto-selects the
 * engine available on each platform (OkHttp on Android, CIO on JVM).
 */
fun createHttpClient(): HttpClient = HttpClient {
    expectSuccess = false

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS // unlimited for large file uploads
        socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    }

    install(Logging) {
        level = LogLevel.INFO
    }
}
