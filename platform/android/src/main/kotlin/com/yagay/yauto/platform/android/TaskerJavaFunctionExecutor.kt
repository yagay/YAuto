package com.yagay.yauto.platform.android

import android.content.Context
import android.net.Uri
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.ExecutionId
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.model.FeatureRef
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal object TaskerJavaObjectStore {
    private val global = ConcurrentHashMap<String, Any>()
    private val local = ConcurrentHashMap<String, ConcurrentHashMap<String, Any>>()

    fun put(executionId: ExecutionId, rawName: String, value: Any?) {
        val name = normalize(rawName)
        if (name.isBlank()) return
        if (value == null) {
            remove(executionId, name)
            return
        }
        if (isGlobal(name)) global[name] = value
        else local.computeIfAbsent(executionId.value) { ConcurrentHashMap() }[name] = value
    }

    fun get(executionId: ExecutionId, rawName: String): Any? {
        val name = normalize(rawName)
        if (name.isBlank()) return null
        return if (isGlobal(name)) global[name] else local[executionId.value]?.get(name)
    }

    fun remove(executionId: ExecutionId, rawName: String): Boolean {
        val name = normalize(rawName)
        if (name.isBlank()) return false
        return if (isGlobal(name)) global.remove(name) != null
        else local[executionId.value]?.remove(name) != null
    }

    fun clear(executionId: ExecutionId) {
        local.remove(executionId.value)?.clear()
    }

    fun clearGlobals() {
        global.clear()
    }

    private fun normalize(raw: String): String = raw.trim().removePrefix("%")
    private fun isGlobal(name: String): Boolean = name.firstOrNull()?.isUpperCase() == true
}

