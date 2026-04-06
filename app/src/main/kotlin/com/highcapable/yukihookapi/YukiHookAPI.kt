package com.highcapable.yukihookapi

import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.HookCompatRuntime
import com.highcapable.yukihookapi.hook.param.PackageParam

object YukiHookAPI {

    fun configs(block: YLog.Configs.() -> Unit) {
        YLog.Configs.block()
    }

    inline fun encase(crossinline block: PackageParam.() -> Unit) {
        val packageParam = HookCompatRuntime.packageParam ?: return
        packageParam.withRuntime {
            block(this)
        }
    }
}
