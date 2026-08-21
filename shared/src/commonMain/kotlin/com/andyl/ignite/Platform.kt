package com.andyl.ignite

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform