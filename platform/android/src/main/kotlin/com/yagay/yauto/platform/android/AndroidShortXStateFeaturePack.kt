package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidShortXStateFeaturePack : FeaturePack {
    override val id = "android.shortx.states"

    override fun install(registry: FeatureRegistry) {
        registerTaskPresent(registry)
        registerNotificationPanelExpanded(registry)
        registerImeVisible(registry)
        registerBluetoothConnected(registry)
    }

    private fun registerTaskPresent(registry: FeatureRegistry) {
        val descriptor = FeatureDescriptor(
            FeatureId("android.condition.task_present"), FeatureKind.CONDITION,
            "App task present",
            "Check whether ActivityManager currently has a task/activity record for a package",
            FeatureCategory.APP,
            fields = listOf(
                FieldSchema.AppPicker("package", "App / package", true),
                FieldSchema.Toggle("value", "Task present"),
            ),
            capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
            implementationOptions = privilegedOptions(),
            keywords = setOf("task", "recent task", "activity task", "shortx"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val pkg = feature.config.string("package").trim()
            if (!PACKAGE.matches(pkg)) return@ConditionEvaluator false
            val result = shell(ctx, "dumpsys activity activities")
            val text = stdout(result)
            val present = result.success && Regex("(?m)(?:A=|packageName=|cmp=|mResumedActivity:.*|Task\\{.*)" + Regex.escape(pkg))
                .containsMatchIn(text)
            present == feature.config.boolean("value", true)
        }
        registry.registerCondition(descriptor, evaluator)
        registry.registerState(
            descriptor.copy(id = FeatureId("android.state.task_present"), kind = FeatureKind.STATE),
            evaluator,
        )
    }

    private fun registerNotificationPanelExpanded(registry: FeatureRegistry) {
        val descriptor = FeatureDescriptor(
            FeatureId("android.condition.notification_panel_expanded"), FeatureKind.CONDITION,
            "Notification panel expanded",
            "Check SystemUI/status-bar dump state for an expanded notification or quick-settings panel",
            FeatureCategory.SYSTEM,
            fields = listOf(FieldSchema.Toggle("value", "Expanded")),
            capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
            implementationOptions = privilegedOptions(),
            keywords = setOf("notification panel", "shade", "expanded", "quick settings", "shortx"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val result = shell(ctx, "dumpsys statusbar")
            val text = stdout(result)
            val expanded = result.success && listOf(
                "mExpandedVisible=true",
                "expanded=true",
                "isExpanded=true",
                "panelExpanded=true",
                "isFullyExpanded=true",
                "mQsExpanded=true",
            ).any { text.contains(it, ignoreCase = true) }
            expanded == feature.config.boolean("value", true)
        }
        registry.registerCondition(descriptor, evaluator)
        registry.registerState(
            descriptor.copy(id = FeatureId("android.state.notification_panel_expanded"), kind = FeatureKind.STATE),
            evaluator,
        )
    }

    private fun registerImeVisible(registry: FeatureRegistry) {
        val descriptor = FeatureDescriptor(
            FeatureId("android.condition.ime_visible"), FeatureKind.CONDITION,
            "Input method visible",
            "Check whether Android currently reports the software input method as shown or requested",
            FeatureCategory.UI_AUTOMATION,
            fields = listOf(FieldSchema.Toggle("value", "Visible")),
            capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
            implementationOptions = privilegedOptions(),
            keywords = setOf("ime", "keyboard", "input method", "visible", "shortx"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val result = shell(ctx, "dumpsys input_method")
            val text = stdout(result)
            val visible = result.success && listOf(
                "mInputShown=true",
                "mShowRequested=true",
                "isInputViewShown=true",
                "mWindowVisible=true",
                "inputShown=true",
            ).any { text.contains(it, ignoreCase = true) }
            visible == feature.config.boolean("value", true)
        }
        registry.registerCondition(descriptor, evaluator)
        registry.registerState(
            descriptor.copy(id = FeatureId("android.state.ime_visible"), kind = FeatureKind.STATE),
            evaluator,
        )
    }

    private fun registerBluetoothConnected(registry: FeatureRegistry) {
        val descriptor = FeatureDescriptor(
            FeatureId("android.condition.bluetooth_device_connected"), FeatureKind.CONDITION,
            "Bluetooth device connected",
            "Check Bluetooth manager dump state for a connected device, optionally filtering address or name",
            FeatureCategory.NETWORK,
            fields = listOf(
                FieldSchema.Text("address", "Bluetooth address"),
                FieldSchema.Text("nameContains", "Device name contains"),
                FieldSchema.Toggle("value", "Connected"),
            ),
            capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
            implementationOptions = privilegedOptions(),
            keywords = setOf("bluetooth", "device connected", "shortx", "headset"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val result = shell(ctx, "dumpsys bluetooth_manager")
            if (!result.success) return@ConditionEvaluator false
            val text = stdout(result)
            val address = feature.config.string("address").trim()
            val name = feature.config.string("nameContains").trim()
            val blocks = text.lineSequence()
                .windowed(size = 5, step = 1, partialWindows = true)
                .map { it.joinToString("\n") }
            val connected = blocks.any { block ->
                val connectedState =
                    block.contains("CONNECTED", ignoreCase = true) ||
                    block.contains("state=2", ignoreCase = true) ||
                    block.contains("connectionState=2", ignoreCase = true)
                connectedState &&
                    (address.isBlank() || block.contains(address, ignoreCase = true)) &&
                    (name.isBlank() || block.contains(name, ignoreCase = true))
            }
            connected == feature.config.boolean("value", true)
        }
        registry.registerCondition(descriptor, evaluator)
        registry.registerState(
            descriptor.copy(id = FeatureId("android.state.bluetooth_device_connected"), kind = FeatureKind.STATE),
            evaluator,
        )
    }

    private suspend fun shell(ctx: FeatureExecutionContext, command: String) =
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

    private companion object {
        val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}
