package cn.xihan.qdds

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Member
import java.util.concurrent.atomic.AtomicBoolean

private val modernBootstrapFeature = HookFeatureId.named(
    key = "modern.bootstrap",
    displayName = "Modern API 101 引导"
)

private object ModernHookState {
    val attachHookInstalled = AtomicBoolean(false)
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
            mode = "modern101"
        )

        trackHookFeature(modernBootstrapFeature) {
            installApplicationBootstrap(packageName = param.packageName)
        }
    }

    private fun installApplicationBootstrap(packageName: String) {
        if (!ModernHookState.attachHookInstalled.compareAndSet(false, true)) return

        val attachMethod = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hookAfter(attachMethod) { _, args, _ ->
            val context = args.firstOrNull().safeCast<Context>() ?: return@hookAfter
            if (context.packageName != packageName) return@hookAfter

            runCatching {
                Option.initialize(context)
                HookDiagnostics.beginSession(
                    packageName = packageName,
                    versionCode = context.getVersionCode(packageName),
                    mode = "modern101"
                )
                log(Log.INFO, TAG, "modern bootstrap attached for $packageName")
            }.onFailure {
                HookDiagnostics.markThrowable(modernBootstrapFeature, it)
                log(Log.ERROR, TAG, "modern bootstrap failed: ${it.message}")
            }
        }
    }

    private fun hookAfter(
        member: Member,
        block: (thisObject: Any?, args: Array<Any?>, result: Any?) -> Unit,
    ) {
        hook(member).intercept { chain ->
            val result = chain.proceed()
            block(chain.getThisObject(), chain.args, result)
            result
        }
    }

    private fun qdPackageName(): String =
        Option.optionEntity.mainOption.packageName.ifBlank { "com.qidian.QDReader" }

    companion object {
        private const val TAG = "QDReadHookModern"
    }
}
