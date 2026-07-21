package com.lhstack.suxi.dns

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform