package com.yagay.yauto.platform.xposed

/** Immutable, bounded exception metadata captured by the runtime-init Hook.
 * The Hook keeps ownership of interception; serialization is tested without LSPosed.
 */
internal object XposedUncaughtExceptionSnapshot {
    fun create(packageName: String, thread: Thread?, error: Throwable?, methodName: String): Map<String, String> =
        mapOf(
            "package" to packageName,
            "thread" to thread?.name.orEmpty(),
            "exceptionClass" to error?.javaClass?.name.orEmpty(),
            "message" to error?.message.orEmpty().take(1024),
            "method" to methodName,
        )
}
