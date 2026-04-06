package com.highcapable.yukihookapi.hook.log

import android.util.Log

object YLog {

    object Configs {
        var tag: String = "YukiHook"
        var isEnable: Boolean = true
    }

    fun info(msg: String, tag: String = Configs.tag) {
        if (Configs.isEnable) {
            Log.i(tag, msg)
        }
    }

    fun error(msg: String, tag: String = Configs.tag) {
        if (Configs.isEnable) {
            Log.e(tag, msg)
        }
    }
}
