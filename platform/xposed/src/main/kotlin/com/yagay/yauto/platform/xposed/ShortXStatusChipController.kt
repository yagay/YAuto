package com.yagay.yauto.platform.xposed

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * A real child of PhoneStatusBarView in the hooked SystemUI process.
 * No application overlay or fake notification is involved.
 *
 * The view and its layout are ROM-specific. An acknowledgement means that the
 * command was accepted by SystemUI, not a guarantee that the OEM view is visible.
 */
internal class ShortXStatusChipController(
    private val context: Context,
    private val onGesture: (String, String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val views = WeakHashMap<FrameLayout, WeakReference<LinearLayout>>()
    private var id = ""
    private var label = ""
    private var iconName = ""
    private var png: ByteArray? = null
    private var visible = false

    fun update(operation: String, chipId: String, text: String, iconMode: String, icon: String, pngBase64: String) {
        require(Regex("[a-z][a-z0-9_]{0,23}").matches(chipId)) { "Invalid chip ID" }
        require(operation in setOf(SystemBridgeProtocol.STATUS_CHIP_SHOW, SystemBridgeProtocol.STATUS_CHIP_HIDE)) {
            "Unknown status chip operation"
        }
        require(operation == SystemBridgeProtocol.STATUS_CHIP_HIDE || (text.isNotBlank() && text.length <= 48)) {
            "Status chip text invalid"
        }
        require(iconMode in setOf("none", "android_drawable", "png_file")) { "Unknown icon method" }
        require(iconMode != "android_drawable" || Regex("[a-z][a-z0-9_]{0,63}").matches(icon)) {
            "Invalid drawable"
        }
        val image = if (operation == SystemBridgeProtocol.STATUS_CHIP_SHOW && iconMode == "png_file") {
            require(pngBase64.length in 12..90_000) { "PNG too large" }
            val bytes = Base64.decode(pngBase64, Base64.NO_WRAP)
            require(bytes.size in 8..65_536 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "Invalid PNG bytes" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..128 && bounds.outHeight in 1..128) { "Image dimensions invalid" }
            bytes
        } else null
        handler.post {
            if (operation == SystemBridgeProtocol.STATUS_CHIP_HIDE) {
                if (chipId != id && chipId != "shortx") return@post
                visible = false
            } else {
                id = chipId
                label = text
                iconName = if (iconMode == "android_drawable") icon else ""
                png = image
                visible = true
            }
            updateViews()
        }
    }

    fun attach(rawView: Any?) {
        val container = rawView as? FrameLayout ?: return
        handler.post {
            if (views[container]?.get() != null) return@post
            val density = container.resources.displayMetrics.density
            val chip = LinearLayout(container.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val h = (5 * density).toInt()
                val v = (2 * density).toInt()
                setPadding(h + 3, v, h + 3, v)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(62, 77, 91))
                    cornerRadius = 10 * density
                }
                isClickable = true
                setOnClickListener { if (visible) onGesture(id, "click") }
                setOnLongClickListener {
                    if (visible) { onGesture(id, "long_click"); true } else false
                }
                visibility = View.GONE
                tag = "yauto.shortx.status_chip"
            }
            container.addView(chip, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL,
            ))
            views[container] = WeakReference(chip)
            updateViews()
        }
    }

    private fun updateViews() {
        val iterator = views.entries.iterator()
        while (iterator.hasNext()) {
            val (root, ref) = iterator.next()
            val chip = ref.get()
            if (chip == null || chip.parent !== root) { iterator.remove(); continue }
            chip.removeAllViews()
            chip.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible) continue
            val img = ImageView(root.context)
            when {
                png != null -> {
                    val bytes = png!!
                    img.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                }
                iconName.isNotBlank() -> {
                    val drawable = root.resources.getIdentifier(iconName, "drawable", "android")
                    if (drawable != 0) img.setImageResource(drawable)
                }
            }
            if (img.drawable != null) {
                val size = (15 * root.resources.displayMetrics.density).toInt()
                chip.addView(img, LinearLayout.LayoutParams(size, size))
            }
            chip.addView(TextView(root.context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 11f
                setSingleLine(true)
                maxEms = 15
                gravity = Gravity.CENTER_VERTICAL
            })
        }
    }
}
