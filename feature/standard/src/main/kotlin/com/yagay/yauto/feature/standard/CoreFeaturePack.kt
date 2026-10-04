package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.delay

/**
 * Small reference pack for the V2 feature-definition style.
 *
 * Each feature keeps its descriptor and executor together. New domains should follow this pattern
 * instead of adding more feature-specific branches to a central registry or editor.
 */
class CoreFeaturePack : FeaturePack {
    override val id: String = "standard.core"

    private val definitions: List<FeatureDefinition> = listOf(
        eventFeature(
            FeatureDescriptor(
                id = FeatureId("core.event.any"),
                kind = FeatureKind.EVENT,
                title = "Any runtime event",
                description = "Run for any YAuto runtime event",
                category = FeatureCategory.CORE,
                fields = listOf(FieldSchema.Text("tag", "Source tag")),
                keywords = setOf("any event", "wildcard", "fact", "tag"),
            )
        ) { _, _ -> true },
        eventFeature(
            FeatureDescriptor(
                id = FeatureId("core.event.manual"),
                kind = FeatureKind.EVENT,
                title = "Manual event",
                description = "Run when YAuto receives a manual runtime event",
                category = FeatureCategory.CORE,
                fields = listOf(FieldSchema.Text("name", "Name")),
                keywords = setOf("manual", "test", "run"),
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "core.event.manual") false
            else {
                val expected = feature.config.string("name")
                expected.isBlank() || ctx.event.payload.string("name") == expected
            }
        },
        actionFeature(
            FeatureDescriptor(
                id = FeatureId("core.noop"),
                kind = FeatureKind.ACTION,
                title = "No operation",
                description = "Continue without performing an operation",
                category = FeatureCategory.CORE,
                fields = emptyList(),
                keywords = setOf("noop", "empty", "continue"),
            )
        ) { _, _ -> ActionExecutionResult(true) },
        actionFeature(
            FeatureDescriptor(
                id = FeatureId("core.delay"),
                kind = FeatureKind.ACTION,
                title = "Delay",
                description = "Wait before continuing",
                category = FeatureCategory.CORE,
                fields = listOf(FieldSchema.Duration("durationMs", "Duration")),
                fieldBehaviors = mapOf(
                    "durationMs" to FieldBehavior(defaultValue = ConfigValue.NumberValue(0.0)),
                ),
            )
        ) { feature, _ ->
            delay(feature.config.long("durationMs", 0).coerceAtLeast(0))
            ActionExecutionResult(true)
        },
        actionFeature(
            FeatureDescriptor(
                id = FeatureId("core.log"),
                kind = FeatureKind.ACTION,
                title = "Write log",
                description = "Write a message to the execution trace",
                category = FeatureCategory.CORE,
                fields = listOf(FieldSchema.Text("message", "Message", true, true)),
                fieldBehaviors = mapOf(
                    "message" to FieldBehavior(supportsVariables = true),
                ),
            )
        ) { feature, ctx ->
            val message = feature.config.string("message").resolveVariables(ctx.variables)
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
        },
        conditionFeature(
            FeatureDescriptor(
                id = FeatureId("core.condition.event_tag"),
                kind = FeatureKind.CONDITION,
                title = "Event source tag",
                description = "Match the tag of the event feature that triggered the current automation",
                category = FeatureCategory.CORE,
                fields = listOf(FieldSchema.Text("tag", "Tag", true)),
                fieldBehaviors = mapOf("tag" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("event tag", "fact tag", "shortx"),
            )
        ) { feature, ctx ->
            val expected = feature.config.string("tag").resolveVariables(ctx.variables)
            (ctx.variables.get("event.fact_tag") as? ConfigValue.StringValue)?.value == expected
        },
        conditionFeature(
            FeatureDescriptor(
                id = FeatureId("core.boolean"),
                kind = FeatureKind.CONDITION,
                title = "Boolean",
                description = "Static boolean condition",
                category = FeatureCategory.CORE,
                fields = listOf(FieldSchema.Toggle("value", "Value")),
                fieldBehaviors = mapOf(
                    "value" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(false)),
                ),
            )
        ) { feature, _ -> feature.config.boolean("value") },
    )

    override fun install(registry: FeatureRegistry) {
        DefinitionFeaturePack(id, definitions).install(registry)
    }
}
