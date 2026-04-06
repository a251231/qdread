package cn.xihan.qdds

import com.highcapable.yukihookapi.hook.factory.HookHandle
import com.highcapable.yukihookapi.hook.factory.HookRegistrationBuilder
import com.highcapable.yukihookapi.hook.factory.hookAll as compatHookAll
import com.highcapable.yukihookapi.hook.factory.lazyClassOrNull as compatLazyClassOrNull
import com.highcapable.yukihookapi.hook.factory.superClass as compatSuperClass
import com.highcapable.yukihookapi.hook.factory.toClass as compatToClass
import com.highcapable.yukihookapi.hook.factory.toClassOrNull as compatToClassOrNull
import java.lang.reflect.Executable

fun String.toClass(): Class<*> = run { compatToClass() }

fun String.toClassOrNull(): Class<*>? = run { compatToClassOrNull() }

fun lazyClassOrNull(name: String): Lazy<Class<*>?> = compatLazyClassOrNull(name)

fun Any.superClass(): Class<*> = run { compatSuperClass() }

fun Collection<Executable>.hookAll(
    block: HookRegistrationBuilder.() -> HookHandle
): HookHandle = run { compatHookAll(block) }
