package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent

object SurfaceRuntimeBridge {
    @Volatile private var emitter: RuntimeEventEmitter? = null

    fun attach(value: RuntimeEventEmitter?) { emitter = value }

    fun emit(surfaceId: String, action: String, value: String = "") {
        emitter?.emit(
            RuntimeEvent(
                typeId = "android.event.surface_action",
                payload = mapOf(
                    "surfaceId" to ConfigValue.StringValue(surfaceId),
                    "action" to ConfigValue.StringValue(action),
                    "value" to ConfigValue.StringValue(value),
                ),
                source = "android.surface",
            )
        )
    }
}
