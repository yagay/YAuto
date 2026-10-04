package com.yagay.yauto.platform.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AccessibilityInspectorFeaturePack(context: Context) : FeaturePack {
    override val id: String = "accessibility.inspectors"
    private val overlay = AccessibilityInspectorOverlay(context.applicationContext)

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("accessibility.universal_copy.show"), FeatureKind.ACTION,
                "Universal Copy",
                "Show a scrollable overlay of visible Accessibility text; tap an item to copy it",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Toggle("includeDescriptions", "Include content descriptions"),
                    FieldSchema.Number("limit", "Maximum nodes", min = 1.0, max = 2000.0),
                ),
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                accessRequirements = setOf(AccessRequirement.ACCESSIBILITY, AccessRequirement.OVERLAY),
                keywords = setOf("universal copy", "copy screen text", "shortx", "accessibility"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val service = YAutoAccessibilityService.current ?: return@registerAction ActionExecutionResult(false)
            val ok = overlay.showUniversalCopy(
                service = service,
                includeDescriptions = feature.config.boolean("includeDescriptions", true),
                limit = (feature.config["limit"] as? ConfigValue.NumberValue)?.value?.toInt() ?: 500,
            )
            ActionExecutionResult(ok)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("accessibility.view_id_viewer.show"), FeatureKind.ACTION,
                "View ID Viewer",
                "Show visible Accessibility nodes with View ID, text, class and bounds; tap an item to copy its View ID",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Toggle("clickableOnly", "Only clickable nodes"),
                    FieldSchema.Number("limit", "Maximum nodes", min = 1.0, max = 2000.0),
                ),
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                accessRequirements = setOf(AccessRequirement.ACCESSIBILITY, AccessRequirement.OVERLAY),
                keywords = setOf("view id viewer", "resource id", "inspect ui", "shortx", "accessibility"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val service = YAutoAccessibilityService.current ?: return@registerAction ActionExecutionResult(false)
            val ok = overlay.showViewIds(
                service = service,
                clickableOnly = feature.config.boolean("clickableOnly", false),
                limit = (feature.config["limit"] as? ConfigValue.NumberValue)?.value?.toInt() ?: 500,
            )
            ActionExecutionResult(ok)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("accessibility.inspector.hide"), FeatureKind.ACTION,
                "Hide Accessibility inspector",
                "Close the Universal Copy / View ID Viewer overlay",
                FeatureCategory.UI_AUTOMATION,
                keywords = setOf("universal copy", "view id viewer", "hide overlay"),
                ownerPackId = id,
            )
        ) { _, _ ->
            overlay.hide()
            ActionExecutionResult(true)
        }

        val evaluator = ConditionEvaluator { feature, _ ->
            overlay.isShown() == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("accessibility.state.inspector_shown"), FeatureKind.STATE,
            "Accessibility inspector visible",
            "Check whether the Universal Copy / View ID Viewer overlay is currently shown",
            FeatureCategory.UI_AUTOMATION,
            fields = listOf(FieldSchema.Toggle("value", "Shown")),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("accessibility.condition.inspector_shown"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }
}

private class AccessibilityInspectorOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    @Volatile private var currentView: android.view.View? = null

    fun isShown(): Boolean = currentView != null

    fun hide() {
        val view = currentView ?: return
        currentView = null
        runCatching { windowManager.removeView(view) }
    }

    fun showUniversalCopy(
        service: YAutoAccessibilityService,
        includeDescriptions: Boolean,
        limit: Int,
    ): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        val values = LinkedHashSet<String>()
        service.uiNodes(limit.coerceIn(1, 2000), onlyVisible = true, clickableOnly = false).forEach { node ->
            node.text.trim().takeIf { it.isNotEmpty() }?.let(values::add)
            if (includeDescriptions) node.contentDescription.trim().takeIf { it.isNotEmpty() }?.let(values::add)
        }
        if (values.isEmpty()) return false
        return showList(
            title = "Universal Copy",
            rows = values.map { text -> InspectorRow(text, text) },
            copyLabel = "text",
        )
    }

    fun showViewIds(
        service: YAutoAccessibilityService,
        clickableOnly: Boolean,
        limit: Int,
    ): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        val rows = service.uiNodes(limit.coerceIn(1, 2000), onlyVisible = true, clickableOnly = clickableOnly)
            .mapNotNull { node ->
                val viewId = node.viewId.trim()
                if (viewId.isEmpty()) return@mapNotNull null
                val detail = buildString {
                    append(viewId)
                    if (node.text.isNotBlank()) append("\n").append(node.text)
                    if (node.contentDescription.isNotBlank()) append("\n[").append(node.contentDescription).append("]")
                    if (node.className.isNotBlank()) append("\n").append(node.className)
                    append("\n")
                    append(if (node.clickable) "clickable" else "not-clickable")
                }
                InspectorRow(detail, viewId)
            }
        if (rows.isEmpty()) return false
        return showList("View ID Viewer", rows, "View ID")
    }

    private fun showList(title: String, rows: List<InspectorRow>, copyLabel: String): Boolean {
        hide()
        val density = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(0xF2202124.toInt())
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(Button(context).apply {
            text = "×"
            setOnClickListener { hide() }
        })
        root.addView(header)

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rows.forEachIndexed { index, row ->
            list.addView(TextView(context).apply {
                text = row.display
                setTextColor(0xFFE8EAED.toInt())
                textSize = 14f
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                setOnClickListener {
                    clipboard.setPrimaryClip(ClipData.newPlainText(copyLabel, row.copyValue))
                    setTextColor(0xFF8AB4F8.toInt())
                }
                contentDescription = "Item " + (index + 1)
            })
        }
        root.addView(
            ScrollView(context).apply { addView(list) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val params = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.80f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        return runCatching {
            windowManager.addView(root, params)
            currentView = root
            true
        }.getOrDefault(false)
    }

    private data class InspectorRow(val display: String, val copyValue: String)
}
