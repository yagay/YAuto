package com.yagay.yauto.platform.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.CellIdentity
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class AndroidTelephonyAdvancedFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.telephony_advanced"
    private val context = context.applicationContext
    private val subscriptions = this.context.getSystemService(SubscriptionManager::class.java)
    private val telephony = this.context.getSystemService(TelephonyManager::class.java)
    private val telecom = this.context.getSystemService(TelecomManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerSubscriptions(registry)
        registerTelephonyInfo(registry)
        registerCellInfo(registry)
        registerDirectCall(registry)
        registerAnswerCall(registry)
        registerEndCall(registry)
    }

    private fun registerSubscriptions(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sim.subscriptions.query"), FeatureKind.ACTION,
                "Query SIM subscriptions", "Return active SIM/eSIM subscription information",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store subscription list", true)),
                accessRequirements = setOf(AccessRequirement.PHONE),
                keywords = setOf("sim", "esim", "subscription", "slot", "carrier"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "phone")) return@registerAction missingPhonePermission()
            val output = runCatching {
                @Suppress("MissingPermission")
                ConfigValue.ListValue(subscriptions.activeSubscriptionInfoList.orEmpty().map { info ->
                    ConfigValue.ObjectValue(
                        mapOf(
                            "subscriptionId" to ConfigValue.NumberValue(info.subscriptionId.toDouble()),
                            "slotIndex" to ConfigValue.NumberValue(info.simSlotIndex.toDouble()),
                            "displayName" to ConfigValue.StringValue(info.displayName?.toString().orEmpty()),
                            "carrierName" to ConfigValue.StringValue(info.carrierName?.toString().orEmpty()),
                            "countryIso" to ConfigValue.StringValue(info.countryIso.orEmpty()),
                            "mcc" to ConfigValue.StringValue(info.mccString.orEmpty()),
                            "mnc" to ConfigValue.StringValue(info.mncString.orEmpty()),
                            "embedded" to ConfigValue.BooleanValue(info.isEmbedded),
                            "opportunistic" to ConfigValue.BooleanValue(info.isOpportunistic),
                            "cardId" to ConfigValue.NumberValue(info.cardId.toDouble()),
                            "roaming" to ConfigValue.BooleanValue(info.dataRoaming == SubscriptionManager.DATA_ROAMING_ENABLE),
                        )
                    )
                })
            }.getOrElse { return@registerAction failure(it) }
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerTelephonyInfo(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.subscription.info"), FeatureKind.ACTION,
                "Get telephony subscription info", "Return service, network and signal information for a subscription",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("subscriptionId", "Subscription ID", min = 0.0, max = Int.MAX_VALUE.toDouble()),
                    FieldSchema.Variable("resultVariable", "Store telephony object", true),
                ),
                accessRequirements = setOf(AccessRequirement.PHONE),
                keywords = setOf("telephony", "network type", "signal", "carrier", "sim"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "phone")) return@registerAction missingPhonePermission()
            val subId = feature.config["subscriptionId"].numberOrNull()?.toInt()
            val manager = subId?.let(telephony::createForSubscriptionId) ?: telephony
            val output = runCatching {
                @Suppress("MissingPermission")
                ConfigValue.ObjectValue(
                    mapOf(
                        "subscriptionId" to ConfigValue.NumberValue((subId ?: SubscriptionManager.getDefaultSubscriptionId()).toDouble()),
                        "networkOperatorName" to ConfigValue.StringValue(manager.networkOperatorName.orEmpty()),
                        "networkOperator" to ConfigValue.StringValue(manager.networkOperator.orEmpty()),
                        "simOperatorName" to ConfigValue.StringValue(manager.simOperatorName.orEmpty()),
                        "simCountryIso" to ConfigValue.StringValue(manager.simCountryIso.orEmpty()),
                        "networkCountryIso" to ConfigValue.StringValue(manager.networkCountryIso.orEmpty()),
                        "dataNetworkType" to ConfigValue.StringValue(networkTypeName(manager.dataNetworkType)),
                        "voiceNetworkType" to ConfigValue.StringValue(networkTypeName(manager.voiceNetworkType)),
                        "dataState" to ConfigValue.StringValue(dataStateName(manager.dataState)),
                        "callState" to ConfigValue.StringValue(callStateName(manager.callState)),
                        "signalDbm" to ConfigValue.NumberValue((manager.signalStrength?.cellSignalStrengths?.maxOfOrNull { it.dbm } ?: -999).toDouble()),
                    )
                )
            }.getOrElse { return@registerAction failure(it) }
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerCellInfo(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.cell_tower.query"), FeatureKind.ACTION,
                "Query cell towers", "Return visible registered and neighboring cellular identities and signal levels",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("subscriptionId", "Subscription ID", min = 0.0, max = Int.MAX_VALUE.toDouble()),
                    FieldSchema.Variable("resultVariable", "Store cell list", true),
                ),
                accessRequirements = setOf(AccessRequirement.PHONE, AccessRequirement.LOCATION),
                keywords = setOf("cell tower", "cell id", "lte", "5g", "nr", "gsm", "signal"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "phone") || !runtimePermissionGranted(context, "location")) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.phone_location_permission_required"))
            }
            val manager = feature.config["subscriptionId"].numberOrNull()?.toInt()?.let(telephony::createForSubscriptionId) ?: telephony
            val output = runCatching {
                @Suppress("MissingPermission")
                ConfigValue.ListValue(manager.allCellInfo.orEmpty().map(::cellInfoValue))
            }.getOrElse { return@registerAction failure(it) }
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerDirectCall(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.phone.call"), FeatureKind.ACTION,
                "Place phone call", "Place a direct phone call using Android ACTION_CALL",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Text("number", "Phone number", true)),
                fieldBehaviors = mapOf("number" to FieldBehavior(supportsVariables = true)),
                accessRequirements = setOf(AccessRequirement.PHONE),
                keywords = setOf("call", "dial", "phone", "direct"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "phone")) return@registerAction missingPhonePermission()
            val number = feature.config.string("number").resolveVariables(ctx.variables).trim()
            if (!Regex("[+0-9*#,; -]{1,64}").matches(number)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.phone_number_invalid"))
            }
            runCatching {
                context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionExecutionResult(true)
            }.getOrElse { failure(it) }
        }
    }

    private fun registerAnswerCall(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.phone.answer"), FeatureKind.ACTION,
                "Answer ringing call", "Answer the current ringing call when Android allows YAuto to do so",
                FeatureCategory.APP,
                accessRequirements = setOf(AccessRequirement.PHONE),
                keywords = setOf("answer", "call", "ringing", "phone"),
                ownerPackId = id,
            )
        ) { _, _ ->
            if (!runtimePermissionGranted(context, "phone")) return@registerAction missingPhonePermission()
            @Suppress("DEPRECATION", "MissingPermission")
            runCatching { telecom.acceptRingingCall(); ActionExecutionResult(true) }.getOrElse { failure(it) }
        }
    }

    private fun registerEndCall(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.phone.end"), FeatureKind.ACTION,
                "End phone call", "End the current call when Android allows YAuto to do so",
                FeatureCategory.APP,
                accessRequirements = setOf(AccessRequirement.PHONE),
                keywords = setOf("hang up", "end call", "reject", "phone"),
                ownerPackId = id,
            )
        ) { _, _ ->
            if (!runtimePermissionGranted(context, "phone")) return@registerAction missingPhonePermission()
            @Suppress("DEPRECATION", "MissingPermission")
            runCatching {
                val ended = telecom.endCall()
                ActionExecutionResult(ended, ConfigValue.BooleanValue(ended), if (ended) null else userText("feature.end_call_rejected"))
            }.getOrElse { failure(it) }
        }
    }

    private fun store(name: String, value: ConfigValue, ctx: FeatureExecutionContext) {
        name.trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, value) }
    }

    private fun missingPhonePermission() =
        ActionExecutionResult(false, message = userText("feature.phone_permission_required"))

    private fun failure(error: Throwable) =
        ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))
}

