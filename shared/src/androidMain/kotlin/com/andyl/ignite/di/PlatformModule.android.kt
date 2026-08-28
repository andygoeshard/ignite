package com.andyl.ignite.di

import com.andyl.ignite.data.AndroidClipboardMonitor
import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.data.db.AndroidContextHolder
import com.andyl.ignite.data.network.CompositeDeviceDiscovery
import com.andyl.ignite.data.network.MdnsDeviceDiscovery
import com.andyl.ignite.data.network.SubnetScannerDiscovery
import com.andyl.ignite.data.network.UdpDeviceDiscovery
import com.andyl.ignite.data.notification.AndroidTransferNotifier
import com.andyl.ignite.domain.ClipboardMonitor
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
                SubnetScannerDiscovery(get(), get()),
            ),
        )
    }
    single<TransferNotifier> { AndroidTransferNotifier(AndroidContextHolder.context) }
    single<ClipboardMonitor> { AndroidClipboardMonitor(AndroidContextHolder.context) }
}
