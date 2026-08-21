package com.andyl.ignite.di

import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration

/**
 * Starts Koin with the shared [appModule] plus any platform modules provided
 * through [config].
 */
fun initKoin(config: KoinAppDeclaration? = null) {
    // Idempotente: si Koin ya está iniciado (Application + MainActivity, o restart del Service) no crashear.
    runCatching {
        startKoin {
            config?.invoke(this)
            modules(appModule, platformModule())
        }
    }
}
