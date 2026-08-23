package com.andyl.ignite

/** En tests unitarios de Android no hay Context: los fakes no necesitan FileKit. */
actual fun initTestFileKit() = Unit
