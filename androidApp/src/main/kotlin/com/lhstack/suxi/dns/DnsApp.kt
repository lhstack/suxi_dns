package com.lhstack.suxi.dns

import android.app.Application
import com.lhstack.suxi.dns.dns.BlockRulesInitializer
import com.lhstack.suxi.dns.dns.DnsServersInitializer

/**
 * 进程启动时做一次性配置初始化（与 UI 按钮无关）。
 * 仅当本地对应配置文件不存在时写入默认值。
 */
class DnsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DnsServersInitializer.ensureInitialized(this)
        BlockRulesInitializer.ensureInitialized(this)
    }
}
