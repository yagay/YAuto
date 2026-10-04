package com.yagay.yauto.platform.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class AndroidLegacyParityFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.legacy_parity"
    private val context = context.applicationContext
    private val sensors = this.context.getSystemService(SensorManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerCallLogClear(registry)
        registerSmsDelete(registry)
        registerIme(registry)
        registerAppLocale(registry)
        registerAssistant(registry)
        registerDemoMode(registry)
        registerSensorRead(registry)
        registerSensorCondition(registry)
    }

    private fun registerCallLogClear(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.call_log.clear"), FeatureKind.ACTION,
                "Clear call log",
                "Delete call-log rows using the Android content provider through Root or Shizuku",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Choice("mode", "Delete", true, listOf("all", "number", "id")),
                    FieldSchema.Text("number", "Phone number"),
                    FieldSchema.Number("id", "Row ID", min = 0.0),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("call log", "delete calls", "clear history", "macrodroid", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val command = when (feature.config.string("mode", "all")) {
                "number" -> {
                    val number = feature.config.string("number").trim()
                    if (!PHONE.matches(number)) return@registerAction ActionExecutionResult(false)
                    "content delete --uri content://call_log/calls --where " +
                        shellArg("number='" + number.replace("'", "''") + "'")
                }
                "id" -> {
                    val rowId = feature.config["id"].numberOrNull()?.toLong()
                        ?: return@registerAction ActionExecutionResult(false)
                    "content delete --uri content://call_log/calls --where " + shellArg("_id=" + rowId)
                }
                else -> "content delete --uri content://call_log/calls"
            }
            shell(ctx, command)
        }
    }

    private fun registerSmsDelete(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sms.delete"), FeatureKind.ACTION,
                "Delete SMS",
                "Delete SMS provider rows using Root or Shizuku",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Choice("mode", "Delete", true, listOf("all", "address", "id")),
                    FieldSchema.Text("address", "Sender / recipient"),
                    FieldSchema.Number("id", "Message ID", min = 0.0),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("sms", "delete message", "clear messages", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val command = when (feature.config.string("mode", "all")) {
                "address" -> {
                    val address = feature.config.string("address").trim()
                    if (!PHONE.matches(address)) return@registerAction ActionExecutionResult(false)
                    "content delete --uri content://sms --where " +
                        shellArg("address='" + address.replace("'", "''") + "'")
                }
                "id" -> {
                    val rowId = feature.config["id"].numberOrNull()?.toLong()
                        ?: return@registerAction ActionExecutionResult(false)
                    "content delete --uri content://sms --where " + shellArg("_id=" + rowId)
                }
                else -> "content delete --uri content://sms"
            }
            shell(ctx, command)
        }
    }

    private fun registerIme(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.ime.set"), FeatureKind.ACTION,
                "Set input method",
                "Switch to an installed Android input method by IME ID",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Text("imeId", "IME ID package/service", true)),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("keyboard", "ime", "input method", "macrodroid", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val ime = feature.config.string("imeId").resolveVariables(ctx.variables).trim()
            if (!COMPONENT.matches(ime)) return@registerAction ActionExecutionResult(false)
            shell(ctx, "ime set " + shellArg(ime))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.ime.list"), FeatureKind.ACTION,
                "List input methods",
                "Return installed Android IME IDs",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store IME list", true)),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("keyboard", "ime", "input method"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val result = shellResult(ctx, "ime list -s")
            if (!result.success) return@registerAction ActionExecutionResult(false, result.value, result.message)
            val values = stdout(result).lineSequence().map(String::trim).filter(String::isNotBlank)
                .map { ConfigValue.StringValue(it) }.toList()
            val output = ConfigValue.ListValue(values)
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerAppLocale(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.locale.set"), FeatureKind.ACTION,
                "Set app language",
                "Set or clear Android per-app locale tags for an installed package",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Text("locales", "BCP-47 locale tags (blank = system default)"),
                    FieldSchema.Number("userId", "Android user ID", min = 0.0),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("app language", "locale", "language", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val pkg = feature.config.string("package").trim()
            if (!PACKAGE.matches(pkg)) return@registerAction ActionExecutionResult(false)
            val locales = feature.config.string("locales").resolveVariables(ctx.variables).trim()
            if (locales.isNotBlank() && !LOCALES.matches(locales)) return@registerAction ActionExecutionResult(false)
            val user = (feature.config["userId"].numberOrNull() ?: 0.0).toInt().coerceAtLeast(0)
            shell(
                ctx,
                "cmd locale set-app-locales " + shellArg(pkg) + " --user " + user + " " + shellArg(locales),
            )
        }
    }

    private fun registerAssistant(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.assistant.set"), FeatureKind.ACTION,
                "Set digital assistant",
                "Set the Android ASSISTANT role holder package through RoleManager shell",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.AppPicker("package", "Assistant package", true),
                    FieldSchema.Number("userId", "Android user ID", min = 0.0),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("assistant", "digital assistant", "role", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val pkg = feature.config.string("package").trim()
            if (!PACKAGE.matches(pkg)) return@registerAction ActionExecutionResult(false)
            val user = (feature.config["userId"].numberOrNull() ?: 0.0).toInt().coerceAtLeast(0)
            shell(
                ctx,
                "cmd role add-role-holder --user " + user + " android.app.role.ASSISTANT " + shellArg(pkg),
            )
        }
    }

    private fun registerDemoMode(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.systemui.demo"), FeatureKind.ACTION,
                "SystemUI Demo Mode",
                "Enter, exit or send a command to Android SystemUI Demo Mode",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Choice(
                        "command", "Command", true,
                        listOf("enter", "exit", "clock", "battery", "network", "bars", "notifications", "status"),
                    ),
                    FieldSchema.Text("extras", "Extra key=value pairs, one per line", multiline = true),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("systemui", "demo mode", "status bar", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val command = feature.config.string("command", "enter")
            if (command !in DEMO_COMMANDS) return@registerAction ActionExecutionResult(false)
            val extras = feature.config.string("extras").lineSequence()
                .mapNotNull { line ->
                    val split = line.split('=', limit = 2)
                    if (split.size != 2) null
                    else split[0].trim().takeIf(KEY::matches)?.let { it to split[1].trim() }
                }
                .take(20)
                .joinToString(" ") { (key, value) -> "-e " + key + " " + shellArg(value) }
            val allow = if (command == "enter") "settings put global sysui_demo_allowed 1; " else ""
            shell(
                ctx,
                allow + "am broadcast -a com.android.systemui.demo -e command " + shellArg(command) +
                    if (extras.isBlank()) "" else " " + extras,
            )
        }
    }


    private fun registerSensorCondition(registry: FeatureRegistry) {
        val fields = listOf(
            FieldSchema.Choice(
                "sensor", "Sensor", true,
                listOf(
                    "light", "proximity", "accelerometer", "gyroscope", "magnetic_field",
                    "pressure", "gravity", "linear_acceleration", "rotation_vector",
                    "relative_humidity", "ambient_temperature",
                ),
            ),
            FieldSchema.Number("valueIndex", "Value index", min = 0.0, max = 15.0),
            FieldSchema.Choice("operator", "Operator", true, listOf("<", "<=", "==", ">=", ">")),
            FieldSchema.Number("value", "Compare value", true),
            FieldSchema.Number("tolerance", "Equality tolerance", min = 0.0),
            FieldSchema.Duration("timeoutMs", "Sample timeout"),
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val type = SENSOR_TYPES[feature.config.string("sensor")]
                ?: return@ConditionEvaluator false
            val sensor = sensors.getDefaultSensor(type) ?: return@ConditionEvaluator false
            val timeout = (feature.config["timeoutMs"].numberOrNull() ?: 2_000.0)
                .toLong().coerceIn(100L, 30_000L)
            val sample = withTimeoutOrNull(timeout) { readSensor(sensor) }
                ?: return@ConditionEvaluator false
            val index = (feature.config["valueIndex"].numberOrNull() ?: 0.0).toInt()
            val actual = sample.values.getOrNull(index)?.toDouble() ?: return@ConditionEvaluator false
            val expected = feature.config["value"].numberOrNull() ?: return@ConditionEvaluator false
            val tolerance = feature.config["tolerance"].numberOrNull()?.coerceAtLeast(0.0) ?: 0.01
            when (feature.config.string("operator", "==")) {
                "<" -> actual < expected
                "<=" -> actual <= expected
                "==" -> kotlin.math.abs(actual - expected) <= tolerance
                ">=" -> actual >= expected
                ">" -> actual > expected
                else -> false
            }
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.sensor_value"),
            FeatureKind.STATE,
            "Sensor value",
            "Sample an Android sensor and compare one value",
            FeatureCategory.DEVICE,
            fields = fields,
            keywords = setOf("sensor", "light", "proximity", "value", "macrodroid"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.sensor_value"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private fun registerSensorRead(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sensor.read"), FeatureKind.ACTION,
                "Read sensor once",
                "Read one sample from an Android hardware sensor and store its values",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice(
                        "sensor", "Sensor", true,
                        listOf(
                            "light", "proximity", "accelerometer", "gyroscope", "magnetic_field",
                            "pressure", "gravity", "linear_acceleration", "rotation_vector",
                            "relative_humidity", "ambient_temperature",
                        ),
                    ),
                    FieldSchema.Duration("timeoutMs", "Timeout"),
                    FieldSchema.Variable("resultVariable", "Store sensor sample", true),
                ),
                keywords = setOf("sensor", "light level", "proximity", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val type = SENSOR_TYPES[feature.config.string("sensor")]
                ?: return@registerAction ActionExecutionResult(false)
            val sensor = sensors.getDefaultSensor(type)
                ?: return@registerAction ActionExecutionResult(false)
            val timeout = (feature.config["timeoutMs"].numberOrNull() ?: 5_000.0)
                .toLong().coerceIn(100L, 60_000L)
            val sample = withTimeoutOrNull(timeout) { readSensor(sensor) }
                ?: return@registerAction ActionExecutionResult(false)
            val output = ConfigValue.ObjectValue(
                buildMap {
                    put("sensorType", ConfigValue.NumberValue(sensor.type.toDouble()))
                    put("name", ConfigValue.StringValue(sensor.name))
                    put("vendor", ConfigValue.StringValue(sensor.vendor))
                    put("accuracy", ConfigValue.NumberValue(sample.accuracy.toDouble()))
                    put("timestampNs", ConfigValue.NumberValue(sample.timestamp.toDouble()))
                    put("values", ConfigValue.ListValue(sample.values.map { ConfigValue.NumberValue(it.toDouble()) }))
                }
            )
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private suspend fun readSensor(sensor: Sensor): SensorSample? =
        suspendCancellableCoroutine { continuation ->
            lateinit var listener: SensorEventListener
            listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    sensors.unregisterListener(this)
                    if (continuation.isActive) {
                        continuation.resume(
                            SensorSample(event.values.copyOf(), event.accuracy, event.timestamp)
                        )
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            val ok = sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            if (!ok) {
                if (continuation.isActive) continuation.resume(null)
            } else {
                continuation.invokeOnCancellation { sensors.unregisterListener(listener) }
            }
        }

    private suspend fun shell(ctx: FeatureExecutionContext, command: String): ActionExecutionResult {
        val result = shellResult(ctx, command)
        return ActionExecutionResult(result.success, result.value, result.message)
    }

    private suspend fun shellResult(ctx: FeatureExecutionContext, command: String) =
        ctx.capabilities.execute(
            CapabilityRequest(
                capability = CapabilityIds.PRIVILEGED_SHELL,
                operationId = "system.shell.execute",
                payload = mapOf("command" to ConfigValue.StringValue(command)),
            )
        )

    private fun stdout(result: com.yagay.yauto.core.capability.CapabilityResult): String =
        ((result.value as? ConfigValue.ObjectValue)?.value?.get("stdout") as? ConfigValue.StringValue)?.value.orEmpty()

    private fun privilegedOptions() = listOf(
        FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
        FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
    )

    private fun shellArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private data class SensorSample(
        val values: FloatArray,
        val accuracy: Int,
        val timestamp: Long,
    )

    private companion object {
        val PHONE = Regex("[+0-9*#() .-]{1,64}")
        val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        val COMPONENT = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+")
        val LOCALES = Regex("[A-Za-z0-9,-]{0,128}")
        val KEY = Regex("[A-Za-z0-9_.-]{1,64}")
        val DEMO_COMMANDS = setOf("enter", "exit", "clock", "battery", "network", "bars", "notifications", "status")
        val SENSOR_TYPES = mapOf(
            "light" to Sensor.TYPE_LIGHT,
            "proximity" to Sensor.TYPE_PROXIMITY,
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD,
            "pressure" to Sensor.TYPE_PRESSURE,
            "gravity" to Sensor.TYPE_GRAVITY,
            "linear_acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
            "rotation_vector" to Sensor.TYPE_ROTATION_VECTOR,
            "relative_humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
            "ambient_temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
        )
    }
}
