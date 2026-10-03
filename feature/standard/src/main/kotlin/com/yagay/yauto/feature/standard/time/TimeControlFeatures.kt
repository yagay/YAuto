package com.yagay.yauto.feature.standard.time

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature
import com.yagay.yauto.core.registry.conditionFeature
import com.yagay.yauto.core.registry.stateFeature
import kotlinx.coroutines.delay

internal object TimeControlFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
            timeDescriptor(
                "time.wait_until",
                FeatureKind.ACTION,
                "Wait until timestamp",
                "Pause the flow until the target epoch-millisecond time, with a safety limit",
                fields = listOf(
                    FieldSchema.Number("timestampMs", "Target timestamp (ms)", true),
                    FieldSchema.Duration("maxWaitMs", "Maximum wait"),
                ),
                keywords = setOf("time", "wait", "until", "delay", "schedule"),
                behaviors = mapOf(
                    "maxWaitMs" to FieldBehavior(defaultValue = ConfigValue.NumberValue(3_600_000.0)),
                ),
            )
        ) { feature, _ ->
            val target = feature.config["timestampMs"].numberOrNull()?.toLong()
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_invalid_timestamp"))
            val maxWait = (feature.config["maxWaitMs"].numberOrNull()?.toLong() ?: 3_600_000L)
                .coerceIn(0L, 86_400_000L)
            val remaining = (target - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remaining > maxWait) {
                return@actionFeature ActionExecutionResult(false, message = userText("feature.time_wait_exceeds_limit"))
            }
            if (remaining > 0L) delay(remaining)
            ActionExecutionResult(true)
        },
        stateFeature(timestampCompareDescriptor(FeatureKind.STATE, "time.state.timestamp_compare")) { feature, _ ->
            compareTimestamps(feature)
        },
        conditionFeature(timestampCompareDescriptor(FeatureKind.CONDITION, "time.condition.timestamp_compare")) { feature, _ ->
            compareTimestamps(feature)
        },
    )

    private fun timestampCompareDescriptor(kind: FeatureKind, id: String) = timeDescriptor(
        id = id,
        kind = kind,
        title = "Compare timestamps",
        description = "Compare two epoch-millisecond timestamps",
        fields = listOf(
            FieldSchema.Number("leftMs", "Left timestamp (ms)", true),
            FieldSchema.Choice("operator", "Comparison", true, listOf("==", "!=", ">", ">=", "<", "<=")),
            FieldSchema.Number("rightMs", "Right timestamp (ms)", true),
        ),
        keywords = setOf("time", "timestamp", "compare"),
        behaviors = mapOf("operator" to FieldBehavior(defaultValue = ConfigValue.StringValue("=="))),
    )

    private fun compareTimestamps(feature: com.yagay.yauto.core.model.FeatureRef): Boolean {
        val left = feature.config["leftMs"].numberOrNull() ?: return false
        val right = feature.config["rightMs"].numberOrNull() ?: return false
        return when (feature.config.string("operator", "==")) {
            "==" -> left == right
            "!=" -> left != right
            ">" -> left > right
            ">=" -> left >= right
            "<" -> left < right
            "<=" -> left <= right
            else -> false
        }
    }
}
