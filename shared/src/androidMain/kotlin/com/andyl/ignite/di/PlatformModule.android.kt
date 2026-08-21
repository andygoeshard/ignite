package com.andyl.ignite.di

import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.data.network.UdpDeviceDiscovery
import com.andyl.ignite.domain.DeviceDiscovery
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<DeviceDiscovery> { UdpDeviceDiscovery(get<DeviceInfo>()) }
}
