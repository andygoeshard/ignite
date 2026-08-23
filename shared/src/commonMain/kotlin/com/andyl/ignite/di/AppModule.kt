package com.andyl.ignite.di

import com.andyl.ignite.data.AppStorage
import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.data.RoomTransferRepository
import com.andyl.ignite.data.createAppStorage
import com.andyl.ignite.data.createDeviceInfo
import com.andyl.ignite.data.createTrustedDevices
import com.andyl.ignite.data.db.IgniteDatabase
import com.andyl.ignite.data.db.createDatabase
import com.andyl.ignite.data.network.KtorFileReceiver
import com.andyl.ignite.data.network.KtorFileSender
import com.andyl.ignite.data.network.createHttpClient
import com.andyl.ignite.data.network.createServerEngine
import com.andyl.ignite.domain.FileReceiver
import com.andyl.ignite.domain.FileSender
import com.andyl.ignite.domain.PairingManager
import com.andyl.ignite.domain.TransferRepository
import com.andyl.ignite.domain.model.TransferDefaults
import com.andyl.ignite.presentation.home.HomeViewModel
import com.andyl.ignite.presentation.history.HistoryViewModel
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Cross-platform dependency injection. [platformModule] is provided per
 * platform (expect/actual) for storage, device info and discovery.
 */
expect fun platformModule(): Module

val appModule: Module = module {
    single<HttpClient> { createHttpClient() }

    single<AppStorage> { createAppStorage() }
    single<DeviceInfo> { createDeviceInfo() }
    single<PairingManager> { PairingManager() }
    single { createTrustedDevices() }

    single<IgniteDatabase> { createDatabase() }
    single<TransferRepository> {
        RoomTransferRepository(requireNotNull(get<IgniteDatabase>().transferDao()))
    }

    single<FileSender> { KtorFileSender(get(), get()) }
    single<com.andyl.ignite.domain.ReceiverController> {
        com.andyl.ignite.domain.ReceiverController(get(), get())
    }
    single<FileReceiver> {
        KtorFileReceiver(
            storage = get(),
            repository = get(),
            pairingManager = get(),
            deviceInfo = get(),
            port = TransferDefaults.PORT,
            engineFactory = ::createServerEngine,
            trustedDevices = get(),
        )
    }

    viewModel {
        HomeViewModel(
            discovery = get(),
            sender = get(),
            receiver = get(),
            repository = get(),
            deviceInfo = get(),
            storage = get(),
            notifier = get(),
            pairingManager = get(),
            httpClient = get(),
            trustedDevices = get(),
            receiverController = get(),
            externalDrops = com.andyl.ignite.data.externalDropFlow(),
        )
    }
    viewModelOf(::HistoryViewModel)
}
