package com.yagay.yauto.platform.xposed

/** Only records successful installations and frees failed reservations for retry. */
internal object XposedHookInstallationGuard {
    fun install(keys: MutableSet<String>, key: String, install: () -> Unit): Result<Boolean> {
        if (!keys.add(key)) return Result.success(false)
        return runCatching {
            install()
            true
        }.onFailure { keys.remove(key) }
    }
}
