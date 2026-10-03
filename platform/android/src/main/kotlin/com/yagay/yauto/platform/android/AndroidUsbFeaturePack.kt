package com.yagay.yauto.platform.android

import android.content.Context
import android.hardware.usb.UsbManager
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.ConditionEvaluator
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

class AndroidUsbFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.usb"
    private val usb = context.applicationContext.getSystemService(UsbManager::class.java)

    override fun install(registry: FeatureRegistry) {
        val fields = usbFilterFields()
        val state = FeatureDescriptor(
            FeatureId("android.state.usb_device_connected"), FeatureKind.STATE,
            "USB device connected", "Check whether a connected USB device matches optional vendor, product, class or name filters",
            FeatureCategory.DEVICE, fields = fields, keywords = setOf("usb", "device", "vendor", "product"), ownerPackId = id,
        )
        val condition = FeatureDescriptor(
            FeatureId("android.condition.usb_device_connected"), FeatureKind.CONDITION,
            "USB device connected", "Check whether a connected USB device matches optional vendor, product, class or name filters",
            FeatureCategory.DEVICE, fields = fields, keywords = setOf("usb", "device", "vendor", "product"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            usb.deviceList.values.any { device -> matchesUsb(feature.config, usbSnapshot(device)) }
        }
        registry.registerState(state, evaluator)
        registry.registerCondition(condition, evaluator)

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.usb_device_changed"), FeatureKind.EVENT,
                "USB device changed", "Run when a USB device is attached or detached and optionally match device identity",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Choice("state", "Connection", options = listOf("any", "attached", "detached"))) + fields,
                keywords = setOf("usb", "attach", "detach", "device"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.usb_device_changed") return@registerEvent false
            val connectionMatches = when (feature.config.string("state", "any")) {
                "attached" -> ctx.event.payload.boolean("connected")
                "detached" -> !ctx.event.payload.boolean("connected")
                else -> true
            }
            if (!connectionMatches) return@registerEvent false
            matchesUsbPayload(feature.config, ctx.event.payload)
        }
    }

    private fun usbFilterFields(): List<FieldSchema> = listOf(
        FieldSchema.Number("vendorId", "Vendor ID", min = 0.0, max = 65_535.0),
        FieldSchema.Number("productId", "Product ID", min = 0.0, max = 65_535.0),
        FieldSchema.Number("deviceClass", "USB device class", min = 0.0, max = 255.0),
        FieldSchema.Text("nameContains", "Device name contains"),
    )
}

internal fun matchesUsb(config: com.yagay.yauto.core.model.ConfigMap, snapshot: UsbDeviceSnapshot): Boolean {
    val vendor = config["vendorId"].numberOrNull()?.toInt()
    val product = config["productId"].numberOrNull()?.toInt()
    val deviceClass = config["deviceClass"].numberOrNull()?.toInt()
    val name = config.string("nameContains").trim()
    if (vendor != null && snapshot.vendorId != vendor) return false
    if (product != null && snapshot.productId != product) return false
    if (deviceClass != null && snapshot.deviceClass != deviceClass) return false
    if (name.isNotBlank() && listOf(snapshot.deviceName, snapshot.productName, snapshot.manufacturerName).none { it.contains(name, ignoreCase = true) }) return false
    return true
}

internal fun matchesUsbPayload(
    config: com.yagay.yauto.core.model.ConfigMap,
    payload: Map<String, com.yagay.yauto.core.model.ConfigValue>,
): Boolean {
    val snapshot = UsbDeviceSnapshot(
        vendorId = payload["vendorId"].numberOrNull()?.toInt() ?: -1,
        productId = payload["productId"].numberOrNull()?.toInt() ?: -1,
        deviceName = payload.string("deviceName"),
        productName = payload.string("productName"),
        manufacturerName = payload.string("manufacturerName"),
        deviceClass = payload["deviceClass"].numberOrNull()?.toInt() ?: -1,
    )
    return matchesUsb(config, snapshot)
}
