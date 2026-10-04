package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.ExecutionId
import com.yagay.yauto.core.model.resolveVariables
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal object TaskerJavaObjectStore {
    private data class Bucket(
        val values: ConcurrentHashMap<String, Any> = ConcurrentHashMap(),
        @Volatile var touchedAt: Long = System.currentTimeMillis(),
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val globals = ConcurrentHashMap<String, Any>()

    fun get(executionId: ExecutionId, name: String): Any? {
        val normalized = name.trim()
        if (isGlobal(normalized)) return globals[normalized]
        return buckets[executionId.value]
            ?.also { it.touchedAt = System.currentTimeMillis() }
            ?.values
            ?.get(normalized)
    }

    fun put(executionId: ExecutionId, name: String, value: Any?) {
        val normalized = name.trim()
        if (normalized.isBlank() || value == null) return
        if (isGlobal(normalized)) {
            globals[normalized] = value
            return
        }
        prune()
        val bucket = buckets.computeIfAbsent(executionId.value) { Bucket() }
        bucket.touchedAt = System.currentTimeMillis()
        bucket.values[normalized] = value
    }

    fun snapshot(executionId: ExecutionId): Map<String, Any> = buildMap {
        putAll(globals)
        buckets[executionId.value]
            ?.also { it.touchedAt = System.currentTimeMillis() }
            ?.values
            ?.let(::putAll)
    }

    fun remove(executionId: ExecutionId, name: String): Boolean {
        val normalized = name.trim()
        if (normalized.isBlank()) return false
        return if (isGlobal(normalized)) {
            globals.remove(normalized) != null
        } else {
            buckets[executionId.value]?.values?.remove(normalized) != null
        }
    }

    fun clear(executionId: ExecutionId) {
        buckets.remove(executionId.value)
    }

    fun clearGlobals() {
        globals.clear()
    }

    private fun isGlobal(name: String): Boolean =
        name.any(Char::isUpperCase)

    private fun prune() {
        if (buckets.size < 128) return
        val cutoff = System.currentTimeMillis() - 30L * 60L * 1_000L
        buckets.entries.removeIf { it.value.touchedAt < cutoff }
    }
}

internal class TaskerJavaFunctionExecutor(private val context: Context) {
    suspend fun execute(
        feature: com.yagay.yauto.core.model.FeatureRef,
        ctx: FeatureExecutionContext,
    ): ActionExecutionResult {
        val targetRaw = feature.config.string("target").resolveVariables(ctx.variables).trim()
        val signature = feature.config.string("signature").resolveVariables(ctx.variables).trim()
        if (targetRaw.isBlank() || signature.isBlank()) return ActionExecutionResult(false)

        val target = resolveTarget(targetRaw, ctx) ?: return ActionExecutionResult(
            false,
            message = userText("feature.java_target_unresolved", targetRaw),
        )
        val parameterCount = signatureParameterCount(signature).coerceIn(0, 10)
        val args = (0 until parameterCount).map { index ->
            val raw = (feature.config["arg" + index] as? ConfigValue.StringValue)?.value.orEmpty()
            resolveArgument(raw.resolveVariables(ctx.variables), ctx)
        }

        val result = runCatching { invoke(target, signature, args) }.getOrElse {
            return ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
        }

        val resultTarget = normalizeResultTarget(feature.config.string("resultTarget"))
        if (resultTarget.isNotBlank()) {
            if (resultTarget.startsWith("%")) {
                ctx.variables.set(resultTarget, javaToConfig(result))
            } else if (result != null) {
                TaskerJavaObjectStore.put(ctx.executionId, resultTarget, result)
            }
        }
        return ActionExecutionResult(true, javaToConfig(result))
    }

    private fun resolveTarget(raw: String, ctx: FeatureExecutionContext): Any? {
        val clean = raw.trim()
        if (clean.equals("CONTEXT", true) || clean.equals("context", true)) return context
        TaskerJavaObjectStore.get(ctx.executionId, clean)?.let { return it }
        resolveClass(clean)?.let { return it }
        return null
    }

    private fun resolveClass(raw: String): Class<*>? {
        val normalized = raw
            .removePrefix("class ")
            .replace(Regex("^\\([^)]*\\)\\s*"), "")
            .trim()
        if (normalized.isBlank()) return null
        val candidates = buildList {
            add(normalized)
            if (!normalized.contains('.')) {
                add("java.lang.$normalized")
                add("java.util.$normalized")
                add("android.app.$normalized")
                add("android.content.$normalized")
                add("android.provider.Settings\$$normalized")
                add("android.net.$normalized")
                add("android.location.$normalized")
                add("android.hardware.$normalized")
                add("android.telephony.$normalized")
                add("android.net.wifi.$normalized")
                add("android.bluetooth.$normalized")
                add("android.media.$normalized")
                add("android.os.$normalized")
                add("android.view.$normalized")
                add("android.widget.$normalized")
            }
        }
        return candidates.firstNotNullOfOrNull { name ->
            runCatching { Class.forName(name, true, context.classLoader) }.getOrNull()
        }
    }

    private fun resolveArgument(raw: String, ctx: FeatureExecutionContext): Any? {
        val value = raw.trim()
        if (value.isEmpty() || value.equals("null", true)) return null
        if (value.equals("CONTEXT", true) || value.equals("context", true)) return context
        TaskerJavaObjectStore.get(ctx.executionId, value)?.let { return it }
        ctx.variables.get(value)?.let { return configToJava(it) }
        if (value.equals("true", true)) return true
        if (value.equals("false", true)) return false
        value.toIntOrNull()?.let { return it }
        value.toLongOrNull()?.let { return it }
        value.toDoubleOrNull()?.let { return it }
        if (
            (value.startsWith('"') && value.endsWith('"')) ||
            (value.startsWith('\'') && value.endsWith('\''))
        ) return value.substring(1, value.length - 1)
        return value
    }

    private fun signatureParameterCount(signature: String): Int {
        val raw = signature.substringAfterLast('(', "").substringBeforeLast(')', "")
        if (raw.isBlank()) return 0
        var depth = 0
        var count = 1
        raw.forEach { ch ->
            when (ch) {
                '<', '[', '(' -> depth++
                '>', ']', ')' -> if (depth > 0) depth--
                ',' -> if (depth == 0) count++
            }
        }
        return count
    }

    private fun invoke(target: Any, signature: String, args: List<Any?>): Any? {
        val methodName = signature.substringBefore('{').substringBefore('(').trim()
            .substringAfterLast(' ')
            .ifBlank { signature.substringBefore('(').trim() }

        if (methodName.equals("new", true) || signature.trimStart().startsWith("new ")) {
            val clazz = target as? Class<*> ?: error("Constructor target is not a Class")
            val ctor = bestConstructor(clazz.constructors.toList(), args)
                ?: error("No matching constructor for ${clazz.name}")
            return ctor.newInstance(*coerceArguments(ctor.parameterTypes, args))
        }

        val clazz = if (target is Class<*>) target else target.javaClass
        val methods = (clazz.methods.asList() + clazz.declaredMethods.asList())
            .distinctBy { it.toGenericString() }
            .filter { it.name == methodName && it.parameterCount == args.size }

        val method = bestMethod(methods, args)
            ?: error("No matching Java method ${clazz.name}.$methodName/${args.size}")
        method.isAccessible = true
        val receiver = if (Modifier.isStatic(method.modifiers)) null else {
            if (target is Class<*>) error("Instance required for ${method.name}") else target
        }
        return method.invoke(receiver, *coerceArguments(method.parameterTypes, args))
    }

    private fun bestMethod(methods: List<Method>, args: List<Any?>): Method? =
        methods.mapNotNull { method ->
            compatibilityScore(method.parameterTypes, args)?.let { score -> method to score }
        }.minByOrNull { it.second }?.first

    private fun bestConstructor(
        constructors: List<Constructor<*>>,
        args: List<Any?>,
    ): Constructor<*>? =
        constructors.mapNotNull { ctor ->
            compatibilityScore(ctor.parameterTypes, args)?.let { score -> ctor to score }
        }.minByOrNull { it.second }?.first

    private fun compatibilityScore(types: Array<Class<*>>, args: List<Any?>): Int? {
        var score = 0
        for (index in types.indices) {
            val type = wrap(types[index])
            val arg = args[index]
            if (arg == null) {
                if (types[index].isPrimitive) return null
                score += 4
                continue
            }
            val actual = arg.javaClass
            when {
                type.isAssignableFrom(actual) -> score += if (type == actual) 0 else 1
                Number::class.java.isAssignableFrom(type) && arg is Number -> score += 2
                type == java.lang.Boolean::class.java && arg is Boolean -> Unit
                type == java.lang.Character::class.java && arg is CharSequence && arg.length == 1 -> score += 2
                type == String::class.java -> score += 3
                else -> return null
            }
        }
        return score
    }

    private fun coerceArguments(types: Array<Class<*>>, args: List<Any?>): Array<Any?> =
        Array(types.size) { index -> coerce(types[index], args[index]) }

    private fun coerce(type: Class<*>, value: Any?): Any? {
        if (value == null) return null
        val wrapped = wrap(type)
        if (wrapped.isInstance(value)) return value
        if (value is Number) return when (wrapped) {
            java.lang.Byte::class.java -> value.toByte()
            java.lang.Short::class.java -> value.toShort()
            java.lang.Integer::class.java -> value.toInt()
            java.lang.Long::class.java -> value.toLong()
            java.lang.Float::class.java -> value.toFloat()
            java.lang.Double::class.java -> value.toDouble()
            else -> value
        }
        if (wrapped == java.lang.Boolean::class.java && value is String) {
            return value.equals("true", true) || value == "1"
        }
        if (wrapped == java.lang.Character::class.java && value is CharSequence && value.isNotEmpty()) {
            return value[0]
        }
        if (wrapped == String::class.java) return value.toString()
        return value
    }

    private fun wrap(type: Class<*>): Class<*> = when (type) {
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

    private fun normalizeResultTarget(raw: String): String =
        raw.trim().replace(Regex("^\\([^)]*\\)\\s*"), "")

    private fun configToJava(value: ConfigValue): Any? = when (value) {
        ConfigValue.NullValue -> null
        is ConfigValue.StringValue -> value.value
        is ConfigValue.NumberValue -> value.value
        is ConfigValue.BooleanValue -> value.value
        is ConfigValue.ListValue -> value.value.map(::configToJava)
        is ConfigValue.ObjectValue -> value.value.mapValues { configToJava(it.value) }
    }

    private fun javaToConfig(value: Any?): ConfigValue = when (value) {
        null -> ConfigValue.NullValue
        is ConfigValue -> value
        is Boolean -> ConfigValue.BooleanValue(value)
        is Number -> ConfigValue.NumberValue(value.toDouble())
        is CharSequence -> ConfigValue.StringValue(value.toString())
        is Map<*, *> -> ConfigValue.ObjectValue(
            value.entries.associate { (key, item) -> key.toString() to javaToConfig(item) }
        )
        is Iterable<*> -> ConfigValue.ListValue(value.map(::javaToConfig))
        is Array<*> -> ConfigValue.ListValue(value.map(::javaToConfig))
        else -> ConfigValue.StringValue(value.toString())
    }
}
