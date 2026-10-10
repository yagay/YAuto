package com.yagay.yauto.platform.xposed

/** Shared low-level, stateless reflection and type conversion for Xposed hooks. */

internal fun reflectedString(target: Any?, vararg names: String): String =
        reflectedValue(target, *names)?.toString().orEmpty()

internal fun reflectedInt(target: Any?, vararg names: String): Int =
        when (val value = reflectedValue(target, *names)) {
            is Int -> value
            is Number -> value.toInt()
            else -> -1
        }

internal fun reflectedValue(target: Any?, vararg names: String): Any? {
        if (target == null) return null
        var type: Class<*>? = target.javaClass
        while (type != null) {
            for (name in names) {
                val field = runCatching { type.getDeclaredField(name) }.getOrNull() ?: continue
                field.isAccessible = true
                return runCatching { field.get(target) }.getOrNull()
            }
            type = type.superclass
        }
        for (name in names) {
            val getter = target.javaClass.methods.firstOrNull {
                it.parameterCount == 0 &&
                    (it.name.equals(name, true) || it.name.equals("get" + name.replaceFirstChar(Char::uppercase), true))
            } ?: continue
            return runCatching { getter.invoke(target) }.getOrNull()
        }
        return null
    }



internal fun mediaCollection(uri: android.net.Uri?): String {
        val text = uri?.toString().orEmpty().lowercase()
        return when {
            "/images/" in text || text.endsWith("/images") -> "images"
            "/video/" in text || text.endsWith("/video") -> "video"
            "/audio/" in text || text.endsWith("/audio") -> "audio"
            else -> "files"
        }
    }



internal fun parseReplacement(returnType: Class<*>, type: String, raw: String): Any? {
        if (returnType == Void.TYPE) return null
        val value: Any? = when (type) {
            "null" -> null
            "boolean" -> raw.equals("true", ignoreCase = true)
            "int" -> raw.toInt()
            "long" -> raw.toLong()
            "float" -> raw.toFloat()
            "double" -> raw.toDouble()
            "string" -> raw
            else -> error("Unsupported replacement type")
        }
        if (value == null && returnType.isPrimitive) error("Primitive return type cannot be null")
        if (value != null && !boxed(returnType).isInstance(value)) {
            error("Replacement type does not match ${returnType.name}")
        }
        return value
    }

internal fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }

