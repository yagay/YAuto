package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.importer.CompatFeatureIds
import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.delay

class StandardFeaturePack : FeaturePack {
    override val id: String = "standard"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(FeatureId("core.delay"), FeatureKind.ACTION, "Delay", "Wait before continuing", FeatureCategory.CORE, fields = listOf(FieldSchema.Duration("durationMs", "Duration")), ownerPackId = id)
        ) { feature, _ ->
            delay(feature.config.long("durationMs", 0).coerceAtLeast(0))
            ActionExecutionResult(true)
        }

        registry.registerAction(
            FeatureDescriptor(FeatureId("core.log"), FeatureKind.ACTION, "Write log", "Write a message to the execution trace", FeatureCategory.CORE, fields = listOf(FieldSchema.Text("message", "Message", true, true)), ownerPackId = id)
        ) { feature, ctx ->
            val message = feature.config.string("message")
            ctx.tracer.record(TraceEvent(ctx.executionId, kind = TraceKind.MESSAGE, timestampEpochMs = System.currentTimeMillis(), message = message, nodeId = ctx.nodeId, featureId = "core.log"))
            ActionExecutionResult(true, message = message)
        }

        registry.registerAction(
            FeatureDescriptor(FeatureId("variable.set"), FeatureKind.ACTION, "Set variable", "Set a runtime variable", FeatureCategory.VARIABLE, fields = listOf(FieldSchema.Text("name", "Variable name", true), FieldSchema.Text("value", "Value")), ownerPackId = id)
        ) { feature, ctx ->
            val name = feature.config.string("name")
            if (name.isBlank()) ActionExecutionResult(false, message = "Variable name is empty")
            else { ctx.variables.set(name, feature.config["value"] ?: ConfigValue.NullValue); ActionExecutionResult(true) }
        }

        registry.registerCondition(
            FeatureDescriptor(FeatureId("core.boolean"), FeatureKind.CONDITION, "Boolean", "Static boolean condition", FeatureCategory.CORE, fields = listOf(FieldSchema.Toggle("value", "Value")), ownerPackId = id)
        ) { feature, _ -> feature.config.boolean("value") }

        listOf(
            CompatFeatureIds.SOURCE_EVENT to FeatureKind.EVENT,
            CompatFeatureIds.SOURCE_STATE to FeatureKind.STATE,
            CompatFeatureIds.SOURCE_CONDITION to FeatureKind.CONDITION,
        ).forEach { (typeId, kind) ->
            registry.registerDescriptor(
                FeatureDescriptor(FeatureId(typeId), kind, "Imported source item", "Preserved source item waiting for a native mapping", FeatureCategory.COMPATIBILITY, stability = com.yagay.yauto.core.model.Stability.DEPRECATED, ownerPackId = id)
            )
        }
        registry.registerAction(
            FeatureDescriptor(FeatureId(CompatFeatureIds.SOURCE_ACTION), FeatureKind.ACTION, "Imported source action", "Preserved source action waiting for a native mapping", FeatureCategory.COMPATIBILITY, stability = com.yagay.yauto.core.model.Stability.DEPRECATED, ownerPackId = id)
        ) { feature, _ ->
            val source = feature.config.string("source.type", "unknown")
            ActionExecutionResult(false, message = "Imported action is not mapped yet: $source")
        }
    }
}
