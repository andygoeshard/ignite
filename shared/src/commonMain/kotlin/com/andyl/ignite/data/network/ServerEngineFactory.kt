package com.andyl.ignite.data.network

import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer

/**
 * Creates the embedded HTTP server engine for [KtorFileReceiver]. Implemented
 * per platform: Netty on JVM (desktop), CIO on Android.
 */
expect fun createServerEngine(module: Application.() -> Unit, port: Int): EmbeddedServer<*, *>
