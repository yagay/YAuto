package com.yagay.yauto.platform.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.telephony.SubscriptionManager
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AndroidRemainingSourceParityFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.remaining_source_parity"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registerWorkspaceBackup(registry)
        registerMapBusQuery(registry)
        registerSimSlot(registry)
    }

    private fun registerWorkspaceBackup(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.workspace.backup.export"), FeatureKind.ACTION,
                "Export YAuto backup",
                "Export the current YAuto workspace JSON into Downloads",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("destDir", "Destination folder under Downloads"),
                    FieldSchema.Text("fileName", "File name"),
                    FieldSchema.Variable("resultVariable", "Store exported URI"),
                ),
                fieldBehaviors = mapOf(
                    "destDir" to FieldBehavior(defaultValue = ConfigValue.StringValue("YAuto/Backups"), supportsVariables = true),
                    "fileName" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("backup", "export", "workspace", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val workspace = sequenceOf(
                File(context.filesDir, "workspace/workspace.json"),
                File(context.filesDir, "workspace/workspace.json.bak"),
            ).firstOrNull(File::isFile) ?: return@registerAction ActionExecutionResult(false)

            val relative = sanitizeRelativeDir(
                feature.config.string("destDir", "YAuto/Backups").resolveVariables(ctx.variables)
            )
            val requestedName = feature.config.string("fileName").resolveVariables(ctx.variables).trim()
            val fileName = sanitizeFileName(
                requestedName.ifBlank {
                    "YAuto-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json"
                }
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@registerAction ActionExecutionResult(false)
            val ok = runCatching {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    workspace.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Unable to open export output")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                true
            }.getOrElse {
                runCatching { resolver.delete(uri, null, null) }
                false
            }
            if (!ok) return@registerAction ActionExecutionResult(false)
            val output = ConfigValue.StringValue(uri.toString())
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerMapBusQuery(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.maps.bus_query"), FeatureKind.ACTION,
                "Query bus line in map app",
                "Open a selected map app with a city and bus-line search",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("city", "City", true),
                    FieldSchema.Text("bus", "Bus / route", true),
                    FieldSchema.Choice("app", "Map app", options = listOf("system", "google", "gaode", "baidu")),
                ),
                fieldBehaviors = mapOf(
                    "city" to FieldBehavior(supportsVariables = true),
                    "bus" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("map", "bus", "route", "gaode", "baidu", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val city = feature.config.string("city").resolveVariables(ctx.variables).trim()
            val bus = feature.config.string("bus").resolveVariables(ctx.variables).trim()
            if (city.isBlank() || bus.isBlank()) return@registerAction ActionExecutionResult(false)
            val query = Uri.encode(city + " " + bus)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + query))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            when (feature.config.string("app", "system")) {
                "google" -> intent.setPackage("com.google.android.apps.maps")
                "gaode" -> intent.setPackage("com.autonavi.minimap")
                "baidu" -> intent.setPackage("com.baidu.BaiduMap")
            }
            val launched = runCatching {
                context.startActivity(intent)
                true
            }.recoverCatching {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/search/?api=1&query=" + query),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
            ActionExecutionResult(launched)
        }
    }

    private fun registerSimSlot(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.slot.set"), FeatureKind.ACTION,
                "Enable, disable or toggle SIM slot",
                "Resolve a subscription for a physical SIM slot and change its enabled state",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("slotId", "SIM slot ID", true, min = 0.0, max = 7.0),
                    FieldSchema.Choice("state", "State", true, listOf("on", "off", "toggle")),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = listOf(
                    FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
                    FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
                ),
                keywords = setOf("sim", "slot", "subscription", "enable", "disable", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val slot = (feature.config["slotId"].numberOrNull() ?: return@registerAction ActionExecutionResult(false))
                .toInt().coerceIn(0, 7)
            val manager = context.getSystemService(SubscriptionManager::class.java)
            val active = if (
                context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { manager.getActiveSubscriptionInfoForSimSlotIndex(slot) }.getOrNull()
            } else null
            val available = if (
                context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { manager.getAvailableSubscriptionInfoList().orEmpty().firstOrNull { it.getSimSlotIndex() == slot } }.getOrNull()
            } else null

            var subId: Int? = active?.getSubscriptionId() ?: available?.getSubscriptionId()
            var currentlyActive = active != null
            if (subId == null) {
                val dump = shellResult(ctx, "dumpsys isub")
                if (dump.success) {
                    val text = stdout(dump)
                    val block = Regex(
                        """(?is)(?:slotIndex|simSlotIndex)\s*[=:]\s*""" + slot + """.{0,600}?""",
                    ).find(text)?.value.orEmpty()
                    subId = Regex("""(?:subId|subscriptionId|id)\s*[=:]\s*(-?\d+)""")
                        .find(block)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    currentlyActive = block.contains("active", true) && !block.contains("inactive", true)
                }
            }
            if (subId == null || subId < 0) return@registerAction ActionExecutionResult(false)

            val enable = when (feature.config.string("state", "toggle")) {
                "on" -> true
                "off" -> false
                else -> !currentlyActive
            }
            val verb = if (enable) "enable-physical-subscription" else "disable-physical-subscription"
            val result = shellResult(ctx, "cmd phone " + verb + " " + subId)
            ActionExecutionResult(
                result.success,
                ConfigValue.ObjectValue(
                    mapOf(
                        "slotId" to ConfigValue.NumberValue(slot.toDouble()),
                        "subscriptionId" to ConfigValue.NumberValue(subId.toDouble()),
                        "enabled" to ConfigValue.BooleanValue(enable),
                    )
                ),
                result.message,
            )
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

    private fun sanitizeRelativeDir(raw: String): String =
        raw.replace('\\', '/')
            .split('/')
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
            .ifBlank { "YAuto/Backups" }
            .take(180)

    private fun sanitizeFileName(raw: String): String {
        val clean = raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().take(120)
        return clean.ifBlank { "YAuto-backup.json" }.let { if (it.endsWith(".json", true)) it else it + ".json" }
    }
}
