package com.andyl.ignite.data.network

import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty


actual fun createServerEngine(module: Application.() -> Unit, port: Int): EmbeddedServer<*, *> =
    embeddedServer(
        Netty,
        configure = {
            connectors.add(EngineConnectorBuilder().apply { this.port = port })
            connectionGroupSize = 1
            workerGroupSize = 2
            callGroupSize = 4
        },
    ) {
        module()
    }
