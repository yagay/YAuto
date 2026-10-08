package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

object ModeRuntimeBridge {
    @Volatile private var emitter: RuntimeEventEmitter? = null

    fun attach(value: RuntimeEventEmitter?) {
        emitter = value
    }

    fun emit(previous: String, current: String) {
        emitter?.emit(
            RuntimeEvent(
                typeId = "android.event.mode_changed",
                payload = mapOf(
                    "previous" to ConfigValue.StringValue(previous),
                    "mode" to ConfigValue.StringValue(current),
                ),
                source = "android.mode",
            )
        )
    }
}

class AndroidModeFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.mode"
    private val prefs = context.applicationContext.getSharedPreferences("yauto_mode", Context.MODE_PRIVATE)

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.mode.set"),
                FeatureKind.ACTION,
                "Set YAuto mode",
                "Set a named global YAuto mode and emit a mode-changed event",
                FeatureCategory.FLOW,
                fields = listOf(
                    FieldSchema.Text("mode", "Mode name", true),
                    FieldSchema.Variable("resultVariable", "Store previous mode"),
                ),
                fieldBehaviors = mapOf("mode" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("mode", "profile", "state", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val next = feature.config.string("mode").resolveVariables(ctx.variables).trim()
            if (next.isBlank() || next.length > 128) return@registerAction ActionExecutionResult(false)
            val previous = current()
            prefs.edit().putString(KEY_MODE, next).apply()
            if (previous != next) ModeRuntimeBridge.emit(previous, next)
            val output = ConfigValue.StringValue(previous)
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, output)
            }
            ActionExecutionResult(true, output)
        }

        val evaluator = ConditionEvaluator { feature, ctx ->
            val expected = feature.config.string("mode").resolveVariables(ctx.variables)
            val ignoreCase = (feature.config["ignoreCase"] as? ConfigValue.BooleanValue)?.value ?: false
            current().equals(expected, ignoreCase = ignoreCase)
        }
        val fields = listOf(
            FieldSchema.Text("mode", "Mode name", true),
            FieldSchema.Toggle("ignoreCase", "Ignore case"),
        )
        val state = FeatureDescriptor(
            FeatureId("android.state.mode"),
            FeatureKind.STATE,
            "YAuto mode",
            "Check the current named YAuto mode",
            FeatureCategory.FLOW,
            fields = fields,
            fieldBehaviors = mapOf("mode" to FieldBehavior(supportsVariables = true)),
            keywords = setOf("mode", "profile", "state", "macrodroid"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.mode"), kind = FeatureKind.CONDITION),
            evaluator,
        )

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.mode_changed"),
                FeatureKind.EVENT,
                "YAuto mode changed",
                "Run when the named YAuto mode changes",
                FeatureCategory.FLOW,
                fields = listOf(
                    FieldSchema.Text("mode", "New mode"),
                    FieldSchema.Text("previous", "Previous mode"),
                ),
                keywords = setOf("mode", "profile", "changed", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.mode_changed") return@registerEvent false
            val mode = feature.config.string("mode")
            val previous = feature.config.string("previous")
            (mode.isBlank() || ctx.event.payload.string("mode").equals(mode, true)) &&
                (previous.isBlank() || ctx.event.payload.string("previous").equals(previous, true))
        }
    }

    private fun current(): String = prefs.getString(KEY_MODE, DEFAULT_MODE).orEmpty().ifBlank { DEFAULT_MODE }

    private companion object {
        const val KEY_MODE = "mode"
        const val DEFAULT_MODE = "default"
    }
}
