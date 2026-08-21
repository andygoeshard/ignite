package com.andyl.ignite.di

import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.data.network.CompositeDeviceDiscovery
import com.andyl.ignite.data.network.MdnsDeviceDiscovery
import com.andyl.ignite.data.network.UdpDeviceDiscovery
import com.andyl.ignite.data.notification.JvmTransferNotifier
import com.andyl.ignite.domain.DeviceDiscovery
import com.andyl.ignite.domain.TransferNotifier
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<DeviceDiscovery> {
        CompositeDeviceDiscovery(
            listOf(
                UdpDeviceDiscovery(get<DeviceInfo>()),
                MdnsDeviceDiscovery(get<DeviceInfo>()),
            ),
        )
    }
    single<TransferNotifier> { JvmTransferNotifier() }
}
