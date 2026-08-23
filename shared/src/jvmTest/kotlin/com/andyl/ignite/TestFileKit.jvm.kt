package com.andyl.ignite

import io.github.vinceglb.filekit.FileKit

actual fun initTestFileKit() {
    FileKit.init(appId = "com.andyl.ignite.test.vm")
}
