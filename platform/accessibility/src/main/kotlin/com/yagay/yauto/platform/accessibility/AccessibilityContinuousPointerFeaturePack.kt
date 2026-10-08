package com.yagay.yauto.platform.accessibility

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

class AccessibilityContinuousPointerFeaturePack(context: Context) : FeaturePack {
    override val id: String = "accessibility.continuous_pointer"
    private val controller = ContinuousPointerController(context.applicationContext)

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("accessibility.pointer.show"), FeatureKind.ACTION,
                "Continuous gesture pointer",
                "Show a full-screen virtual pointer controlled by finger movement and dispatch Accessibility taps/scrolls",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Number("sensitivityScale", "Pointer sensitivity", min = 0.1, max = 5.0),
                    FieldSchema.Toggle("clickOnRelease", "Click pointer location on release"),
                    FieldSchema.Choice("scrollMode", "Scroll mode", options = listOf("none", "vertical", "horizontal", "both")),
                    FieldSchema.Number("scrollScale", "Scroll sensitivity", min = 0.1, max = 5.0),
                    FieldSchema.Number("cancelRegionRadiusDp", "Center cancel radius dp", min = 0.0, max = 240.0),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.ACCESSIBILITY, AccessRequirement.OVERLAY),
                keywords = setOf("continuous gesture pointer", "virtual pointer", "edge gesture", "shortx"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val service = YAutoAccessibilityService.current ?: return@registerAction ActionExecutionResult(false)
            val ok = controller.show(
                service = service,
                sensitivity = (feature.config["sensitivityScale"].numberOrNull() ?: 1.0).toFloat(),
                clickOnRelease = feature.config.boolean("clickOnRelease", true),
                scrollMode = feature.config.string("scrollMode", "none"),
                scrollScale = (feature.config["scrollScale"].numberOrNull() ?: 1.0).toFloat(),
                cancelRadiusDp = (feature.config["cancelRegionRadiusDp"].numberOrNull() ?: 36.0).toFloat(),
                autoHideMs = (feature.config["autoHideMs"].numberOrNull() ?: 0.0).toLong(),
            )
            ActionExecutionResult(ok)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("accessibility.pointer.hide"), FeatureKind.ACTION,
                "Hide continuous gesture pointer",
                "Close the virtual gesture pointer overlay",
                FeatureCategory.UI_AUTOMATION,
                keywords = setOf("gesture pointer", "hide", "shortx"),
                ownerPackId = id,
            )
        ) { _, _ ->
            controller.hide()
            ActionExecutionResult(true)
        }

        val evaluator = ConditionEvaluator { feature, _ ->
            controller.isShown() == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("accessibility.state.pointer_shown"), FeatureKind.STATE,
            "Continuous pointer visible",
            "Check whether the continuous gesture pointer is visible",
            FeatureCategory.UI_AUTOMATION,
            fields = listOf(FieldSchema.Toggle("value", "Visible")),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("accessibility.condition.pointer_shown"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }
}

private class ContinuousPointerController(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var view: PointerView? = null

    fun isShown(): Boolean = view != null

    fun hide() {
        val current = view ?: return
        view = null
        runCatching { windowManager.removeView(current) }
    }

    fun show(
        service: YAutoAccessibilityService,
        sensitivity: Float,
        clickOnRelease: Boolean,
        scrollMode: String,
        scrollScale: Float,
        cancelRadiusDp: Float,
        autoHideMs: Long,
    ): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        hide()
        val pointer = PointerView(
            context = context,
            sensitivity = sensitivity.coerceIn(0.1f, 5f),
            clickOnRelease = clickOnRelease,
            scrollMode = scrollMode,
            scrollScale = scrollScale.coerceIn(0.1f, 5f),
            cancelRadiusPx = cancelRadiusDp.coerceIn(0f, 240f) * context.resources.displayMetrics.density,
            onTap = { x, y -> scope.launch { service.tap(x, y, 40L) } },
            onScroll = { x1, y1, x2, y2, duration ->
                scope.launch { service.swipe(x1, y1, x2, y2, duration) }
            },
            onCancel = { hide() },
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        return runCatching {
            windowManager.addView(pointer, params)
            view = pointer
            if (autoHideMs > 0) pointer.postDelayed({ if (view === pointer) hide() }, autoHideMs.coerceAtMost(86_400_000L))
            true
        }.getOrDefault(false)
    }
}

private class PointerView(
    context: Context,
    private val sensitivity: Float,
    private val clickOnRelease: Boolean,
    private val scrollMode: String,
    private val scrollScale: Float,
    private val cancelRadiusPx: Float,
    private val onTap: (Float, Float) -> Unit,
    private val onScroll: (Float, Float, Float, Float, Long) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val cancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private var originX = 0f
    private var originY = 0f
    private var pointerX = 0f
    private var pointerY = 0f
    private var lastFingerX = 0f
    private var lastFingerY = 0f
    private var scrollAccumX = 0f
    private var scrollAccumY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                originX = width / 2f
                originY = height / 2f
                pointerX = originX
                pointerY = originY
                lastFingerX = event.x
                lastFingerY = event.y
                scrollAccumX = 0f
                scrollAccumY = 0f
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - lastFingerX) * sensitivity
                val dy = (event.y - lastFingerY) * sensitivity
                lastFingerX = event.x
                lastFingerY = event.y
                pointerX = (pointerX + dx).coerceIn(0f, width.toFloat())
                pointerY = (pointerY + dy).coerceIn(0f, height.toFloat())
                scrollAccumX += dx * scrollScale
                scrollAccumY += dy * scrollScale

                val threshold = 56f * density
                val vertical = scrollMode == "vertical" || scrollMode == "both"
                val horizontal = scrollMode == "horizontal" || scrollMode == "both"
                if (vertical && abs(scrollAccumY) >= threshold) {
                    val amount = 220f * density * if (scrollAccumY > 0) 1f else -1f
                    onScroll(pointerX, pointerY, pointerX, (pointerY - amount).coerceIn(0f, height.toFloat()), 180L)
                    scrollAccumY = 0f
                }
                if (horizontal && abs(scrollAccumX) >= threshold) {
                    val amount = 220f * density * if (scrollAccumX > 0) 1f else -1f
                    onScroll(pointerX, pointerY, (pointerX - amount).coerceIn(0f, width.toFloat()), pointerY, 180L)
                    scrollAccumX = 0f
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = pointerX - originX
                val dy = pointerY - originY
                val inCancel = cancelRadiusPx > 0f && dx * dx + dy * dy <= cancelRadiusPx * cancelRadiusPx
                if (inCancel) onCancel()
                else if (clickOnRelease) onTap(pointerX, pointerY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cancelRadiusPx > 0f) canvas.drawCircle(originX, originY, cancelRadiusPx, cancelPaint)
        val r = 12f * density
        canvas.drawCircle(pointerX, pointerY, r, pointerPaint)
        canvas.drawLine(pointerX - r * 1.5f, pointerY, pointerX + r * 1.5f, pointerY, pointerPaint)
        canvas.drawLine(pointerX, pointerY - r * 1.5f, pointerX, pointerY + r * 1.5f, pointerPaint)
    }
}
