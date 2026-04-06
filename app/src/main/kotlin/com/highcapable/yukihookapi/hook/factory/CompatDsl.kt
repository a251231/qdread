package com.highcapable.yukihookapi.hook.factory

import android.app.Activity
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.HookCompatRuntime
import com.highcapable.yukihookapi.hook.param.HookParam
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

enum class MembersType {
    METHOD,
    CONSTRUCTOR,
    ALL,
}

private fun normalizeType(type: Any?): String? = when (type) {
    null -> null
    is Class<*> -> when (type) {
        java.lang.Void.TYPE -> "void"
        java.lang.Integer.TYPE -> "int"
        java.lang.Long.TYPE -> "long"
        java.lang.Boolean.TYPE -> "boolean"
        java.lang.Float.TYPE -> "float"
        java.lang.Double.TYPE -> "double"
        java.lang.Short.TYPE -> "short"
        java.lang.Byte.TYPE -> "byte"
        java.lang.Character.TYPE -> "char"
        else -> type.name
    }

    is String -> type
    else -> type.toString()
}

private fun Class<*>.allSearchTypes(includeSuper: Boolean): Sequence<Class<*>> =
    if (!includeSuper) sequenceOf(this) else generateSequence(this) { it.superclass }

fun String.toClass(): Class<*> = Class.forName(this, false, HookCompatRuntime.currentClassLoader())

fun String.toClassOrNull(): Class<*>? = runCatching { toClass() }.getOrNull()

fun lazyClassOrNull(name: String): Lazy<Class<*>?> = lazy { name.toClassOrNull() }

fun <T> T.current(block: T.() -> Unit): T =
    apply { HookCompatRuntime.withReceiver(this) { block() } }

fun Any.superClass(): Class<*> = javaClass.superclass ?: javaClass

fun Class<*>.constructor(block: ConstructorRule.() -> Unit = {}): ExecutableSelector {
    val rule = ConstructorRule().apply(block)
    return ExecutableSelector(this, rule.includeSuperClass, null) {
        it.declaredConstructors.filter(rule::matches)
    }
}

fun Any.constructor(block: ConstructorRule.() -> Unit = {}): ExecutableSelector =
    javaClass.constructor(block).withDefaultTarget(this)

fun Class<*>.method(block: MethodRule.() -> Unit = {}): ExecutableSelector {
    val rule = MethodRule().apply(block)
    return ExecutableSelector(this, rule.includeSuperClass, null) { type ->
        type.declaredMethods.filter(rule::matches)
    }
}

fun Any.method(block: MethodRule.() -> Unit = {}): ExecutableSelector =
    javaClass.method(block).withDefaultTarget(this)

fun Activity.registerModuleAppActivities() = Unit

open class MemberRule {
    var includeSuperClass: Boolean = false
        private set
    var paramCount: Int? = null
    var paramTypes: List<Any?>? = null
    var returnType: Any? = null
    var name: String? = null
    private var nameConditions: MutableList<(String) -> Boolean>? = null
    var modifiers: Int? = null

    fun superClass() {
        includeSuperClass = true
    }

    fun paramCount(count: Int) {
        paramCount = count
    }

    fun emptyParam() {
        paramCount = 0
        paramTypes = emptyList()
    }

    fun param(vararg types: Any?) {
        paramTypes = types.toList()
        paramCount = types.size
    }

    fun name(condition: (String) -> Boolean) {
        if (nameConditions == null) {
            nameConditions = mutableListOf()
        }
        nameConditions?.add(condition)
    }

    protected fun matchesCommon(executable: Executable): Boolean {
        if (name != null && executable.name != name) return false
        if (nameConditions?.all { it(executable.name) } == false) return false
        if (paramCount != null && executable.parameterCount != paramCount) return false
        if (modifiers != null && executable.modifiers and modifiers!! != modifiers!!) return false
        if (paramTypes != null) {
            val actual = executable.parameterTypes.map(::normalizeType)
            val expected = paramTypes!!.map(::normalizeType)
            if (actual != expected) return false
        }
        return true
    }
}

class MethodRule : MemberRule() {
    fun matches(method: Method): Boolean {
        if (!matchesCommon(method)) return false
        val expected = normalizeType(returnType)
        return expected == null || normalizeType(method.returnType) == expected
    }
}

class ConstructorRule : MemberRule() {
    fun matches(constructor: Constructor<*>): Boolean = matchesCommon(constructor)
}

class ExecutableSelector internal constructor(
    private val owner: Class<*>,
    private val includeSuperClass: Boolean,
    private val defaultTarget: Any?,
    private val resolver: (Class<*>) -> List<Executable>,
) {

    fun withDefaultTarget(target: Any?) =
        ExecutableSelector(owner, includeSuperClass, target, resolver)

    fun giveAll(): List<Executable> {
        return owner.allSearchTypes(includeSuperClass)
            .flatMap { resolver(it).asSequence() }
            .distinctBy(::memberSignature)
            .toList()
    }

    fun hook(): HookRegistrationBuilder = HookRegistrationBuilder(giveAll())

    fun hook(block: HookRegistrationBuilder.() -> HookHandle): HookHandle = hook().block()

    fun hookAll(): HookRegistrationBuilder = HookRegistrationBuilder(giveAll())

    fun get(target: Any? = defaultTarget ?: HookCompatRuntime.currentReceiver()): BoundExecutable =
        BoundExecutable(firstOrThrow(), target)

    fun call(vararg args: Any?): Any? =
        BoundExecutable(firstOrThrow(), defaultTarget ?: HookCompatRuntime.currentReceiver()).call(*args)

    private fun firstOrNull(): Executable? = giveAll().firstOrNull()?.apply { isAccessible = true }

    private fun firstOrThrow(): Executable =
        firstOrNull() ?: error("Cannot resolve executable on ${owner.name}")

    fun int(vararg args: Any?): Int = (call(*args) as? Int) ?: 0
}

