package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Reversible 5G control. Android's phone shell reports a textual list of network types, not a
 * binary number; never write a guessed network mode or drop unknown network types.
 */
class Android5gToggleFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.telephony.5g"
    private val prefs = context.applicationContext.getSharedPreferences("yauto_5g_mode_restore", Context.MODE_PRIVATE)
    private val lock = Mutex()

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.5g.toggle"),
                FeatureKind.ACTION,
                "Toggle 5G",
                "Read the selected SIM's network-mode bitmask and toggle only the 5G NR bit; restore the previous selection when possible",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("slotId", "SIM slot ID", min = 0.0, max = 7.0),
                    FieldSchema.Choice("operation", "5G operation", options = listOf("toggle", "enable", "disable")),
                    FieldSchema.Variable("resultVariable", "Store resulting bitmask"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = listOf(
                    FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
                    FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
                ),
                keywords = setOf("5g", "nr", "switch 5g", "toggle 5g", "shortx", "mobile network"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            lock.withLock {
                val slot = (feature.config["slotId"].numberOrNull() ?: 0.0).toInt()
                val operation = feature.config.string("operation", "toggle")
                if (slot !in 0..7 || operation !in setOf("toggle", "enable", "disable")) {
                    return@withLock ActionExecutionResult(false, message = userText("feature.5g_invalid_config"))
                }
                val read = ctx.capabilities.execute(shellRequest("cmd phone get-allowed-network-types-for-users -s $slot"))
                if (!read.success) return@withLock ActionExecutionResult(false, read.value, read.message)
                val output = shellOutput(read.value)
                val current = parseAllowedNetworkTypes(output)
                    ?: return@withLock ActionExecutionResult(false, message = userText("feature.5g_unsupported_mode"))
                val key = "saved_mode_slot_$slot"
                val saved = if (prefs.contains(key)) prefs.getLong(key, 0L).takeIf { it > 0 } else null
                val next = planFiveGMode(current, saved, operation)
                    ?: return@withLock ActionExecutionResult(false, message = userText("feature.5g_no_safe_mode"))
                if (next != current) {
                    val command = "cmd phone set-allowed-network-types-for-users -s $slot " + java.lang.Long.toBinaryString(next)
                    val write = ctx.capabilities.execute(shellRequest(command))
                    if (!write.success) return@withLock ActionExecutionResult(false, write.value, write.message)
                    // AOSP's phone shell has returned exit code 0 even when its service reported failed.
                    if (!shellOutput(write.value).contains("set-allowed-network-types-for-users completed", ignoreCase = true)) {
                        return@withLock ActionExecutionResult(false, write.value, userText("feature.5g_not_confirmed"))
                    }
                }
                if (next != current && current and NR_BIT != 0L && next and NR_BIT == 0L) {
                    prefs.edit().putLong(key, current).apply()
                } else if (next and NR_BIT != 0L) {
                    prefs.edit().remove(key).apply()
                }
                val value = ConfigValue.StringValue(java.lang.Long.toBinaryString(next))
                feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let { ctx.variables.set(it, value) }
                ActionExecutionResult(true, value)
            }
        }
    }

    private fun shellRequest(command: String) = CapabilityRequest(
        capability = CapabilityIds.PRIVILEGED_SHELL,
        operationId = "system.shell.execute",
        payload = mapOf("command" to ConfigValue.StringValue(command)),
    )
}

private fun shellOutput(value: ConfigValue?): String =
    ((value as? ConfigValue.ObjectValue)?.value?.get("stdout") as? ConfigValue.StringValue)?.value
        ?: (value as? ConfigValue.StringValue)?.value.orEmpty()

internal const val NR_BIT: Long = 524288L

/** Strictly decode the AOSP TelephonyShellCommand textual result; unknown tokens fail closed. */
internal fun parseAllowedNetworkTypes(stdout: String): Long? {
    val text = stdout.trim().lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (text.isEmpty() || text.equals("UNKNOWN", ignoreCase = true)) return null
    if (text.matches(Regex("[01]{1,63}"))) return text.toLongOrNull(2)?.takeIf { it > 0L }
    val names = text.split('|').map { it.trim().uppercase(Locale.ROOT).replace("+", "P") }
    if (names.isEmpty() || names.any { it !in NETWORK_TYPE_BITS }) return null
    val result = names.fold(0L) { bits, name -> bits or (NETWORK_TYPE_BITS.getValue(name)) }
    return result.takeIf { it > 0L }
}

/** Preserve non-5G technologies, never zero out the modem's allowed network technologies. */
internal fun planFiveGMode(current: Long, saved: Long?, operation: String): Long? {
    if (current <= 0L) return null
    val enabled = current and NR_BIT != 0L
    val shouldEnable = when (operation) {
        "enable" -> true
        "disable" -> false
        "toggle" -> !enabled
        else -> return null
    }
    val next = if (shouldEnable) {
        if (enabled) current
        else if (saved != null && saved > 0L && saved and NR_BIT != 0L &&
            saved and NR_BIT.inv() == current
        ) saved else current or NR_BIT
    } else {
        current and NR_BIT.inv()
    }
    return next.takeIf { it > 0L }
}

private val NETWORK_TYPE_BITS = mapOf(
    "GPRS" to (1L shl 0),
    "EDGE" to (1L shl 1),
    "UMTS" to (1L shl 2),
    "CDMA" to (1L shl 3),
    "EVDO_0" to (1L shl 4),
    "EVDO_A" to (1L shl 5),
    "1XRTT" to (1L shl 6),
    "HSDPA" to (1L shl 7),
    "HSUPA" to (1L shl 8),
    "HSPA" to (1L shl 9),
    "IDEN" to (1L shl 10),
    "EVDO_B" to (1L shl 11),
    "LTE" to (1L shl 12),
    "EHRPD" to (1L shl 13),
    "HSPAP" to (1L shl 14),
    "GSM" to (1L shl 15),
    "TD_SCDMA" to (1L shl 16),
    "IWLAN" to (1L shl 17),
    "LTE_CA" to (1L shl 18),
    "NR" to NR_BIT,
)
