package com.yagay.yauto

import android.app.Application

class YAutoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StartupFailureRecorder.install(this)
        AppLanguageManager.applySaved(this)
        installAndroidUserTextResolver(this)
    }

    val graph: AppGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { AppGraph(this) }
            .onFailure { StartupFailureRecorder.record(this, "app-graph", it) }
            .getOrThrow()
    }
}
