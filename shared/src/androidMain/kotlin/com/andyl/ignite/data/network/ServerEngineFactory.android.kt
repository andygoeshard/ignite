package com.andyl.ignite.data.network

import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.cio.CIO

actual fun createServerEngine(module: Application.() -> Unit, port: Int): EmbeddedServer<*, *> =
    io.ktor.server.engine.embeddedServer(CIO, port = port, module = module)
