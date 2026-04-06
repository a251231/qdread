package cn.xihan.qdds

import android.app.Application
import android.content.Context
import android.util.Log
import com.highcapable.yukihookapi.hook.param.HookCompatRuntime
import com.highcapable.yukihookapi.hook.param.PackageParam
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val modernBootstrapFeature = HookFeatureId.named(
    key = "modern.bootstrap",
    displayName = "Modern API 101 引导"
)

private object ModernHookState {
    val attachHooks = ConcurrentHashMap<String, AtomicBoolean>()
    val onCreateHooks = ConcurrentHashMap<String, AtomicBoolean>()
    val installedProcesses = ConcurrentHashMap<String, AtomicBoolean>()
    val packageParams = ConcurrentHashMap<String, PackageParam>()
}

class ModernHookEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "framework=$frameworkName($frameworkVersionCode) api=$apiVersion process=${param.processName}"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != qdPackageName()) return

        HookDiagnostics.beginSession(
            packageName = param.packageName,
            versionCode = 0,
            mode = "modern101",
            processName = "pending"
        )

        trackHookFeature(modernBootstrapFeature) {
            installApplicationBootstrap(packageName = param.packageName)
            installApplicationCreateHook(packageName = param.packageName)
        }
    }

    private fun installHooks(packageParam: PackageParam, context: Context) {
        val installKey = processKey(packageParam.packageName, packageParam.processName)
        val installed = ModernHookState.installedProcesses.computeIfAbsent(installKey) {
            AtomicBoolean(false)
        }
        if (!installed.compareAndSet(false, true)) return
        val systemContext = getSystemContext()
        val installedParam = PackageParam(
            packageName = packageParam.packageName,
            classLoader = packageParam.classLoader,
            appInfo = systemContext.packageManager.getApplicationInfo(packageParam.packageName, 0),
            processName = packageParam.processName
        )
        ModernHookState.packageParams[installKey] = installedParam
        HookCompatRuntime.install(
            module = this,
            packageParam = installedParam,
            applicationContext = context.applicationContext ?: context
        )
        val entry = HookEntry()
        HookCompatRuntime.withRuntime(
            module = this,
            packageParam = installedParam,
            applicationContext = context.applicationContext ?: context,
            receiver = null
        ) {
            entry.onInit()
            entry.onHook()
        }
        log(Log.INFO, TAG, "modern hooks installed for $installKey")
    }

    private fun installApplicationBootstrap(packageName: String) {
        val installed = ModernHookState.attachHooks.computeIfAbsent(packageName) {
            AtomicBoolean(false)
        }
        if (!installed.compareAndSet(false, true)) return

        val attachMethod = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hookAfter(attachMethod) { _, args, _ ->
            val context = args.firstOrNull().safeCast<Context>() ?: return@hookAfter
            if (context.packageName != packageName) return@hookAfter
            val currentProcessName = processName(context)
            if (currentProcessName != packageName) {
                log(Log.INFO, TAG, "skip non-main process: $currentProcessName")
                return@hookAfter
            }

            runCatching {
                val packageParam = PackageParam(
                    packageName = packageName,
                    classLoader = context.classLoader ?: context.javaClass.classLoader
                    ?: HookCompatRuntime.currentClassLoader(),
                    appInfo = context.applicationInfo,
                    processName = currentProcessName
                )
                HookCompatRuntime.install(
                    module = this,
                    packageParam = packageParam,
                    applicationContext = context.applicationContext ?: context
                )
                installHooks(packageParam = packageParam, context = context)
                HookDiagnostics.beginSession(
                    packageName = packageName,
                    versionCode = context.getVersionCode(packageName),
                    mode = "modern101",
                    processName = currentProcessName
                )
                log(Log.INFO, TAG, "modern bootstrap attached for ${processKey(packageName, currentProcessName)}")
            }.onFailure {
                HookDiagnostics.markThrowable(modernBootstrapFeature, it)
                log(Log.ERROR, TAG, "modern bootstrap failed: ${it.message}")
            }
        }
    }

    private fun installApplicationCreateHook(packageName: String) {
        val installed = ModernHookState.onCreateHooks.computeIfAbsent(packageName) {
            AtomicBoolean(false)
        }
        if (!installed.compareAndSet(false, true)) return

        val onCreateMethod = Application::class.java.getDeclaredMethod("onCreate")
        hookAfter(onCreateMethod) { thisObject, _, _ ->
            val application = thisObject.safeCast<Application>() ?: return@hookAfter
            if (application.packageName != packageName) return@hookAfter
            val currentProcessName = processName(application)
            if (currentProcessName != packageName) return@hookAfter
            val installKey = processKey(packageName, currentProcessName)
            val packageParam = ModernHookState.packageParams[installKey] ?: return@hookAfter
            HookCompatRuntime.withRuntime(
                module = this,
                packageParam = packageParam,
                applicationContext = application,
                receiver = application
            ) {
                packageParam.dispatchOnCreate(application)
                HookDiagnostics.beginSession(
                    packageName = packageName,
                    versionCode = application.getVersionCode(packageName),
                    mode = "modern101",
                    processName = currentProcessName
                )
            }
            log(Log.INFO, TAG, "application onCreate dispatched for $installKey")
        }
    }

    private fun hookAfter(
        member: Executable,
        block: (thisObject: Any?, args: List<Any?>, result: Any?) -> Unit,
    ) {
        hook(member).intercept { chain ->
            val result = chain.proceed()
            block(chain.getThisObject(), chain.args, result)
            result
        }
    }

    private fun qdPackageName(): String =
        Option.targetPackageName()

    private fun processName(context: Context): String =
        runCatching { Application.getProcessName() }
            .getOrNull()
            ?.ifBlank { context.packageName }
            ?: context.packageName

    private fun processKey(packageName: String, processName: String): String =
        "$packageName@$processName"

    companion object {
        private const val TAG = "QDReadHookModern"
    }
}
