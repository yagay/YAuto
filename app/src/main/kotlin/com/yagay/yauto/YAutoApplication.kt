package com.yagay.yauto

import android.app.Application

class YAutoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installAndroidUserTextResolver(this)
    }

    val graph: AppGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppGraph(this) }
}
