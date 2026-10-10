package com.yagay.yauto.ui.editor

import androidx.compose.runtime.staticCompositionLocalOf
import com.yagay.yauto.core.registry.AccessRequirement

/** App-owned authorization actions; the editor never owns system services. */
enum class PermissionAvailability { GRANTED, NOT_GRANTED, UNKNOWN }

interface FeaturePermissionGateway {
    fun availability(permission: AccessRequirement): PermissionAvailability
    fun request(permission: AccessRequirement)
    val feedback: String?
}

val LocalFeaturePermissionGateway = staticCompositionLocalOf<FeaturePermissionGateway?> { null }
