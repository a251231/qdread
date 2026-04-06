package com.highcapable.yukihookapi.hook.param

import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

object HookCompatRuntime {
    var module: XposedModule? = null
    var packageParam: PackageParam? = null
    var applicationContext: Context? = null

    private val currentModule = ThreadLocal<XposedModule?>()
    private val currentPackageParam = ThreadLocal<PackageParam?>()
    private val currentApplicationContext = ThreadLocal<Context?>()
    private val currentReceiver = ThreadLocal<Any?>()

    data class RuntimeSnapshot(
        val module: XposedModule?,
        val packageParam: PackageParam?,
        val applicationContext: Context?,
    )

    fun install(
        module: XposedModule,
        packageParam: PackageParam,
        applicationContext: Context?,
    ) {
        this.module = module
        this.packageParam = packageParam
        this.applicationContext = applicationContext
    }

    fun snapshot(): RuntimeSnapshot = RuntimeSnapshot(
        module = currentModule(),
        packageParam = currentPackageParam(),
        applicationContext = currentApplicationContext()
    )

    fun requireModule(): XposedModule =
        currentModule() ?: error("Modern XposedModule is not initialized")

    fun currentModule(): XposedModule? = currentModule.get() ?: module

    fun currentPackageParam(): PackageParam? = currentPackageParam.get() ?: packageParam

    fun currentApplicationContext(): Context? = currentApplicationContext.get() ?: applicationContext

    fun currentClassLoader(): ClassLoader {
        return currentPackageParam()?.classLoader
            ?: currentApplicationContext()?.javaClass?.classLoader
            ?: Thread.currentThread().contextClassLoader
            ?: HookCompatRuntime::class.java.classLoader
            ?: error("Cannot resolve current class loader")
    }

    fun currentReceiver(): Any? = currentReceiver.get()

    fun <T> withRuntime(
        module: XposedModule? = currentModule(),
        packageParam: PackageParam? = currentPackageParam(),
        applicationContext: Context? = currentApplicationContext(),
        receiver: Any? = currentReceiver(),
        block: () -> T,
    ): T {
        val previousModule = currentModule.get()
        val previousPackageParam = currentPackageParam.get()
        val previousApplicationContext = currentApplicationContext.get()
        val previousReceiver = currentReceiver.get()
        currentModule.set(module)
        currentPackageParam.set(packageParam)
        currentApplicationContext.set(applicationContext)
        currentReceiver.set(receiver)
        return try {
            block()
        } finally {
            currentModule.set(previousModule)
            currentPackageParam.set(previousPackageParam)
            currentApplicationContext.set(previousApplicationContext)
            currentReceiver.set(previousReceiver)
        }
    }

    fun <T> withSnapshot(
        snapshot: RuntimeSnapshot,
        receiver: Any?,
        block: () -> T,
    ): T = withRuntime(
        module = snapshot.module,
        packageParam = snapshot.packageParam,
        applicationContext = snapshot.applicationContext,
        receiver = receiver,
        block = block
    )

    fun <T> withReceiver(receiver: Any?, block: () -> T): T {
        return withRuntime(receiver = receiver, block = block)
    }
}

class PackageParam(
    val packageName: String,
    val classLoader: ClassLoader,
    val appInfo: ApplicationInfo,
    val processName: String = packageName,
) {
    private val onCreateCallbacks = mutableListOf<Context.() -> Unit>()
    private var createdContext: Context? = null

    fun <T> withRuntime(block: PackageParam.() -> T): T {
        return HookCompatRuntime.withRuntime(
            packageParam = this,
            applicationContext = createdContext ?: HookCompatRuntime.currentApplicationContext()
        ) {
            block()
        }
    }

    inline fun loadApp(name: String, crossinline block: PackageParam.() -> Unit) {
        if (packageName == name) {
            withRuntime { block(this) }
        }
    }

    fun onAppLifecycle(block: AppLifecycleBuilder.() -> Unit) {
        AppLifecycleBuilder(
            onCreate = { callback ->
                val context = createdContext
                if (context != null) {
                    HookCompatRuntime.withRuntime(
                        packageParam = this,
                        applicationContext = context,
                        receiver = null
                    ) {
                        context.callback()
                    }
                } else {
                    onCreateCallbacks += callback
                }
            }
        ).block()
    }

    fun dispatchOnCreate(context: Context) {
        createdContext = context
        val callbacks = onCreateCallbacks.toList()
        onCreateCallbacks.clear()
        if (callbacks.isEmpty()) return
        HookCompatRuntime.withRuntime(
            packageParam = this,
            applicationContext = context,
            receiver = null
        ) {
            callbacks.forEach { context.it() }
        }
    }
}

class AppLifecycleBuilder(
    private val onCreate: (Context.() -> Unit) -> Unit,
) {
    fun onCreate(block: Context.() -> Unit) {
        onCreate(block)
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
