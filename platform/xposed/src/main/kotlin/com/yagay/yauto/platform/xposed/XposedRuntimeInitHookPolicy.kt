package com.yagay.yauto.platform.xposed

import java.lang.reflect.Method

/** Pure method-selection and deduplication policy for app RuntimeInit crash hooks. */
internal object XposedRuntimeInitHookPolicy {
    fun isCrashHandler(method: Method): Boolean =
        method.name == "uncaughtException" &&
            method.parameterTypes.any { Throwable::class.java.isAssignableFrom(it) }

    fun installationKey(packageName: String, method: Method): String =
        "shortx-runtime-init|" + packageName + "|" + method.toGenericString()
}