internal class TaskerJavaFunctionExecutor(
    private val context: Context,
) {
    suspend fun execute(feature: FeatureRef, ctx: FeatureExecutionContext): ActionExecutionResult {
        val targetRaw = feature.config.string("target").resolveVariables(ctx.variables).trim()
        val signatureRaw = feature.config.string("signature").resolveVariables(ctx.variables).trim()
        if (targetRaw.isBlank() || signatureRaw.isBlank()) return ActionExecutionResult(false)

        val result = runCatching {
            val target = resolveTarget(targetRaw, ctx)
            val signature = parseSignature(signatureRaw)
            val rawArgs = (0 until 10)
                .map { feature.config.string("arg" + it).resolveVariables(ctx.variables) }
                .take(signature.parameterTypes.size)

            when {
                signature.isConstructor -> {
                    val clazz = target.asClass()
                    val constructor = chooseConstructor(clazz, signature.parameterTypes, rawArgs, ctx)
                    constructor.isAccessible = true
                    val args = convertArguments(constructor.parameterTypes, rawArgs, ctx)
                    constructor.newInstance(*args)
                }
                else -> {
                    val clazz = target.asClass()
                    val method = chooseMethod(clazz, signature.methodName, signature.parameterTypes, rawArgs, ctx)
                    method.isAccessible = true
                    val receiver = if (Modifier.isStatic(method.modifiers)) null else target.instanceOrNull
                        ?: error("Instance method requires an object target")
                    val args = convertArguments(method.parameterTypes, rawArgs, ctx)
                    method.invoke(receiver, *args)
                }
            }
        }

        if (result.isFailure) {
            return ActionExecutionResult(
                false,
                message = userText(
                    "feature.operation_failed",
                    result.exceptionOrNull()?.cause?.message
                        ?: result.exceptionOrNull()?.message
                        ?: "Java Function",
                ),
            )
        }

        val value = result.getOrNull()
        val resultTarget = feature.config.string("resultTarget").resolveVariables(ctx.variables).trim()
        if (resultTarget.isNotBlank()) {
            if (isSimple(value) || resultTarget.startsWith("%")) {
                ctx.variables.set(normalizeVariable(resultTarget), javaToConfig(value))
            } else {
                TaskerJavaObjectStore.put(ctx.executionId, resultTarget, value)
            }
        }
        return ActionExecutionResult(true, javaToConfig(value))
    }

    private data class ResolvedTarget(
        val clazz: Class<*>,
        val instanceOrNull: Any? = null,
    ) {
        fun asClass(): Class<*> = clazz
    }

    private data class ParsedSignature(
        val methodName: String,
        val parameterTypes: List<String>,
        val isConstructor: Boolean,
    )

    private fun resolveTarget(raw: String, ctx: FeatureExecutionContext): ResolvedTarget {
        val normalized = raw.removePrefix("%").trim()
        when (normalized.lowercase()) {
            "context", "yautocontext", "this" -> return ResolvedTarget(context.javaClass, context)
        }
        TaskerJavaObjectStore.get(ctx.executionId, normalized)?.let {
            return ResolvedTarget(it.javaClass, it)
        }

        val className = normalized
            .removePrefix("class ")
            .removeSuffix(".class")
            .trim()
        val clazz = resolveClass(className)
        return ResolvedTarget(clazz, null)
    }

    private fun parseSignature(raw: String): ParsedSignature {
        val value = raw.trim()
        val beforeParen = value.substringBefore('(').trim()
        val methodToken = beforeParen.substringBefore('{').trim()
            .substringAfterLast(' ')
            .trim()
        val isConstructor = methodToken.equals("new", true) || methodToken == "<init>"
        val name = if (isConstructor) "<init>" else methodToken.ifBlank {
            error("Missing method name")
        }
        val paramsText = value.substringAfter('(', "").substringBeforeLast(')', "")
        val params = splitTypes(paramsText)
        return ParsedSignature(name, params, isConstructor)
    }

    private fun splitTypes(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        raw.forEachIndexed { index, ch ->
            when (ch) {
                '<', '[', '(' -> depth++
                '>', ']', ')' -> depth--
                ',' -> if (depth == 0) {
                    out += raw.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        out += raw.substring(start).trim()
        return out.filter(String::isNotBlank)
    }

    private fun chooseMethod(
        clazz: Class<*>,
        name: String,
        declaredTypes: List<String>,
        rawArgs: List<String>,
        ctx: FeatureExecutionContext,
    ): Method {
        val candidates = sequence {
            yieldAll(clazz.methods.asSequence())
            yieldAll(clazz.declaredMethods.asSequence())
        }.filter { it.name == name && it.parameterCount == rawArgs.size }
            .distinctBy { it.toGenericString() }
            .toList()
        if (candidates.isEmpty()) error("Method not found: " + clazz.name + "." + name)

        val exact = candidates.firstOrNull { method ->
            declaredTypes.size == method.parameterCount &&
                method.parameterTypes.indices.all { index ->
                    typeNameMatches(method.parameterTypes[index], declaredTypes[index])
                }
        }
        if (exact != null) return exact

        return candidates.firstOrNull { method ->
            runCatching { convertArguments(method.parameterTypes, rawArgs, ctx) }.isSuccess
        } ?: error("No compatible overload for: " + clazz.name + "." + name)
    }

    private fun chooseConstructor(
        clazz: Class<*>,
        declaredTypes: List<String>,
        rawArgs: List<String>,
        ctx: FeatureExecutionContext,
    ): Constructor<*> {
        val candidates = (clazz.constructors.asSequence() + clazz.declaredConstructors.asSequence())
            .filter { it.parameterCount == rawArgs.size }
            .distinctBy { it.toGenericString() }
            .toList()
        if (candidates.isEmpty()) error("Constructor not found: " + clazz.name)

        val exact = candidates.firstOrNull { constructor ->
            declaredTypes.size == constructor.parameterCount &&
                constructor.parameterTypes.indices.all { index ->
                    typeNameMatches(constructor.parameterTypes[index], declaredTypes[index])
                }
        }
        if (exact != null) return exact

        return candidates.firstOrNull { constructor ->
            runCatching { convertArguments(constructor.parameterTypes, rawArgs, ctx) }.isSuccess
        } ?: error("No compatible constructor for: " + clazz.name)
    }

    private fun typeNameMatches(type: Class<*>, raw: String): Boolean {
        val normalized = raw
            .replace("{", "")
            .replace("}", "")
            .replace("java.lang.", "")
            .replace(" ", "")
            .trim()
        if (normalized.isBlank()) return true
        val typeNames = setOf(
            type.name,
            type.simpleName,
            type.canonicalName.orEmpty(),
            boxed(type).simpleName,
            boxed(type).name,
        ).map {
            it.replace("java.lang.", "").replace(" ", "")
        }.toSet()
        return normalized in typeNames || normalized.substringAfterLast('.') in typeNames
    }

    private fun convertArguments(
        types: Array<Class<*>>,
        rawArgs: List<String>,
        ctx: FeatureExecutionContext,
    ): Array<Any?> =
        Array(types.size) { index -> convertArgument(types[index], rawArgs.getOrElse(index) { "" }, ctx) }

    private fun convertArgument(type: Class<*>, rawInput: String, ctx: FeatureExecutionContext): Any? {
        val raw = rawInput.trim()
        if (raw.equals("null", true)) {
            if (type.isPrimitive) error("null for primitive")
            return null
        }

        TaskerJavaObjectStore.get(ctx.executionId, raw.removePrefix("%"))?.let { stored ->
            if (boxed(type).isAssignableFrom(stored.javaClass)) return stored
        }

        if (type == String::class.java || type == CharSequence::class.java) return rawInput
        if (type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java) {
            return when (raw.lowercase()) {
                "1", "true", "on", "yes" -> true
                "0", "false", "off", "no" -> false
                else -> error("Invalid boolean: " + raw)
            }
        }
        if (type == Byte::class.javaPrimitiveType || type == java.lang.Byte::class.java) return raw.toByte()
        if (type == Short::class.javaPrimitiveType || type == java.lang.Short::class.java) return raw.toShort()
        if (type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java) return raw.toInt()
        if (type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java) return raw.toLong()
        if (type == Float::class.javaPrimitiveType || type == java.lang.Float::class.java) return raw.toFloat()
        if (type == Double::class.javaPrimitiveType || type == java.lang.Double::class.java) return raw.toDouble()
        if (type == Char::class.javaPrimitiveType || type == java.lang.Character::class.java) {
            return raw.singleOrNull() ?: error("Invalid char")
        }
        if (type == Class::class.java) return resolveClass(raw)
        if (type == Uri::class.java) return Uri.parse(raw)
        if (type == Context::class.java || type.isAssignableFrom(context.javaClass)) return context
        if (type.isEnum) {
            @Suppress("UNCHECKED_CAST")
            return java.lang.Enum.valueOf(type as Class<out Enum<*>>, raw)
        }
        if (type.isArray) {
            val parts = if (raw.isBlank()) emptyList() else raw.split(',').map(String::trim)
            val component = type.componentType
            val array = ReflectArray.newInstance(component, parts.size)
            parts.forEachIndexed { index, part ->
                ReflectArray.set(array, index, convertArgument(component, part, ctx))
            }
            return array
        }

        val stringCtor = runCatching { type.getDeclaredConstructor(String::class.java) }.getOrNull()
        if (stringCtor != null) {
            stringCtor.isAccessible = true
            return stringCtor.newInstance(rawInput)
        }
        error("Unsupported argument type: " + type.name)
    }

    private fun resolveClass(raw: String): Class<*> {
        val name = raw.trim()
        PRIMITIVES[name.lowercase()]?.let { return it }
        return runCatching { Class.forName(name, true, context.classLoader) }
            .recoverCatching { Class.forName("java.lang." + name, true, context.classLoader) }
            .getOrElse { throw IllegalArgumentException("Class not found: " + name, it) }
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }

    private fun isSimple(value: Any?): Boolean =
        value == null || value is String || value is CharSequence || value is Number ||
            value is Boolean || value is Char || value is Enum<*>

    private fun normalizeVariable(raw: String): String =
        if (raw.startsWith("%")) raw else "%" + raw

    private fun javaToConfig(value: Any?): ConfigValue = when (value) {
        null -> ConfigValue.NullValue
        is ConfigValue -> value
        is Boolean -> ConfigValue.BooleanValue(value)
        is Number -> ConfigValue.NumberValue(value.toDouble())
        is CharSequence -> ConfigValue.StringValue(value.toString())
        is Char -> ConfigValue.StringValue(value.toString())
        is Enum<*> -> ConfigValue.StringValue(value.name)
        is Map<*, *> -> ConfigValue.ObjectValue(
            value.entries.associate { (key, item) -> key.toString() to javaToConfig(item) }
        )
        is Iterable<*> -> ConfigValue.ListValue(value.map(::javaToConfig))
        is Array<*> -> ConfigValue.ListValue(value.map(::javaToConfig))
        is IntArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is LongArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is FloatArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is DoubleArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it) })
        is BooleanArray -> ConfigValue.ListValue(value.map { ConfigValue.BooleanValue(it) })
        else -> ConfigValue.StringValue(value.toString())
    }

    private companion object {
        val PRIMITIVES = mapOf(
            "boolean" to java.lang.Boolean.TYPE,
            "byte" to java.lang.Byte.TYPE,
            "short" to java.lang.Short.TYPE,
            "int" to java.lang.Integer.TYPE,
            "integer" to java.lang.Integer.TYPE,
            "long" to java.lang.Long.TYPE,
            "float" to java.lang.Float.TYPE,
            "double" to java.lang.Double.TYPE,
            "char" to java.lang.Character.TYPE,
            "string" to String::class.java,
        )
    }
}