internal fun cellInfoValue(info: CellInfo): ConfigValue.ObjectValue {
    val identity: CellIdentity?
    val dbm: Int
    val technology: String
    when (info) {
        is CellInfoLte -> { identity = info.cellIdentity; dbm = info.cellSignalStrength.dbm; technology = "lte" }
        is CellInfoNr -> { identity = info.cellIdentity; dbm = info.cellSignalStrength.dbm; technology = "nr" }
        is CellInfoGsm -> { identity = info.cellIdentity; dbm = info.cellSignalStrength.dbm; technology = "gsm" }
        is CellInfoWcdma -> { identity = info.cellIdentity; dbm = info.cellSignalStrength.dbm; technology = "wcdma" }
        is CellInfoTdscdma -> { identity = info.cellIdentity; dbm = info.cellSignalStrength.dbm; technology = "tdscdma" }
        is CellInfoCdma -> { identity = info.cellIdentity; dbm = info.cellSignalStrength.dbm; technology = "cdma" }
        else -> { identity = null; dbm = -999; technology = "unknown" }
    }
    return ConfigValue.ObjectValue(
        mapOf(
            "technology" to ConfigValue.StringValue(technology),
            "registered" to ConfigValue.BooleanValue(info.isRegistered),
            "dbm" to ConfigValue.NumberValue(dbm.toDouble()),
            "identity" to ConfigValue.StringValue(identity?.toString().orEmpty()),
        )
    )
}

