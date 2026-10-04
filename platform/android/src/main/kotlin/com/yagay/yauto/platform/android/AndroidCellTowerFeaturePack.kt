package com.yagay.yauto.platform.android

import android.content.Context
import android.telephony.TelephonyManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidCellTowerFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.cell_tower"
    private val context = context.applicationContext
    private val telephony = this.context.getSystemService(TelephonyManager::class.java)

    override fun install(registry: FeatureRegistry) {
        val fields = listOf(
            FieldSchema.Choice("technology", "Technology", true, listOf("any", "nr", "lte", "wcdma", "tdscdma", "gsm", "cdma")),
            FieldSchema.Text("identityContains", "Cell identity contains"),
            FieldSchema.Number("minDbm", "Minimum signal dBm", min = -160.0, max = 0.0),
            FieldSchema.Toggle("registeredOnly", "Registered cells only"),
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            if (!runtimePermissionGranted(context, "phone") || !runtimePermissionGranted(context, "location")) return@ConditionEvaluator false
            @Suppress("MissingPermission")
            telephony.allCellInfo.orEmpty().any { info -> matchCell(feature, cellInfoValue(info).value) }
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.cell_tower_match"), FeatureKind.STATE,
            "Cell tower match", "Check whether a visible cellular tower matches technology, identity and signal filters",
            FeatureCategory.NETWORK,
            fields = fields,
            accessRequirements = setOf(AccessRequirement.PHONE, AccessRequirement.LOCATION),
            keywords = setOf("cell tower", "cell id", "tower", "lte", "5g", "nr"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.cell_tower_match"), kind = FeatureKind.CONDITION),
            evaluator,
        )
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.cell_tower_changed"), FeatureKind.EVENT,
                "Cell tower changed", "Run when the visible cellular environment changes and a tower matches the configured filters",
                FeatureCategory.NETWORK,
                fields = fields,
                accessRequirements = setOf(AccessRequirement.PHONE, AccessRequirement.LOCATION),
                keywords = setOf("cell tower", "cell id", "tower changed", "signal"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.cell_tower_changed" && matchCell(feature, ctx.event.payload)
        }
    }

    private fun matchCell(feature: com.yagay.yauto.core.model.FeatureRef, payload: Map<String, ConfigValue>): Boolean {
        val technology = feature.config.string("technology", "any")
        val actualTechnology = payload.string("technology")
        if (technology != "any" && technology != actualTechnology) return false
        val identity = feature.config.string("identityContains")
        if (identity.isNotBlank() && !payload.string("identity").contains(identity, ignoreCase = true)) return false
        if (feature.config.boolean("registeredOnly") && (payload["registered"] as? ConfigValue.BooleanValue)?.value != true) return false
        val minDbm = feature.config["minDbm"].numberOrNull()
        val dbm = (payload["dbm"] as? ConfigValue.NumberValue)?.value ?: -999.0
        return minDbm == null || dbm >= minDbm
    }
}
