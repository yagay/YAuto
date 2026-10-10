package com.yagay.yauto

import com.yagay.yauto.platform.accessibility.AccessibilityRuntimeBridge
import com.yagay.yauto.platform.android.*
import com.yagay.yauto.platform.xposed.XposedHookRuntimeBridge
import com.yagay.yauto.platform.xposed.XposedSystemEventRuntimeBridge

/** One shutdown owner for every event bridge attached by the runtime service. */
internal object RuntimeBridgeLifecycle {
    fun detachAll() {
        AccessibilityRuntimeBridge.configureUiRuntimeEvents(emptySet())
        AccessibilityRuntimeBridge.setListener(null)
        AccessibilityRuntimeBridge.setKeyListener(null)
        AccessibilityRuntimeBridge.setUiEventListener(null)
        AccessibilityRuntimeBridge.setFingerprintGestureListener(null)
        SurfaceRuntimeBridge.attach(null)
        AdvancedParityRuntimeBridge.attach(null)
        ModeRuntimeBridge.attach(null)
        WearRuntimeBridge.attach(null)
        VendorBridgeRuntime.attach(null)
        XposedHookRuntimeBridge.attach(null)
        XposedSystemEventRuntimeBridge.attach(null)
    }
}
