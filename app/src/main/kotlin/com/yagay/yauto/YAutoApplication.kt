package com.yagay.yauto

import android.app.Application

class YAutoApplication : Application() {
    val graph: AppGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppGraph(this) }
}