fun Collection<Executable>.hookAll(block: HookRegistrationBuilder.() -> HookHandle): HookHandle =
    HookRegistrationBuilder(toList()).block()

class BoundExecutable internal constructor(
    private val executable: Executable,
    private val target: Any?,
) {

    fun call(vararg args: Any?): Any? {
        executable.isAccessible = true
        return when (executable) {
            is Method -> executable.invoke(
                if (Modifier.isStatic(executable.modifiers)) null else target,
                *args
            )

            is Constructor<*> -> executable.newInstance(*args)
            else -> null
        }
    }
}

class HookHandle internal constructor(
    private val delegates: List<Any?> = emptyList(),
) {
    fun remove() {
        delegates.forEach { delegate ->
            if (delegate == null) return@forEach
            delegate::class.java.methods
                .firstOrNull { it.parameterCount == 0 && it.name in setOf("remove", "close", "unhook") }
                ?.invoke(delegate)
        }
    }
}

class HookRegistrationBuilder internal constructor(
    private val executables: List<Executable>,
) {

    fun before(block: HookParam.() -> Unit): HookHandle =
        register { chain, executable ->
            val param = HookParam(executable, chainThisObject(chain), chainArgs(chain))
            block(param)
            chainProceed(chain, param.args)
        }

    fun after(block: HookParam.() -> Unit): HookHandle =
        register { chain, executable ->
            val mutableArgs = chainArgs(chain)
            val initialResult = chainProceed(chain, mutableArgs)
            val param = HookParam(
                method = executable,
                instanceValue = chainThisObject(chain),
                mutableArgs = mutableArgs,
                initialResult = initialResult
            )
            block(param)
            param.result
        }

    fun intercept(): HookHandle = replaceAny {
        defaultReturnValue(method)
    }

    fun replaceTo(value: Any?): HookHandle = replaceAny { value }

    fun replaceToFalse(): HookHandle = replaceAny { false }

    fun replaceUnit(block: HookParam.() -> Unit): HookHandle = replaceAny {
        block()
        null
    }

    fun replaceAny(block: HookParam.() -> Any?): HookHandle =
        register { chain, executable ->
            val param = HookParam(executable, chainThisObject(chain), chainArgs(chain))
            param.result = block(param)
            param.result
        }

    private fun register(
        block: (chain: Any, executable: Executable) -> Any?,
    ): HookHandle {
        val module = HookCompatRuntime.requireModule()
        val delegates = executables.map { executable ->
            executable.isAccessible = true
            module.hook(executable).intercept { chain ->
                runCatching {
                    block(chain, executable)
                }.getOrElse { throwable ->
                    YLog.error(
                        msg = "hook callback failed: ${memberSignature(executable)} -> ${throwable.message}",
                        tag = YLog.Configs.tag
                    )
                    chainProceed(chain, chainArgs(chain))
                }
            }
        }
        return HookHandle(delegates)
    }
}

private fun chainArgs(chain: Any): MutableList<Any?> {
    val getter = chain::class.java.methods.firstOrNull {
        it.parameterCount == 0 && (it.name == "getArgs" || it.name == "args")
    }
    @Suppress("UNCHECKED_CAST")
    return when (val value = getter?.invoke(chain)) {
        is MutableList<*> -> value as MutableList<Any?>
        is List<*> -> value.toMutableList() as MutableList<Any?>
        else -> mutableListOf()
    }
}

private fun chainThisObject(chain: Any): Any? =
    chain::class.java.methods.firstOrNull { it.parameterCount == 0 && it.name == "getThisObject" }
        ?.invoke(chain)

private fun chainProceed(chain: Any, args: List<Any?>): Any? {
    val proceed = chain::class.java.methods.firstOrNull { it.name == "proceed" }
        ?: error("Cannot find proceed() on modern chain ${chain::class.java.name}")
    return when (proceed.parameterCount) {
        0 -> proceed.invoke(chain)
        else -> {
            val parameterType = proceed.parameterTypes.firstOrNull()
            when {
                parameterType == null -> proceed.invoke(chain)
                List::class.java.isAssignableFrom(parameterType) -> proceed.invoke(chain, args)
                parameterType.isArray -> proceed.invoke(chain, args.toTypedArray())
                else -> proceed.invoke(chain, args)
            }
        }
    }
}

private fun defaultReturnValue(executable: Executable): Any? {
    val returnType = (executable as? Method)?.returnType ?: Void.TYPE
    return when (returnType) {
        java.lang.Void.TYPE -> null
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }
}

private fun memberSignature(member: Member): String = when (member) {
    is Method -> buildString {
        append(member.declaringClass.name)
        append('#')
        append(member.name)
        append(member.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name })
        append(':')
        append(member.returnType.name)
    }

    is Constructor<*> -> buildString {
        append(member.declaringClass.name)
        append("#<init>")
        append(member.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name })
    }

    else -> member.toString()
}
