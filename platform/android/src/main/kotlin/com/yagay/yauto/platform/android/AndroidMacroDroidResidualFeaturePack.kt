package com.yagay.yauto.platform.android

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Environment
import android.provider.MediaStore
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.core.storage.WorkspaceRepository
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

class AndroidMacroDroidResidualFeaturePack(
    context: Context,
    private val workspace: WorkspaceRepository,
) : FeaturePack {
    override val id: String = "android.macrodroid.residual"
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("yauto_compat_settings", Context.MODE_PRIVATE)

    override fun install(registry: FeatureRegistry) {
        registerNotificationLed(registry)
        registerChart(registry)
        registerPinUnlock(registry)
        registerLauncherEntry(registry)
        registerCompatSetting(registry)
        registerAutomationSummary(registry)
    }

    private fun registerNotificationLed(registry: FeatureRegistry) {
        val evaluator = ConditionEvaluator { feature, ctx ->
            val result = shellResult(ctx, "settings get system notification_light_pulse")
            val enabled = stdout(result).trim() == "1"
            enabled == feature.config.boolean("value", true)
        }
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.led.set"),
                FeatureKind.ACTION,
                "Set notification LED",
                "Enable, disable, or toggle the legacy Android notification LED pulse setting",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Choice("mode", "Mode", true, listOf("enable", "disable", "toggle"))),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("notification led", "notification light", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val currentResult = shellResult(ctx, "settings get system notification_light_pulse")
            val current = stdout(currentResult).trim() == "1"
            val enabled = when (feature.config.string("mode", "toggle")) {
                "enable" -> true
                "disable" -> false
                else -> !current
            }
            val value = if (enabled) 1 else 0
            val result = shellResult(
                ctx,
                "settings put system notification_light_pulse $value; " +
                    "settings put secure notification_light_pulse $value 2>/dev/null || true",
            )
            ActionExecutionResult(result.success, ConfigValue.BooleanValue(enabled), result.message)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.notification_led"),
            FeatureKind.STATE,
            "Notification LED enabled",
            "Check the legacy Android notification-light setting",
            FeatureCategory.NOTIFICATION,
            fields = listOf(FieldSchema.Toggle("value", "Enabled")),
            capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
            implementationOptions = privilegedOptions(),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.notification_led"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private fun registerChart(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.chart.create"),
                FeatureKind.ACTION,
                "Create chart image",
                "Render line or column data to a PNG in Downloads/YAuto/Charts",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("values", "Values (comma/newline separated)", true, multiline = true),
                    FieldSchema.Text("labels", "Labels (comma/newline separated)", multiline = true),
                    FieldSchema.Choice("type", "Chart type", options = listOf("line", "column")),
                    FieldSchema.Text("title", "Title"),
                    FieldSchema.Number("width", "Width", min = 240.0, max = 4096.0),
                    FieldSchema.Number("height", "Height", min = 240.0, max = 4096.0),
                    FieldSchema.Text("fileName", "File name"),
                    FieldSchema.Variable("resultVariable", "Store PNG URI"),
                ),
                fieldBehaviors = mapOf(
                    "values" to FieldBehavior(supportsVariables = true),
                    "labels" to FieldBehavior(supportsVariables = true),
                    "title" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("chart", "graph", "line chart", "column chart", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val values = splitList(feature.config.string("values").resolveVariables(ctx.variables))
                .mapNotNull(String::toDoubleOrNull)
            if (values.isEmpty()) return@registerAction ActionExecutionResult(false)
            val labels = splitList(feature.config.string("labels").resolveVariables(ctx.variables))
            val width = (feature.config["width"].numberOrNull() ?: 800.0).toInt().coerceIn(240, 4096)
            val height = (feature.config["height"].numberOrNull() ?: 800.0).toInt().coerceIn(240, 4096)
            val bitmap = renderChart(
                values = values,
                labels = labels,
                title = feature.config.string("title").resolveVariables(ctx.variables),
                type = feature.config.string("type", "column"),
                width = width,
                height = height,
            )
            val fileName = sanitizePngName(
                feature.config.string("fileName").resolveVariables(ctx.variables).ifBlank {
                    "YAuto-chart-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png"
                }
            )
            val uri = savePng(bitmap, fileName)
            bitmap.recycle()
            if (uri == null) return@registerAction ActionExecutionResult(false)
            val output = ConfigValue.StringValue(uri)
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, output)
            }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerPinUnlock(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.keyguard.pin_unlock"),
                FeatureKind.ACTION,
                "Unlock keyguard with PIN",
                "Wake the screen, dismiss keyguard and enter a numeric PIN using privileged input",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Text("pin", "PIN", true)),
                fieldBehaviors = mapOf("pin" to FieldBehavior(supportsVariables = true)),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("pin unlock", "keyguard", "lock screen", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val pin = feature.config.string("pin").resolveVariables(ctx.variables).trim()
            if (!PIN.matches(pin)) return@registerAction ActionExecutionResult(false)
            val result = shellResult(
                ctx,
                "input keyevent KEYCODE_WAKEUP; sleep 0.2; wm dismiss-keyguard; sleep 0.3; " +
                    "input text " + shellArg(pin) + "; input keyevent KEYCODE_ENTER",
            )
            ActionExecutionResult(result.success, message = result.message)
        }
    }

    private fun registerLauncherEntry(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.launcher.entry.set"),
                FeatureKind.ACTION,
                "Set launcher entry",
                "Enable, disable, or toggle an exported launcher activity/alias component",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("component", "Activity/alias component", true),
                    FieldSchema.Choice("mode", "Mode", true, listOf("enable", "disable", "toggle")),
                ),
                fieldBehaviors = mapOf("component" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("launcher icon", "app icon", "alias", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val raw = feature.config.string("component").resolveVariables(ctx.variables).trim()
            val component = android.content.ComponentName.unflattenFromString(raw)
                ?: return@registerAction ActionExecutionResult(false)
            val pm = context.packageManager
            val current = pm.getComponentEnabledSetting(component)
            val currentlyEnabled = current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val enabled = when (feature.config.string("mode", "toggle")) {
                "enable" -> true
                "disable" -> false
                else -> !currentlyEnabled
            }
            runCatching {
                pm.setComponentEnabledSetting(
                    component,
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
                ActionExecutionResult(true, ConfigValue.BooleanValue(enabled))
            }.getOrElse {
                ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: "launcher"))
            }
        }
    }

    private fun registerCompatSetting(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.yauto.setting.set"),
                FeatureKind.ACTION,
                "Set YAuto compatibility setting",
                "Store a named compatibility setting used by imported automation behavior",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Text("key", "Setting key", true),
                    FieldSchema.Text("value", "Value", true),
                ),
                fieldBehaviors = mapOf(
                    "key" to FieldBehavior(supportsVariables = true),
                    "value" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("setting", "preference", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val key = feature.config.string("key").resolveVariables(ctx.variables).trim()
            val value = feature.config.string("value").resolveVariables(ctx.variables)
            if (!SETTING_KEY.matches(key)) return@registerAction ActionExecutionResult(false)
            prefs.edit().putString(key, value).apply()
            ActionExecutionResult(true, ConfigValue.StringValue(value))
        }
        val evaluator = ConditionEvaluator { feature, ctx ->
            val key = feature.config.string("key").resolveVariables(ctx.variables).trim()
            val expected = feature.config.string("value").resolveVariables(ctx.variables)
            key.isNotBlank() && prefs.getString(key, null) == expected
        }
        registry.registerCondition(
            FeatureDescriptor(
                FeatureId("android.condition.yauto_setting"),
                FeatureKind.CONDITION,
                "YAuto compatibility setting",
                "Compare a stored compatibility setting",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Text("key", "Setting key", true),
                    FieldSchema.Text("value", "Expected value", true),
                ),
                fieldBehaviors = mapOf(
                    "key" to FieldBehavior(supportsVariables = true),
                    "value" to FieldBehavior(supportsVariables = true),
                ),
                ownerPackId = id,
            ),
            evaluator,
        )
    }

    private fun registerAutomationSummary(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.automation.summary"),
                FeatureKind.ACTION,
                "Summarise automations",
                "Create a concise local summary of YAuto automations, triggers and action counts",
                FeatureCategory.FLOW,
                fields = listOf(
                    FieldSchema.Text("targetContains", "Automation name contains"),
                    FieldSchema.Variable("resultVariable", "Store summary", true),
                ),
                fieldBehaviors = mapOf("targetContains" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("summarise macros", "automation summary", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val filter = feature.config.string("targetContains").resolveVariables(ctx.variables).trim()
            val data = workspace.load()
            val selected = data.automations.filter {
                filter.isBlank() || it.name.contains(filter, ignoreCase = true)
            }
            val text = buildString {
                append("Automations: ").append(selected.size)
                selected.forEach { automation ->
                    append("\n• ").append(automation.name)
                    append(" [").append(if (automation.enabled) "enabled" else "disabled").append("]")
                    append(" — triggers ").append(automation.activation.events.size)
                    append(", states ").append(automation.activation.states.size)
                    append(", actions ").append(
                        automation.onEnter.size + automation.onEvent.size + automation.onExit.size
                    )
                }
            }
            val output = ConfigValue.StringValue(text)
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
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

    private fun splitList(raw: String): List<String> =
        raw.split(',', '\n', ';').map(String::trim).filter(String::isNotEmpty)

    private fun renderChart(
        values: List<Double>,
        labels: List<String>,
        title: String,
        type: String,
        width: Int,
        height: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = max(18f, width / 36f)
        }
        val margin = max(48f, width / 12f)
        val top = margin + if (title.isBlank()) 0f else textPaint.textSize * 1.8f
        val bottom = height - margin
        val right = width - margin
        val chartWidth = (right - margin).coerceAtLeast(1f)
        val chartHeight = (bottom - top).coerceAtLeast(1f)
        if (title.isNotBlank()) canvas.drawText(title, margin, margin, textPaint)

        val minValue = minOf(0.0, values.minOrNull() ?: 0.0)
        val maxValue = maxOf(0.0, values.maxOrNull() ?: 1.0)
        val span = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
        fun y(value: Double): Float =
            (bottom - ((value - minValue) / span * chartHeight).toFloat())

        paint.color = android.graphics.Color.DKGRAY
        paint.strokeWidth = 2f
        canvas.drawLine(margin, top, margin, bottom, paint)
        canvas.drawLine(margin, y(0.0), right, y(0.0), paint)

        paint.color = android.graphics.Color.rgb(52, 103, 219)
        if (type == "line") {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(3f, width / 220f)
            var previousX = margin
            var previousY = y(values.first())
            values.forEachIndexed { index, value ->
                val x = if (values.size == 1) margin + chartWidth / 2f
                else margin + chartWidth * index / (values.size - 1).toFloat()
                val currentY = y(value)
                if (index > 0) canvas.drawLine(previousX, previousY, x, currentY, paint)
                canvas.drawCircle(x, currentY, paint.strokeWidth * 1.4f, paint)
                previousX = x
                previousY = currentY
            }
        } else {
            paint.style = Paint.Style.FILL
            val slot = chartWidth / values.size.coerceAtLeast(1)
            val barWidth = slot * 0.68f
            values.forEachIndexed { index, value ->
                val x = margin + slot * index + (slot - barWidth) / 2f
                val valueY = y(value)
                val zeroY = y(0.0)
                canvas.drawRect(RectF(x, minOf(valueY, zeroY), x + barWidth, maxOf(valueY, zeroY)), paint)
            }
        }
        if (labels.isNotEmpty()) {
            textPaint.textSize = max(12f, width / 55f)
            labels.take(values.size).forEachIndexed { index, label ->
                val x = margin + chartWidth * (index + 0.5f) / values.size
                canvas.drawText(label.take(16), x - textPaint.measureText(label.take(16)) / 2f, height - margin / 3f, textPaint)
            }
        }
        return bitmap
    }

    private fun savePng(bitmap: Bitmap, fileName: String): String? {
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YAuto/Charts")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: return null
        return runCatching {
            resolver.openOutputStream(uri, "w")?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            } ?: error("Unable to open output")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            uri.toString()
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun sanitizePngName(raw: String): String =
        raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .take(120)
            .ifBlank { "YAuto-chart.png" }
            .let { if (it.endsWith(".png", true)) it else it + ".png" }

    private fun shellArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        val PIN = Regex("[0-9]{4,16}")
        val SETTING_KEY = Regex("[A-Za-z0-9_.-]{1,128}")
    }
}
