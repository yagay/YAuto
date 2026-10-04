package com.yagay.yauto.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.storage.WorkspaceRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ConfiguredLocalePluginEventSource(
    context: Context,
    private val workspace: WorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "android.plugin.locale.events"
    private val context = context.applicationContext
    private val plugins = LocalePluginHost(this.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val querying = AtomicBoolean(false)
    @Volatile private var emitter: RuntimeEventEmitter? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocalePluginProtocol.ACTION_REQUEST_QUERY) return
            if (!querying.compareAndSet(false, true)) return
            val passthrough = intent.extras?.let(::android.os.Bundle)
            scope.launch {
                try {
                    queryConfiguredEvents(passthrough)
                } finally {
                    querying.set(false)
                }
            }
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        val filter = IntentFilter(LocalePluginProtocol.ACTION_REQUEST_QUERY)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { context.unregisterReceiver(receiver) }
        emitter = null
        scope.cancel()
    }

    private suspend fun queryConfiguredEvents(passthrough: android.os.Bundle?) {
        val rules = runCatching {
            workspace.load().automations.asSequence()
                .filter { it.enabled }
                .flatMap { it.activation.events.asSequence() }
                .filter { it.typeId == "android.event.plugin_locale" }
                .distinctBy(::localePluginRuleKey)
                .toList()
        }.getOrDefault(emptyList())

        for (feature in rules) {
            val result = plugins.queryCondition(
                packageName = feature.config.string("package"),
                receiverClass = feature.config.string("receiverClass"),
                bundleJson = feature.config.string("bundleJson"),
                timeoutMs = feature.config.long("timeoutMs", 10_000L).coerceIn(100L, 120_000L),
                passthrough = passthrough,
            )
            if (!result.completed || result.resultCode != LocalePluginProtocol.RESULT_SATISFIED) continue
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.plugin_locale",
                    payload = mapOf(
                        "ruleKey" to ConfigValue.StringValue(localePluginRuleKey(feature)),
                        "package" to ConfigValue.StringValue(feature.config.string("package")),
                        "receiverClass" to ConfigValue.StringValue(feature.config.string("receiverClass")),
                        "resultCode" to ConfigValue.NumberValue(result.resultCode.toDouble()),
                        "resultExtras" to plugins.bundleAsConfig(result.extras),
                    ),
                    source = id,
                )
            )
        }
    }
}
