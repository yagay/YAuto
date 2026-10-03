package com.yagay.yauto.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

/** Emits USB device attach/detach changes for the central runtime event pipeline. */
class UsbDeviceEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.usb.devices"
    private val context = context.applicationContext
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val connected = when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> true
                UsbManager.ACTION_USB_DEVICE_DETACHED -> false
                else -> return
            }
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.usb_device_changed",
                    payload = usbSnapshot(device).toPayload() + ("connected" to ConfigValue.BooleanValue(connected)),
                    source = id,
                )
            )
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
        }.onFailure {
            started.set(false)
            this.emitter = null
            throw it
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { context.unregisterReceiver(receiver) }
        emitter = null
    }
}

internal data class UsbDeviceSnapshot(
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val productName: String,
    val manufacturerName: String,
    val deviceClass: Int,
) {
    fun toPayload(): Map<String, ConfigValue> = mapOf(
        "vendorId" to ConfigValue.NumberValue(vendorId.toDouble()),
        "productId" to ConfigValue.NumberValue(productId.toDouble()),
        "deviceName" to ConfigValue.StringValue(deviceName),
        "productName" to ConfigValue.StringValue(productName),
        "manufacturerName" to ConfigValue.StringValue(manufacturerName),
        "deviceClass" to ConfigValue.NumberValue(deviceClass.toDouble()),
    )
}

internal fun usbSnapshot(device: UsbDevice): UsbDeviceSnapshot = UsbDeviceSnapshot(
    vendorId = device.vendorId,
    productId = device.productId,
    deviceName = device.deviceName.orEmpty(),
    productName = runCatching { device.productName.orEmpty() }.getOrDefault(""),
    manufacturerName = runCatching { device.manufacturerName.orEmpty() }.getOrDefault(""),
    deviceClass = device.deviceClass,
)
