package com.yagay.yauto

import android.app.Application

class YAutoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguageManager.applySaved(this)
        installAndroidUserTextResolver(this)
    }

    val graph: AppGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppGraph(this) }
}
