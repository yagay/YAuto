package com.yagay.yauto.platform.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/**
 * Cellular state gaps verified against MacroDroid and ShortX reference definitions.
 *
 * Reads only framework telephony state; no phone numbers or subscriber identifiers are exported.
 */
class AndroidTelephonyStateExpansionFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.telephony_state_expansion"
    private val context = context.applicationContext
    private val telephony = this.context.getSystemService(TelephonyManager::class.java)
    private val subscriptions = this.context.getSystemService(SubscriptionManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerInfo(registry)
        booleanPair(
            registry,
            "mobile_data_enabled",
            "Mobile data enabled",
            "Check whether mobile data is enabled for the default telephony subscription",
            FeatureCategory.NETWORK,
            ::mobileDataEnabled,
        )
        registerServiceAvailable(registry)
    }

    private fun registerInfo(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.info"),
                FeatureKind.ACTION,
                "Get cellular status",
                "Store mobile-data, roaming, service state and active SIM slot information",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store cellular status object", true)),
                keywords = setOf("mobile data", "cellular", "roaming", "sim", "service state", "subscription"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val variable = feature.config.string("resultVariable").trim()
            if (variable.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
            }
            if (!hasPhoneStatePermission()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
            }
            val output = telephonyInfoObject()
            ctx.variables.set(variable, output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerServiceAvailable(registry: FeatureRegistry) {
        val fields = listOf(
            FieldSchema.Number("subscriptionId", "Subscription ID (-1 = any active SIM)", min = -1.0),
            FieldSchema.Toggle("value", "Service available"),
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            if (!hasPhoneStatePermission()) return@ConditionEvaluator false
            val subscriptionId = feature.config["subscriptionId"].numberOrNull()?.toInt() ?: -1
            val actual = cellularServiceAvailable(subscriptionId)
            actual == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.cellular_service_available"),
            FeatureKind.STATE,
            "Cellular service available",
            "Check whether cellular service is in service for any or a selected active SIM",
            FeatureCategory.NETWORK,
            fields = fields,
            keywords = setOf("cellular", "signal", "service", "sim", "subscription"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.cellular_service_available"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private fun booleanPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        query: () -> Boolean?,
    ) {
        val fields = listOf(FieldSchema.Toggle("value", "Enabled / true"))
        val evaluator = ConditionEvaluator { feature, _ ->
            if (!hasPhoneStatePermission()) return@ConditionEvaluator false
            val actual = runCatching(query).getOrNull() ?: return@ConditionEvaluator false
            actual == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.$key"),
            FeatureKind.STATE,
            title,
            description,
            category,
            fields = fields,
            keywords = setOf("mobile data", "cellular", "roaming", "telephony"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.$key"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private fun mobileDataEnabled(): Boolean? =
        runCatching { telephony.isDataEnabled }.getOrNull()

    private fun networkRoaming(): Boolean? =
        runCatching { telephony.isNetworkRoaming }.getOrNull()

    private fun cellularServiceAvailable(subscriptionId: Int): Boolean {
        val managers = when {
            subscriptionId >= 0 -> listOf(telephony.createForSubscriptionId(subscriptionId))
            else -> activeSubscriptionIds()
                .map(telephony::createForSubscriptionId)
                .ifEmpty { listOf(telephony) }
        }
        return managers.any { manager ->
            runCatching { isCellularServiceAvailable(manager.serviceState?.state) }.getOrDefault(false)
        }
    }

    private fun telephonyInfoObject(): ConfigValue.ObjectValue {
        val activeIds = activeSubscriptionIds()
        val entries = if (activeIds.isEmpty()) {
            listOf(subscriptionObject(telephony, null, null, null))
        } else {
            subscriptions.activeSubscriptionInfoList.orEmpty().map { info ->
                subscriptionObject(
                    telephony.createForSubscriptionId(info.subscriptionId),
                    info.subscriptionId,
                    info.simSlotIndex,
                    info.carrierName?.toString(),
                )
            }
        }
        return ConfigValue.ObjectValue(
            mapOf(
                "defaultDataEnabled" to (mobileDataEnabled()?.let(ConfigValue::BooleanValue) ?: ConfigValue.NullValue),
                "defaultNetworkRoaming" to (networkRoaming()?.let(ConfigValue::BooleanValue) ?: ConfigValue.NullValue),
                "activeSubscriptionCount" to ConfigValue.NumberValue(activeIds.size.toDouble()),
                "subscriptions" to ConfigValue.ListValue(entries),
            )
        )
    }

    private fun subscriptionObject(
        manager: TelephonyManager,
        subscriptionId: Int?,
        slotIndex: Int?,
        carrierName: String?,
    ): ConfigValue.ObjectValue {
        val serviceState = runCatching { manager.serviceState?.state }.getOrNull()
        val dataEnabled = runCatching { manager.isDataEnabled }.getOrNull()
        val roaming = runCatching { manager.isNetworkRoaming }.getOrNull()
        return ConfigValue.ObjectValue(
            buildMap {
                put("subscriptionId", subscriptionId?.let { ConfigValue.NumberValue(it.toDouble()) } ?: ConfigValue.NullValue)
                put("slotIndex", slotIndex?.let { ConfigValue.NumberValue(it.toDouble()) } ?: ConfigValue.NullValue)
                put("carrierName", ConfigValue.StringValue(carrierName.orEmpty()))
                put("dataEnabled", dataEnabled?.let(ConfigValue::BooleanValue) ?: ConfigValue.NullValue)
                put("networkRoaming", roaming?.let(ConfigValue::BooleanValue) ?: ConfigValue.NullValue)
                put("serviceState", ConfigValue.StringValue(cellularServiceStateName(serviceState)))
                put("serviceAvailable", ConfigValue.BooleanValue(isCellularServiceAvailable(serviceState)))
            }
        )
    }

    private fun activeSubscriptionIds(): List<Int> =
        runCatching { subscriptions.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }.distinct() }
            .getOrDefault(emptyList())

    private fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
}

internal fun isCellularServiceAvailable(state: Int?): Boolean =
    state == ServiceState.STATE_IN_SERVICE

internal fun cellularServiceStateName(state: Int?): String = when (state) {
    ServiceState.STATE_IN_SERVICE -> "in_service"
    ServiceState.STATE_OUT_OF_SERVICE -> "out_of_service"
    ServiceState.STATE_EMERGENCY_ONLY -> "emergency_only"
    ServiceState.STATE_POWER_OFF -> "power_off"
    else -> "unknown"
}
