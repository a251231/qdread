package com.highcapable.yukihookapi.hook.xposed.parasitic.activity.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

open class ModuleAppCompatActivity : AppCompatActivity() {

    open val moduleTheme: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        moduleTheme?.let(::setTheme)
        super.onCreate(savedInstanceState)
    }
}
