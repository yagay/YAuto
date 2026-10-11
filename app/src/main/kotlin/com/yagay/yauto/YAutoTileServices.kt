package com.yagay.yauto

import android.service.quicksettings.TileService
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.platform.android.QuickSettingsTileController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

abstract class BaseYAutoTileService : TileService() {
    protected abstract val slot: Int
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastClickAt = AtomicLong(0L)

    override fun onStartListening() {
        super.onStartListening()
        val controller = QuickSettingsTileController(this)
        qsTile?.apply {
            label = controller.label(slot)
            state = controller.state(slot)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (RuntimeSettingsPreferences.qsClickOptimization(this)) {
            val now = SystemClock.elapsedRealtime()
            val previous = lastClickAt.get()
            if (now - previous < 450L || !lastClickAt.compareAndSet(previous, now)) return
        }
        AutomationRuntimeService.start(this)
        RuntimeEventDispatcher((application as YAutoApplication).graph, scope).dispatch(
            RuntimeEvent(
                "android.event.qs_tile",
                mapOf("slot" to ConfigValue.NumberValue(slot.toDouble())),
                source = "android.qs_tile.$slot",
            )
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class YAutoTileService1 : BaseYAutoTileService() { override val slot = 1 }
class YAutoTileService2 : BaseYAutoTileService() { override val slot = 2 }
class YAutoTileService3 : BaseYAutoTileService() { override val slot = 3 }
