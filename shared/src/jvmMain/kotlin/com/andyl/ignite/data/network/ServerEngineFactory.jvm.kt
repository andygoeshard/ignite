package com.andyl.ignite.data.network

import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty


actual fun createServerEngine(module: Application.() -> Unit, port: Int): EmbeddedServer<*, *> =
    embeddedServer(Netty, port = port, module = module)
