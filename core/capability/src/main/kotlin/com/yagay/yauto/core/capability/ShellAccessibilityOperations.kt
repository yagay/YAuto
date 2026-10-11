package com.yagay.yauto.core.capability

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string

/**
 * Shell-equivalent operations only. Node lookup, view-ID actions, screen text,
 * input method interactions and the power dialog still require Accessibility.
 * All command arguments are strictly validated integers or fixed literals.
 */
object ShellAccessibilityOperations {
    const val GLOBAL_ACTION = "accessibility.global_action"
    const val TAP = "accessibility.gesture.tap"
    const val SWIPE = "accessibility.gesture.swipe"

    /**
     * LSPosed exposes a system_server API only for these semantic equivalents.
     * Do not pretend a generic UI tap, Home or Back has a matching system Hook.
     */
    fun systemUiOperation(request: CapabilityRequest): String? {
        if (request.capability != CapabilityIds.ACCESSIBILITY ||
            request.operationId != GLOBAL_ACTION) return null
        return when (request.payload.string("action")) {
            "notifications" -> SystemOperations.EXPAND_NOTIFICATIONS
            "quick_settings" -> SystemOperations.EXPAND_QUICK_SETTINGS
            "lock_screen" -> SystemOperations.SLEEP
            else -> null
        }
    }

    fun command(request: CapabilityRequest): String? {
        if (request.capability != CapabilityIds.ACCESSIBILITY) return null
        val data = request.payload
        return when (request.operationId) {
            GLOBAL_ACTION -> when (data.string("action")) {
                "back" -> "input keyevent 4"
                "home" -> "input keyevent 3"
                "recents" -> "input keyevent 187"
                "notifications" -> SystemOperations.shellCommand(SystemOperations.EXPAND_NOTIFICATIONS)
                "quick_settings" -> SystemOperations.shellCommand(SystemOperations.EXPAND_QUICK_SETTINGS)
                "lock_screen" -> SystemOperations.shellCommand(SystemOperations.SLEEP)
                else -> null
            }
            TAP -> {
                val x = coordinate(data["x"]) ?: return null
                val y = coordinate(data["y"]) ?: return null
                val duration = data.long("durationMs", 40L).coerceIn(1L, 60_000L)
                "input swipe $x $y $x $y $duration"
            }
            SWIPE -> {
                val x1 = coordinate(data["x1"]) ?: return null
                val y1 = coordinate(data["y1"]) ?: return null
                val x2 = coordinate(data["x2"]) ?: return null
                val y2 = coordinate(data["y2"]) ?: return null
                val duration = data.long("durationMs", 300L).coerceIn(1L, 60_000L)
                "input swipe $x1 $y1 $x2 $y2 $duration"
            }
            else -> null
        }
    }

    private fun coordinate(value: ConfigValue?): Int? =
        value.numberOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it <= 100_000.0 }?.toInt()
}
