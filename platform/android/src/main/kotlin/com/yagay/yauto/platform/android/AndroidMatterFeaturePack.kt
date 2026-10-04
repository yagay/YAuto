package com.yagay.yauto.platform.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.pow

class AndroidMatterFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.matter"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registerCommission(registry)
        registerLight(registry)
        registerLightState(registry)
    }

    private fun registerCommission(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.matter.commission"),
                FeatureKind.ACTION,
                "Commission Matter device",
                "Open Android/Google Play services Matter commissioning using an optional QR/manual pairing payload",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Text("payload", "Matter QR/manual pairing payload"),
                    FieldSchema.Number("vendorId", "Vendor ID", min = 0.0, max = 65535.0),
                    FieldSchema.Number("productId", "Product ID", min = 0.0, max = 65535.0),
                ),
                fieldBehaviors = mapOf(
                    "payload" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("matter", "commission", "pair device", "smart home", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val intent = Intent(ACTION_START_COMMISSIONING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val payload = feature.config.string("payload").resolveVariables(ctx.variables).trim()
            if (payload.isNotBlank()) intent.putExtra(EXTRA_ONBOARDING_PAYLOAD, payload)
            feature.config["vendorId"].numberOrNull()?.toInt()?.takeIf { it in 0..65535 }?.let {
                intent.putExtra(EXTRA_VENDOR_ID, it)
            }
            feature.config["productId"].numberOrNull()?.toInt()?.takeIf { it in 0..65535 }?.let {
                intent.putExtra(EXTRA_PRODUCT_ID, it)
            }
            val launched = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            ActionExecutionResult(launched)
        }
    }

    private fun registerLight(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.matter.light"),
                FeatureKind.ACTION,
                "Matter Light",
                "Control a Matter light using a local chip-tool controller or a Home Assistant Matter entity",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("backend", "Backend", true, listOf("chip_tool", "home_assistant")),
                    FieldSchema.Text("deviceId", "Matter node ID / Home Assistant entity ID", true),
                    FieldSchema.Number("endpointId", "Matter endpoint ID", min = 0.0, max = 65535.0),
                    FieldSchema.Choice("set", "Set", true, listOf("on", "off", "toggle")),
                    FieldSchema.Text("color", "Colour #RRGGBB"),
                    FieldSchema.Number("brightness", "Brightness %", min = 0.0, max = 100.0),
                    FieldSchema.Text("chipToolPath", "chip-tool binary path"),
                    FieldSchema.Text("haUrl", "Home Assistant base URL"),
                    FieldSchema.Text("haToken", "Home Assistant long-lived token"),
                    FieldSchema.Variable("resultVariable", "Store result"),
                ),
                fieldBehaviors = mapOf(
                    "deviceId" to FieldBehavior(supportsVariables = true),
                    "color" to FieldBehavior(supportsVariables = true),
                    "chipToolPath" to FieldBehavior(
                        visibleWhen = FieldRule.Equals("backend", ConfigValue.StringValue("chip_tool")),
                        supportsVariables = true,
                    ),
                    "haUrl" to FieldBehavior(
                        visibleWhen = FieldRule.Equals("backend", ConfigValue.StringValue("home_assistant")),
                        supportsVariables = true,
                    ),
                    "haToken" to FieldBehavior(
                        visibleWhen = FieldRule.Equals("backend", ConfigValue.StringValue("home_assistant")),
                        supportsVariables = true,
                    ),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = listOf(
                    FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
                    FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
                    FeatureImplementationOption("home_assistant"),
                ),
                keywords = setOf("matter light", "matter", "light", "brightness", "colour", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val result = when (feature.config.string("backend", "chip_tool")) {
                "home_assistant" -> controlHomeAssistant(feature, ctx)
                else -> controlChipTool(feature, ctx)
            }
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, result.value)
            }
            result
        }
    }

    private fun registerLightState(registry: FeatureRegistry) {
        val evaluator = ConditionEvaluator { feature, ctx ->
            val expected = feature.config.string("expected", "on")
            val actual = when (feature.config.string("backend", "chip_tool")) {
                "home_assistant" -> queryHomeAssistantState(feature, ctx)
                else -> queryChipToolState(feature, ctx)
            } ?: return@ConditionEvaluator false
            when (expected) {
                "on" -> actual
                "off" -> !actual
                else -> false
            }
        }
        val fields = listOf(
            FieldSchema.Choice("backend", "Backend", true, listOf("chip_tool", "home_assistant")),
            FieldSchema.Text("deviceId", "Matter node ID / Home Assistant entity ID", true),
            FieldSchema.Number("endpointId", "Matter endpoint ID", min = 0.0, max = 65535.0),
            FieldSchema.Choice("expected", "Expected", true, listOf("on", "off")),
            FieldSchema.Text("chipToolPath", "chip-tool binary path"),
            FieldSchema.Text("haUrl", "Home Assistant base URL"),
            FieldSchema.Text("haToken", "Home Assistant long-lived token"),
        )
        val state = FeatureDescriptor(
            FeatureId("android.state.matter_light"),
            FeatureKind.STATE,
            "Matter light state",
            "Query a Matter light On/Off state",
            FeatureCategory.DEVICE,
            fields = fields,
            capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
            implementationOptions = listOf(
                FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
                FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
                FeatureImplementationOption("home_assistant"),
            ),
            keywords = setOf("matter", "light state", "tasker"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.matter_light"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private suspend fun controlChipTool(
        feature: com.yagay.yauto.core.model.FeatureRef,
        ctx: FeatureExecutionContext,
    ): ActionExecutionResult {
        val node = feature.config.string("deviceId").resolveVariables(ctx.variables).trim()
        if (!NODE_ID.matches(node)) return ActionExecutionResult(false)
        val endpoint = (feature.config["endpointId"].numberOrNull() ?: 1.0).toInt().coerceIn(0, 65535)
        val path = feature.config.string("chipToolPath", "chip-tool")
            .resolveVariables(ctx.variables).trim().ifBlank { "chip-tool" }
        if (!SAFE_PATH.matches(path)) return ActionExecutionResult(false)

        val commands = mutableListOf<String>()
        val set = feature.config.string("set", "toggle")
        commands += shellArg(path) + " onoff " + set + " " + node + " " + endpoint

        feature.config["brightness"].numberOrNull()?.takeIf { it in 0.0..100.0 }?.let { percent ->
            val level = (percent * 254.0 / 100.0).toInt().coerceIn(0, 254)
            commands += shellArg(path) + " levelcontrol move-to-level-with-on-off " +
                level + " 0 0 0 " + node + " " + endpoint
        }

        val color = feature.config.string("color").resolveVariables(ctx.variables).trim()
        parseRgb(color)?.let { (r, g, b) ->
            val (x, y) = rgbToMatterXy(r, g, b)
            commands += shellArg(path) + " colorcontrol move-to-color " +
                x + " " + y + " 0 " + node + " " + endpoint
        }

        var last: com.yagay.yauto.core.capability.CapabilityResult? = null
        for (command in commands) {
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = "system.shell.execute",
                    payload = mapOf("command" to ConfigValue.StringValue(command)),
                )
            )
            last = result
            if (!result.success) {
                return ActionExecutionResult(false, result.value, result.message)
            }
        }
        val value = last?.value ?: ConfigValue.BooleanValue(true)
        return ActionExecutionResult(true, value)
    }

    private suspend fun queryChipToolState(
        feature: com.yagay.yauto.core.model.FeatureRef,
        ctx: FeatureExecutionContext,
    ): Boolean? {
        val node = feature.config.string("deviceId").resolveVariables(ctx.variables).trim()
        if (!NODE_ID.matches(node)) return null
        val endpoint = (feature.config["endpointId"].numberOrNull() ?: 1.0).toInt().coerceIn(0, 65535)
        val path = feature.config.string("chipToolPath", "chip-tool")
            .resolveVariables(ctx.variables).trim().ifBlank { "chip-tool" }
        if (!SAFE_PATH.matches(path)) return null
        val result = ctx.capabilities.execute(
            CapabilityRequest(
                capability = CapabilityIds.PRIVILEGED_SHELL,
                operationId = "system.shell.execute",
                payload = mapOf(
                    "command" to ConfigValue.StringValue(
                        shellArg(path) + " onoff read on-off " + node + " " + endpoint
                    )
                ),
            )
        )
        if (!result.success) return null
        val stdout = ((result.value as? ConfigValue.ObjectValue)?.value?.get("stdout")
            as? ConfigValue.StringValue)?.value.orEmpty()
        return when {
            Regex("""(?i)on.?off[^\n]*(?:true|1)""").containsMatchIn(stdout) -> true
            Regex("""(?i)on.?off[^\n]*(?:false|0)""").containsMatchIn(stdout) -> false
            else -> null
        }
    }

    private suspend fun controlHomeAssistant(
        feature: com.yagay.yauto.core.model.FeatureRef,
        ctx: FeatureExecutionContext,
    ): ActionExecutionResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val entity = feature.config.string("deviceId").resolveVariables(ctx.variables).trim()
        val base = feature.config.string("haUrl").resolveVariables(ctx.variables).trim().trimEnd('/')
        val token = feature.config.string("haToken").resolveVariables(ctx.variables).trim()
        if (!HA_ENTITY.matches(entity) || !base.startsWith("http") || token.isBlank()) {
            return@withContext ActionExecutionResult(false)
        }
        val set = feature.config.string("set", "toggle")
        val service = when (set) {
            "on" -> "turn_on"
            "off" -> "turn_off"
            else -> "toggle"
        }
        val body = buildString {
            append("{\"entity_id\":\"")
            append(jsonEscape(entity))
            append('\"')
            feature.config["brightness"].numberOrNull()?.takeIf { it in 0.0..100.0 }?.let {
                append(",\"brightness_pct\":")
                append(it.toInt())
            }
            parseRgb(feature.config.string("color").resolveVariables(ctx.variables).trim())?.let { rgb ->
                append(",\"rgb_color\":[")
                append(rgb.first).append(',').append(rgb.second).append(',').append(rgb.third)
                append(']')
            }
            append('}')
        }
        val response = http(
            url = base + "/api/services/light/" + service,
            method = "POST",
            token = token,
            body = body,
        )
        ActionExecutionResult(
            response.first in 200..299,
            ConfigValue.ObjectValue(
                mapOf(
                    "status" to ConfigValue.NumberValue(response.first.toDouble()),
                    "body" to ConfigValue.StringValue(response.second),
                )
            ),
        )
    }

    private suspend fun queryHomeAssistantState(
        feature: com.yagay.yauto.core.model.FeatureRef,
        ctx: FeatureExecutionContext,
    ): Boolean? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val entity = feature.config.string("deviceId").resolveVariables(ctx.variables).trim()
        val base = feature.config.string("haUrl").resolveVariables(ctx.variables).trim().trimEnd('/')
        val token = feature.config.string("haToken").resolveVariables(ctx.variables).trim()
        if (!HA_ENTITY.matches(entity) || !base.startsWith("http") || token.isBlank()) return@withContext null
        val response = http(base + "/api/states/" + Uri.encode(entity), "GET", token, null)
        if (response.first !in 200..299) return@withContext null
        val state = Regex("""\"state\"\s*:\s*\"([^\"]+)\"""")
            .find(response.second)?.groupValues?.getOrNull(1)
        when (state) {
            "on" -> true
            "off" -> false
            else -> null
        }
    }

    private fun http(url: String, method: String, token: String, body: String?): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer " + token)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(body) }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRgb(raw: String): Triple<Int, Int, Int>? {
        val value = raw.removePrefix("#")
        val rgb = when (value.length) {
            6 -> value
            8 -> value.takeLast(6)
            else -> return null
        }
        val number = rgb.toIntOrNull(16) ?: return null
        return Triple((number shr 16) and 0xff, (number shr 8) and 0xff, number and 0xff)
    }

    private fun rgbToMatterXy(r: Int, g: Int, b: Int): Pair<Int, Int> {
        fun linear(value: Int): Double {
            val c = value / 255.0
            return if (c > 0.04045) ((c + 0.055) / 1.055).pow(2.4) else c / 12.92
        }
        val rr = linear(r)
        val gg = linear(g)
        val bb = linear(b)
        val x = rr * 0.664511 + gg * 0.154324 + bb * 0.162028
        val y = rr * 0.283881 + gg * 0.668433 + bb * 0.047685
        val z = rr * 0.000088 + gg * 0.072310 + bb * 0.986039
        val sum = x + y + z
        if (sum <= 0.0) return 0 to 0
        return ((x / sum) * 65535.0).toInt().coerceIn(0, 65535) to
            ((y / sum) * 65535.0).toInt().coerceIn(0, 65535)
    }

    private fun shellArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val ACTION_START_COMMISSIONING =
            "com.google.android.gms.home.matter.ACTION_START_COMMISSIONING"
        const val EXTRA_ONBOARDING_PAYLOAD =
            "com.google.android.gms.home.matter.EXTRA_ONBOARDING_PAYLOAD"
        const val EXTRA_VENDOR_ID = "com.google.android.gms.home.matter.EXTRA_VENDOR_ID"
        const val EXTRA_PRODUCT_ID = "com.google.android.gms.home.matter.EXTRA_PRODUCT_ID"

        val NODE_ID = Regex("(?:0x)?[0-9A-Fa-f]{1,16}|[0-9]{1,20}")
        val SAFE_PATH = Regex("[A-Za-z0-9_./-]{1,256}")
        val HA_ENTITY = Regex("[A-Za-z0-9_]+\\.[A-Za-z0-9_]+")
    }
}