internal fun networkTypeName(value: Int): String = when (value) {
    TelephonyManager.NETWORK_TYPE_GPRS -> "gprs"
    TelephonyManager.NETWORK_TYPE_EDGE -> "edge"
    TelephonyManager.NETWORK_TYPE_UMTS -> "umts"
    TelephonyManager.NETWORK_TYPE_CDMA -> "cdma"
    TelephonyManager.NETWORK_TYPE_EVDO_0 -> "evdo_0"
    TelephonyManager.NETWORK_TYPE_EVDO_A -> "evdo_a"
    TelephonyManager.NETWORK_TYPE_1xRTT -> "1xrtt"
    TelephonyManager.NETWORK_TYPE_HSDPA -> "hsdpa"
    TelephonyManager.NETWORK_TYPE_HSUPA -> "hsupa"
    TelephonyManager.NETWORK_TYPE_HSPA -> "hspa"
    TelephonyManager.NETWORK_TYPE_IDEN -> "iden"
    TelephonyManager.NETWORK_TYPE_EVDO_B -> "evdo_b"
    TelephonyManager.NETWORK_TYPE_LTE -> "lte"
    TelephonyManager.NETWORK_TYPE_EHRPD -> "ehrpd"
    TelephonyManager.NETWORK_TYPE_HSPAP -> "hspap"
    TelephonyManager.NETWORK_TYPE_GSM -> "gsm"
    TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "td_scdma"
    TelephonyManager.NETWORK_TYPE_IWLAN -> "iwlan"
    TelephonyManager.NETWORK_TYPE_NR -> "nr_5g"
    else -> "unknown"
}

internal fun dataStateName(value: Int): String = when (value) {
    TelephonyManager.DATA_DISCONNECTED -> "disconnected"
    TelephonyManager.DATA_CONNECTING -> "connecting"
    TelephonyManager.DATA_CONNECTED -> "connected"
    TelephonyManager.DATA_SUSPENDED -> "suspended"
    TelephonyManager.DATA_DISCONNECTING -> "disconnecting"
    else -> "unknown"
}

internal fun callStateName(value: Int): String = when (value) {
    TelephonyManager.CALL_STATE_IDLE -> "idle"
    TelephonyManager.CALL_STATE_RINGING -> "ringing"
    TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
    else -> "unknown"
}
