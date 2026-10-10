package com.yagay.yauto.platform.xposed

/** Bounded opt-in primitive snapshots. Never calls unknown object.toString(). */
internal object MethodHookValueSnapshot {
    val allowedKeys: List<String> = (0 until 8).map { "arg$it" } + "result"

    fun capture(args: List<Any?>, result: Any? = null, includeResult: Boolean = false): Map<String, String> =
        buildMap {
            args.take(8).forEachIndexed { index, value ->
                safe(value)?.let { put("arg$index", it) }
            }
            if (includeResult) safe(result)?.let { put("result", it) }
        }

    private fun safe(value: Any?): String? = when (value) {
        null -> "null"
        is String -> value.take(256)
        is Boolean -> value.toString()
        is Byte -> value.toString()
        is Short -> value.toString()
        is Int -> value.toString()
        is Long -> value.toString()
        is Float -> value.takeIf(Float::isFinite)?.toString()
        is Double -> value.takeIf(Double::isFinite)?.toString()
        is Char -> value.toString()
        is Enum<*> -> value.name.take(256)
        else -> null
    }
}
