package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.delay

class CoreFeaturePack : FeaturePack {
    override val id: String = "standard.core"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId("core.event.manual"),
                kind = FeatureKind.EVENT,
                title = "Manual event",
                description = "Run when YAuto receives a manual runtime event",
                category = FeatureCategory.CORE,
                fields = listOf(FieldSchema.Text("name", "Name")),
                keywords = setOf("manual", "test", "run"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "core.event.manual") false
            else {
                val expected = feature.config.string("name")
                expected.isBlank() || ctx.event.payload.string("name") == expected
            }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("core.delay"), FeatureKind.ACTION, "Delay", "Wait before continuing",
                FeatureCategory.CORE,
                fields = listOf(FieldSchema.Duration("durationMs", "Duration")),
                ownerPackId = id,
            )
        ) { feature, _ ->
            delay(feature.config.long("durationMs", 0).coerceAtLeast(0))
            ActionExecutionResult(true)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("core.log"), FeatureKind.ACTION, "Write log", "Write a message to the execution trace",
                FeatureCategory.CORE,
                fields = listOf(FieldSchema.Text("message", "Message", true, true)),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val message = feature.config.string("message")
            ctx.tracer.record(
                TraceEvent(
                    ctx.executionId,
                    kind = TraceKind.MESSAGE,
                    timestampEpochMs = System.currentTimeMillis(),
                    message = message,
                    nodeId = ctx.nodeId,
                    featureId = "core.log",
                )
            )
            ActionExecutionResult(true, message = message)
        }

        registry.registerCondition(
            FeatureDescriptor(
                FeatureId("core.boolean"), FeatureKind.CONDITION, "Boolean", "Static boolean condition",
                FeatureCategory.CORE,
                fields = listOf(FieldSchema.Toggle("value", "Value")),
                ownerPackId = id,
            )
        ) { feature, _ -> feature.config.boolean("value") }
    }
}
