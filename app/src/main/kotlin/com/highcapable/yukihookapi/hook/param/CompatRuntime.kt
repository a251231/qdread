package com.highcapable.yukihookapi.hook.param

import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

object HookCompatRuntime {
    var module: XposedModule? = null
    var packageParam: PackageParam? = null
    var applicationContext: Context? = null

    private val currentReceiver = ThreadLocal<Any?>()

    fun requireModule(): XposedModule = module ?: error("Modern XposedModule is not initialized")

    fun currentClassLoader(): ClassLoader {
        return packageParam?.classLoader
            ?: applicationContext?.javaClass?.classLoader
            ?: Thread.currentThread().contextClassLoader
            ?: HookCompatRuntime::class.java.classLoader
            ?: error("Cannot resolve current class loader")
    }

    fun currentReceiver(): Any? = currentReceiver.get()

    fun <T> withReceiver(receiver: Any?, block: () -> T): T {
        val previous = currentReceiver.get()
        currentReceiver.set(receiver)
        return try {
            block()
        } finally {
            currentReceiver.set(previous)
        }
    }
}

class PackageParam(
    val packageName: String,
    val classLoader: ClassLoader,
    val appInfo: ApplicationInfo,
    val processName: String = packageName,
) {

    fun <T> withRuntime(block: PackageParam.() -> T): T {
        val previous = HookCompatRuntime.packageParam
        HookCompatRuntime.packageParam = this
        return try {
            block()
        } finally {
            HookCompatRuntime.packageParam = previous
        }
    }

    inline fun loadApp(name: String, crossinline block: PackageParam.() -> Unit) {
        if (packageName == name) {
            withRuntime { block(this) }
        }
    }

    fun onAppLifecycle(block: AppLifecycleBuilder.() -> Unit) {
        AppLifecycleBuilder(HookCompatRuntime.applicationContext).block()
    }
}

class AppLifecycleBuilder(private val context: Context?) {
    fun onCreate(block: Context.() -> Unit) {
        context?.block()
    }
}

class HookArg internal constructor(
    private val args: MutableList<Any?>,
    private val index: Int,
) {
    fun set(value: Any?) {
        args[index] = value
    }
}

open class HookParam internal constructor(
    val method: Executable,
    private val instanceValue: Any?,
    internal val mutableArgs: MutableList<Any?>,
    initialResult: Any? = null,
) {
    open var result: Any? = initialResult
    val args: MutableList<Any?> get() = mutableArgs
    val instance: Any get() = instanceValue ?: error("Hook instance is null for ${method.name}")
    val instanceOrNull: Any? get() = instanceValue
    val instanceClass: Class<*>? get() = instanceValue?.javaClass ?: method.declaringClass

    fun args(index: Int) = HookArg(mutableArgs, index)

    inline fun <reified T> instance(): T = instance as T
}
