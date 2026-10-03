package com.yagay.yauto.feature.standard.privileged

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature
import com.yagay.yauto.core.registry.preferredBackendId

internal fun privilegedDescriptor(
    id: String,
    title: String,
    description: String,
    category: FeatureCategory,
    fields: List<FieldSchema> = emptyList(),
    keywords: Set<String> = emptySet(),
    behaviors: Map<String, FieldBehavior> = emptyMap(),
): FeatureDescriptor = FeatureDescriptor(
    id = FeatureId(id),
    kind = FeatureKind.ACTION,
    title = title,
    description = description,
    category = category,
    capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
    fields = fields,
    keywords = keywords,
    fieldBehaviors = behaviors,
)

internal fun privilegedCommandFeature(
    descriptor: FeatureDescriptor,
    command: (FeatureRef, FeatureExecutionContext) -> String,
): FeatureDefinition = actionFeature(descriptor) { feature, context ->
    val shell = runCatching { command(feature, context) }.getOrElse { error ->
        return@actionFeature ActionExecutionResult(
            false,
            message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName),
        )
    }
    executePrivilegedShell(feature, context, shell)
}

internal suspend fun executePrivilegedShell(
    feature: FeatureRef,
    context: FeatureExecutionContext,
    command: String,
    timeoutMs: Long? = null,
): ActionExecutionResult {
    val payload = buildMap<String, ConfigValue> {
        put("command", ConfigValue.StringValue(command))
        timeoutMs?.let { put("timeoutMs", ConfigValue.NumberValue(it.toDouble())) }
    }
    val result = context.executeCapability(
        featureId = feature.typeId,
        request = CapabilityRequest(
            capabilityId = CapabilityIds.PRIVILEGED_SHELL,
            operation = feature.typeId,
            payload = payload,
            preferredBackendId = feature.preferredBackendId(),
        ),
    )
    return ActionExecutionResult(result.success, result.value, result.message)
}

internal fun validatedPackage(raw: String): String {
    val value = raw.trim()
    require(PACKAGE_NAME.matches(value)) { "Invalid package name" }
    return value
}

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

internal val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
internal val COMPONENT_NAME = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
internal val PERMISSION_NAME = Regex("[A-Za-z0-9_.]+")
internal val SAFE_TOKEN = Regex("[A-Za-z0-9_.:-]+")
internal val SAFE_SETTING_KEY = Regex("[A-Za-z0-9_.:-]+")
