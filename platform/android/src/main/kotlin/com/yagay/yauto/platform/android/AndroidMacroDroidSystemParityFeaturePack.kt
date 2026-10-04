package com.yagay.yauto.platform.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidMacroDroidSystemParityFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.macrodroid.system_parity"
    private val context = context.applicationContext
    private val locationPrefs = this.context.getSharedPreferences("yauto_location_runtime", Context.MODE_PRIVATE)

    override fun install(registry: FeatureRegistry) {
        registerAccessibilityService(registry)
        registerOpenLatestMedia(registry)
        registerContactViaApp(registry)
        registerLocationUpdateRate(registry)
        registerConfirmation(registry)
    }

    private fun registerAccessibilityService(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.accessibility_service.set"),
                FeatureKind.ACTION,
                "Set accessibility service",
                "Enable, disable, or toggle a specific accessibility service while preserving all other enabled services",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Text("component", "Service component package/class", true),
                    FieldSchema.Choice("mode", "Mode", true, listOf("enable", "disable", "toggle")),
                ),
                fieldBehaviors = mapOf("component" to FieldBehavior(supportsVariables = true)),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("accessibility service", "enable service", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val component = normalizeComponent(feature.config.string("component").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false)
            val currentResult = privilegedShell(
                ctx,
                "settings get secure enabled_accessibility_services",
            )
            if (!currentResult.success) {
                return@registerAction ActionExecutionResult(false, currentResult.value, currentResult.message)
            }
            val current = capabilityStdout(currentResult)
                .trim()
                .takeUnless { it == "null" }
                .orEmpty()
                .split(':')
                .map(String::trim)
                .filter(String::isNotBlank)
                .toMutableList()
            val exists = current.any { it.equals(component, true) }
            val enabled = when (feature.config.string("mode", "toggle")) {
                "enable" -> true
                "disable" -> false
                else -> !exists
            }
            current.removeAll { it.equals(component, true) }
            if (enabled) current += component
            val unique = current.distinctBy(String::lowercase)
            val services = unique.joinToString(":")
            val commands = listOf(
                "settings put secure enabled_accessibility_services " + shellArg(services),
                "settings put secure accessibility_enabled " + if (unique.isEmpty()) "0" else "1",
            ).joinToString("; ")
            val result = privilegedShell(ctx, commands)
            ActionExecutionResult(
                result.success,
                ConfigValue.ObjectValue(
                    mapOf(
                        "component" to ConfigValue.StringValue(component),
                        "enabled" to ConfigValue.BooleanValue(enabled),
                        "enabledServices" to ConfigValue.ListValue(unique.map(ConfigValue::StringValue)),
                    )
                ),
                result.message,
            )
        }
    }

    private fun registerOpenLatestMedia(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.media.latest.open"),
                FeatureKind.ACTION,
                "Open latest photo/video",
                "Open the newest MediaStore image or video in the selected/default viewer",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Choice("mediaType", "Media", options = listOf("image", "video", "either")),
                    FieldSchema.AppPicker("package", "Viewer package"),
                    FieldSchema.Variable("resultVariable", "Store media URI"),
                ),
                keywords = setOf("last photo", "latest photo", "gallery", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val item = latestMedia(feature.config.string("mediaType", "image"))
                ?: return@registerAction ActionExecutionResult(false)
            val intent = Intent(Intent.ACTION_VIEW, item.first)
                .setDataAndType(item.first, item.second)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val pkg = feature.config.string("package").trim()
            if (pkg.isNotBlank()) intent.setPackage(pkg)
            val launched = runCatching { context.startActivity(intent); true }.getOrDefault(false)
            if (!launched) return@registerAction ActionExecutionResult(false)
            val output = ConfigValue.StringValue(item.first.toString())
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, output)
            }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerContactViaApp(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.contact_via_app.send"),
                FeatureKind.ACTION,
                "Contact via app",
                "Open a selected messaging/social app for a phone/contact with optional prefilled text",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("phone", "Phone / recipient"),
                    FieldSchema.Text("text", "Message", multiline = true),
                    FieldSchema.AppPicker("package", "Target app"),
                    FieldSchema.Choice("mode", "Mode", options = listOf("auto", "send_text", "view_contact", "whatsapp")),
                ),
                fieldBehaviors = mapOf(
                    "phone" to FieldBehavior(supportsVariables = true),
                    "text" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("contact via app", "whatsapp", "message contact", "macrodroid"),
                aliases = setOf("android.whatsapp.send"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val phone = feature.config.string("phone").resolveVariables(ctx.variables).trim()
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            val targetPackage = feature.config.string("package").trim()
            val mode = feature.config.string("mode", "auto")
            val intent = when {
                mode == "whatsapp" || targetPackage == "com.whatsapp" -> {
                    val normalized = phone.filter(Char::isDigit)
                    if (normalized.isBlank()) return@registerAction ActionExecutionResult(false)
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "https://wa.me/" + normalized +
                                if (text.isBlank()) "" else "?text=" + Uri.encode(text)
                        ),
                    )
                }
                mode == "view_contact" && phone.isNotBlank() ->
                    Intent(Intent.ACTION_VIEW, Uri.parse("tel:" + Uri.encode(phone)))
                else -> Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    if (phone.isNotBlank()) putExtra("address", phone)
                }
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (targetPackage.isNotBlank()) intent.setPackage(targetPackage)
            val launched = runCatching { context.startActivity(intent); true }.getOrDefault(false)
            ActionExecutionResult(launched)
        }
    }


    private fun registerConfirmation(registry: FeatureRegistry) {
        val evaluator = ConditionEvaluator { feature, ctx ->
            val token = java.util.UUID.randomUUID().toString()
            val deferred = ConfirmationRuntimeBridge.register(token)
            val intent = Intent()
                .setClassName(context.packageName, "com.yagay.yauto.ConfirmationActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("token", token)
                .putExtra("title", feature.config.string("title").resolveVariables(ctx.variables))
                .putExtra("message", feature.config.string("message").resolveVariables(ctx.variables))
                .putExtra("positive", feature.config.string("positive", "OK").resolveVariables(ctx.variables))
                .putExtra("negative", feature.config.string("negative", "Cancel").resolveVariables(ctx.variables))
            if (!runCatching { context.startActivity(intent); true }.getOrDefault(false)) {
                ConfirmationRuntimeBridge.cancel(token)
                return@ConditionEvaluator false
            }
            val timeout = (feature.config["timeoutMs"].numberOrNull() ?: 60_000.0)
                .toLong().coerceIn(1_000L, 3_600_000L)
            val result = kotlinx.coroutines.withTimeoutOrNull(timeout) { deferred.await() } == true
            ConfirmationRuntimeBridge.cancel(token)
            result
        }
        val descriptor = FeatureDescriptor(
            FeatureId("android.condition.user_confirm"),
            FeatureKind.CONDITION,
            "User confirmation",
            "Show a confirmation dialog and continue only when the user accepts it",
            FeatureCategory.UI_AUTOMATION,
            fields = listOf(
                FieldSchema.Text("title", "Title"),
                FieldSchema.Text("message", "Message", true, multiline = true),
                FieldSchema.Text("positive", "Positive button"),
                FieldSchema.Text("negative", "Negative button"),
                FieldSchema.Duration("timeoutMs", "Timeout"),
            ),
            fieldBehaviors = mapOf(
                "title" to FieldBehavior(supportsVariables = true),
                "message" to FieldBehavior(supportsVariables = true),
                "positive" to FieldBehavior(defaultValue = ConfigValue.StringValue("OK"), supportsVariables = true),
                "negative" to FieldBehavior(defaultValue = ConfigValue.StringValue("Cancel"), supportsVariables = true),
            ),
            keywords = setOf("confirm", "confirmation", "yes no", "macrodroid"),
            ownerPackId = id,
        )
        registry.registerCondition(descriptor, evaluator)
    }

    private fun registerLocationUpdateRate(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.location.update_rate.set"),
                FeatureKind.ACTION,
                "Set global location update rate",
                "Override the minimum interval/distance used by YAuto continuous location and geofence subscriptions",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("mode", "Mode", options = listOf("set", "reset")),
                    FieldSchema.Duration("minimumIntervalMs", "Minimum interval"),
                    FieldSchema.Number("minimumDistanceMeters", "Minimum distance (m)", min = 0.0, max = 100000.0),
                ),
                keywords = setOf("location update rate", "gps rate", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            if (feature.config.string("mode", "set") == "reset") {
                locationPrefs.edit()
                    .remove(KEY_LOCATION_INTERVAL)
                    .remove(KEY_LOCATION_DISTANCE)
                    .apply()
                return@registerAction ActionExecutionResult(true)
            }
            val interval = (feature.config["minimumIntervalMs"].numberOrNull() ?: 30_000.0)
                .toLong().coerceIn(1_000L, 3_600_000L)
            val distance = (feature.config["minimumDistanceMeters"].numberOrNull() ?: 10.0)
                .toFloat().coerceIn(0f, 100_000f)
            locationPrefs.edit()
                .putLong(KEY_LOCATION_INTERVAL, interval)
                .putFloat(KEY_LOCATION_DISTANCE, distance)
                .apply()
            ActionExecutionResult(
                true,
                ConfigValue.ObjectValue(
                    mapOf(
                        "minimumIntervalMs" to ConfigValue.NumberValue(interval.toDouble()),
                        "minimumDistanceMeters" to ConfigValue.NumberValue(distance.toDouble()),
                    )
                )
            )
        }
    }

    private fun latestMedia(type: String): Pair<Uri, String>? {
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val videos = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        fun query(uri: Uri, mimePrefix: String): Triple<Uri, String, Long>? = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED),
                null,
                null,
                MediaStore.MediaColumns.DATE_ADDED + " DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(0)
                val mime = cursor.getString(1).orEmpty().ifBlank { mimePrefix + "/*" }
                val date = cursor.getLong(2)
                Triple(Uri.withAppendedPath(uri, id.toString()), mime, date)
            }
        }.getOrNull()
        return when (type) {
            "video" -> query(videos, "video")?.let { it.first to it.second }
            "either" -> listOfNotNull(query(images, "image"), query(videos, "video"))
                .maxByOrNull { it.third }?.let { it.first to it.second }
            else -> query(images, "image")?.let { it.first to it.second }
        }
    }

    private suspend fun privilegedShell(
        ctx: FeatureExecutionContext,
        command: String,
    ) = ctx.capabilities.execute(
        CapabilityRequest(
            capability = CapabilityIds.PRIVILEGED_SHELL,
            operationId = "system.shell.execute",
            payload = mapOf("command" to ConfigValue.StringValue(command)),
        )
    )

    private fun capabilityStdout(result: com.yagay.yauto.core.capability.CapabilityResult): String =
        ((result.value as? ConfigValue.ObjectValue)?.value?.get("stdout") as? ConfigValue.StringValue)?.value.orEmpty()

    private fun privilegedOptions() = listOf(
        FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
        FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
    )

    private fun normalizeComponent(raw: String): String? {
        val value = raw.trim()
        if (value.matches(COMPONENT)) return value
        val slash = value.indexOf('/')
        if (slash <= 0) return null
        val pkg = value.substring(0, slash)
        val cls = value.substring(slash + 1)
        val normalized = if (cls.startsWith(".")) pkg + cls else cls
        return (pkg + "/" + normalized).takeIf(COMPONENT::matches)
    }

    private fun shellArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        const val PREFS = "yauto_location_runtime"
        const val KEY_LOCATION_INTERVAL = "global_interval_ms"
        const val KEY_LOCATION_DISTANCE = "global_distance_m"
        val COMPONENT = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+")
    }
}
