package com.yagay.yauto.platform.android

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class QuickSettingsTileController(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun configure(slot: Int, label: String, state: String) {
        require(slot in 1..3) { "Tile slot must be 1-3" }
        prefs.edit().putString("label_$slot", label).putString("state_$slot", state).apply()
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context.packageName, "${context.packageName}.YAutoTileService$slot"),
            )
        }
    }

    fun label(slot: Int): String = prefs.getString("label_$slot", null).orEmpty().ifBlank { "YAuto $slot" }
    fun state(slot: Int): Int = when (prefs.getString("state_$slot", "inactive")) {
        "active" -> Tile.STATE_ACTIVE
        "unavailable" -> Tile.STATE_UNAVAILABLE
        else -> Tile.STATE_INACTIVE
    }

    companion object { const val PREFS = "yauto_qs_tiles" }
}

class AndroidQuickSettingsFeaturePack(private val controller: QuickSettingsTileController) : FeaturePack {
    override val id = "android.qs_tiles"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.qs_tile"), FeatureKind.EVENT,
                "Quick Settings tile", "Run when one of the three YAuto Quick Settings tiles is tapped",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(FieldSchema.Number("slot", "Tile slot (1-3)", true, min = 1.0, max = 3.0)),
                keywords = setOf("quick settings", "tile", "qs", "磁贴", "快捷设置"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.qs_tile") return@registerEvent false
            val expected = feature.config["slot"].numberOrNull()?.toInt() ?: 1
            ctx.event.payload["slot"].numberOrNull()?.toInt() == expected
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.qs_tile.configure"), FeatureKind.ACTION,
                "Configure Quick Settings tile", "Change a YAuto tile label and active/inactive visual state",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Number("slot", "Tile slot (1-3)", true, min = 1.0, max = 3.0),
                    FieldSchema.Text("label", "Label"),
                    FieldSchema.Choice("state", "State", true, listOf("active", "inactive", "unavailable")),
                ),
                keywords = setOf("quick settings", "tile", "label", "state", "磁贴"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val slot = feature.config["slot"].numberOrNull()?.toInt() ?: 1
            val label = feature.config.string("label").resolveVariables(ctx.variables)
            val state = feature.config.string("state", "inactive")
            runCatching { controller.configure(slot, label, state); ActionExecutionResult(true) }
                .getOrElse { ActionExecutionResult(false, message = it.message ?: it.javaClass.simpleName) }
        }
    }
}
