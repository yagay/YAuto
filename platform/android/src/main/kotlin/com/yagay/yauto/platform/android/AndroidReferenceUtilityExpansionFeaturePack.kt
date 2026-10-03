package com.yagay.yauto.platform.android

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.measureTimeMillis

/**
 * Reference-app parity features with stable Android 12+ implementations.
 *
 * These cover explicit ShortX/MacroDroid/Tasker capabilities that are not aliases for existing
 * YAuto features: status-bar control, connectivity probing, screen-on duration and default sounds.
 */
class AndroidReferenceUtilityExpansionFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.reference_utility_expansion"
    private val context = context.applicationContext
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val usage = this.context.getSystemService(UsageStatsManager::class.java)
    private val power = this.context.getSystemService(PowerManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerStatusBar(registry)
        registerConnectivityCheck(registry)
        registerActiveNetworkInfo(registry)
        registerScreenOnTime(registry)
        registerDefaultSoundGet(registry)
        registerDefaultSoundSet(registry)
        registerScreenOnTimePair(registry)
        registerRingerModePair(registry)
    }

    private fun registerStatusBar(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.status_bar.control"),
                FeatureKind.ACTION,
                "Control status bar",
                "Expand notifications, expand quick settings, or collapse the status bar",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Choice(
                        "mode",
                        "Status bar operation",
                        true,
                        listOf("notifications", "quick_settings", "collapse"),
                    )
                ),
                fieldBehaviors = mapOf(
                    "mode" to FieldBehavior(defaultValue = ConfigValue.StringValue("notifications")),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("status bar", "notification shade", "quick settings", "expand", "collapse"),
                ownerPackId = id,
                aliases = setOf("system.notifications.expand"),
            )
        ) { feature, ctx ->
            val command = statusBarCommand(feature.config.string("mode", "notifications"))
                ?: return@registerAction ActionExecutionResult(
                    false,
                    message = userText("feature.operation_failed", feature.typeId),
                )
            executeShell(feature.typeId, command, ctx)
        }
    }

    private fun registerConnectivityCheck(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.network.connectivity.check"),
                FeatureKind.ACTION,
                "Check network connectivity",
                "Reach a website and store reachability, status code, latency and final URL",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("site", "Website / URL", true),
                    FieldSchema.Duration("timeoutMs", "Timeout"),
                    FieldSchema.Variable("resultVariable", "Store connectivity result", true),
                ),
                fieldBehaviors = mapOf(
                    "site" to FieldBehavior(supportsVariables = true),
                    "timeoutMs" to FieldBehavior(defaultValue = ConfigValue.NumberValue(3_000.0)),
                ),
                keywords = setOf("connectivity", "internet", "website", "latency", "online", "probe"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val raw = feature.config.string("site").resolveVariables(ctx.variables).trim()
            val url = normalizeConnectivityUrl(raw)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.http_url_required"))
            val timeout = (feature.config["timeoutMs"].numberOrNull()?.toLong() ?: 3_000L)
                .coerceIn(250L, 60_000L)
                .toInt()
            val output = withContext(Dispatchers.IO) { probeConnectivity(url, timeout) }
            val variable = feature.config.string("resultVariable").trim()
            if (variable.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
            }
            ctx.variables.set(variable, output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerActiveNetworkInfo(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.network.active.info"),
                FeatureKind.ACTION,
                "Get active network info",
                "Store active transport, validation, metering, interface, DNS and link addresses",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store network object", true)),
                keywords = setOf("network", "transport", "dns", "address", "interface", "validated"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val variable = feature.config.string("resultVariable").trim()
            if (variable.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
            }
            val output = activeNetworkObject()
            ctx.variables.set(variable, output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerScreenOnTime(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.screen_on_time.get"),
                FeatureKind.ACTION,
                "Get screen-on time",
                "Read the current screen-on session or total interactive time since system startup",
                FeatureCategory.DISPLAY,
                fields = listOf(
                    FieldSchema.Choice(
                        "from",
                        "Measure from",
                        true,
                        listOf("last_screen_off", "system_ready"),
                    ),
                    FieldSchema.Variable("resultVariable", "Store milliseconds in variable", true),
                ),
                fieldBehaviors = mapOf(
                    "from" to FieldBehavior(defaultValue = ConfigValue.StringValue("last_screen_off")),
                ),
                accessRequirements = setOf(AccessRequirement.USAGE_STATS),
                keywords = setOf("screen on time", "interactive", "usage stats", "duration"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!isUsageStatsAccessGranted(context)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
            }
            val duration = screenOnTimeMs(feature.config.string("from", "last_screen_off"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
            val variable = feature.config.string("resultVariable").trim()
            if (variable.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
            }
            val output = ConfigValue.NumberValue(duration.toDouble())
            ctx.variables.set(variable, output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerDefaultSoundGet(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.default_sound.get"),
                FeatureKind.ACTION,
                "Get default system sound",
                "Read the current default ringtone, notification sound, or alarm sound URI",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Choice("type", "Sound type", true, listOf("ringtone", "notification", "alarm")),
                    FieldSchema.Variable("resultVariable", "Store sound URI in variable", true),
                ),
                keywords = setOf("ringtone", "notification sound", "alarm sound", "default sound"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val type = ringtoneType(feature.config.string("type", "ringtone"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
            val variable = feature.config.string("resultVariable").trim()
            if (variable.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
            }
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, type)?.toString().orEmpty()
            val output = ConfigValue.StringValue(uri)
            ctx.variables.set(variable, output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerDefaultSoundSet(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.default_sound.set"),
                FeatureKind.ACTION,
                "Set default system sound",
                "Set or silence the default ringtone, notification sound, or alarm sound",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Choice("type", "Sound type", true, listOf("ringtone", "notification", "alarm")),
                    FieldSchema.Text("uri", "Sound URI"),
                    FieldSchema.Toggle("silent", "Set silent"),
                ),
                fieldBehaviors = mapOf("uri" to FieldBehavior(supportsVariables = true)),
                accessRequirements = setOf(AccessRequirement.WRITE_SETTINGS),
                keywords = setOf("ringtone", "notification sound", "alarm sound", "default sound", "silent"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!Settings.System.canWrite(context)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.modify_settings_denied"))
            }
            val type = ringtoneType(feature.config.string("type", "ringtone"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
            val silent = (feature.config["silent"] as? ConfigValue.BooleanValue)?.value == true
            val rawUri = feature.config.string("uri").resolveVariables(ctx.variables).trim()
            if (!silent && rawUri.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
            }
            runCatching {
                RingtoneManager.setActualDefaultRingtoneUri(
                    context,
                    type,
                    if (silent) null else Uri.parse(rawUri),
                )
                ActionExecutionResult(true)
            }.getOrElse {
                ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
        }
    }

    private fun registerScreenOnTimePair(registry: FeatureRegistry) {
        val fields = listOf(
            FieldSchema.Choice("from", "Measure from", true, listOf("last_screen_off", "system_ready")),
            FieldSchema.Duration("minMs", "Minimum duration"),
            FieldSchema.Duration("maxMs", "Maximum duration"),
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            if (!isUsageStatsAccessGranted(context)) return@ConditionEvaluator false
            val value = screenOnTimeMs(feature.config.string("from", "last_screen_off"))
                ?: return@ConditionEvaluator false
            val min = feature.config["minMs"].numberOrNull()?.toLong() ?: 0L
            val max = feature.config["maxMs"].numberOrNull()?.toLong() ?: Long.MAX_VALUE
            min >= 0L && max >= min && value in min..max
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.screen_on_time"),
            FeatureKind.STATE,
            "Screen-on time",
            "Compare current screen-on duration with a configured range",
            FeatureCategory.DISPLAY,
            fields = fields,
            accessRequirements = setOf(AccessRequirement.USAGE_STATS),
            keywords = setOf("screen on time", "interactive", "duration"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.screen_on_time"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private fun registerRingerModePair(registry: FeatureRegistry) {
        val fields = listOf(
            FieldSchema.Choice("mode", "Ringer mode", true, listOf("normal", "vibrate", "silent")),
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            currentRingerMode() == feature.config.string("mode", "normal")
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.ringer_mode"),
            FeatureKind.STATE,
            "Ringer mode",
            "Match the current Android ringer mode",
            FeatureCategory.AUDIO,
            fields = fields,
            keywords = setOf("ringer", "silent", "vibrate", "sound mode"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.ringer_mode"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private fun currentRingerMode(): String = when (audio.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }

    private fun activeNetworkObject(): ConfigValue.ObjectValue {
        val network = connectivity.activeNetwork
            ?: return ConfigValue.ObjectValue(mapOf("connected" to ConfigValue.BooleanValue(false)))
        val caps = connectivity.getNetworkCapabilities(network)
            ?: return ConfigValue.ObjectValue(mapOf("connected" to ConfigValue.BooleanValue(false)))
        val link = connectivity.getLinkProperties(network)
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        }
        return ConfigValue.ObjectValue(
            mapOf(
                "connected" to ConfigValue.BooleanValue(true),
                "validated" to ConfigValue.BooleanValue(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)),
                "metered" to ConfigValue.BooleanValue(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)),
                "transports" to ConfigValue.ListValue(transports.map(ConfigValue::StringValue)),
                "interfaceName" to ConfigValue.StringValue(link?.interfaceName.orEmpty()),
                "dnsServers" to ConfigValue.ListValue(link?.dnsServers.orEmpty().map { ConfigValue.StringValue(it.hostAddress.orEmpty()) }),
                "linkAddresses" to ConfigValue.ListValue(link?.linkAddresses.orEmpty().map { ConfigValue.StringValue(it.toString()) }),
                "mtu" to ConfigValue.NumberValue((link?.mtu ?: 0).toDouble()),
            )
        )
    }

    private fun screenOnTimeMs(from: String): Long? {
        val now = System.currentTimeMillis()
        val bootStart = (now - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return runCatching {
            when (from) {
                "system_ready" -> usage.queryEventStats(
                    UsageStatsManager.INTERVAL_BEST,
                    bootStart,
                    now,
                ).filter { it.eventType == UsageEvents.Event.SCREEN_INTERACTIVE }
                    .sumOf { it.totalTime }
                "last_screen_off" -> currentScreenSessionMs(bootStart, now)
                else -> null
            }
        }.getOrNull()
    }

    private fun currentScreenSessionMs(begin: Long, now: Long): Long {
        if (!power.isInteractive) return 0L
        val events = usage.queryEvents(begin, now)
        val event = UsageEvents.Event()
        var lastInteractive = begin
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> lastInteractive = event.timeStamp
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> if (event.timeStamp >= lastInteractive) lastInteractive = event.timeStamp
            }
        }
        return (now - lastInteractive).coerceAtLeast(0L)
    }

    private suspend fun executeShell(
        operationId: String,
        command: String,
        ctx: FeatureExecutionContext,
    ): ActionExecutionResult {
        val result = ctx.capabilities.execute(
            CapabilityRequest(
                capability = CapabilityIds.PRIVILEGED_SHELL,
                operationId = operationId,
                payload = mapOf("command" to ConfigValue.StringValue(command)),
            )
        )
        return ActionExecutionResult(result.success, result.value, result.message)
    }
}

internal fun statusBarCommand(mode: String): String? = when (mode) {
    "notifications" -> "cmd statusbar expand-notifications"
    "quick_settings" -> "cmd statusbar expand-settings"
    "collapse" -> "cmd statusbar collapse"
    else -> null
}

internal fun normalizeConnectivityUrl(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
    return runCatching {
        val url = URL(candidate)
        if (url.protocol !in setOf("http", "https") || url.host.isBlank()) null else url.toString()
    }.getOrNull()
}

internal fun probeConnectivity(url: String, timeoutMs: Int): ConfigValue.ObjectValue {
    var connection: HttpURLConnection? = null
    var status = 0
    var finalUrl = url
    var error = ""
    val latency = measureTimeMillis {
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
            }
            status = connection!!.responseCode
            finalUrl = connection!!.url.toString()
        } catch (throwable: Throwable) {
            error = throwable.javaClass.simpleName
        } finally {
            connection?.disconnect()
        }
    }
    val reachable = status in 200..499
    return ConfigValue.ObjectValue(
        mapOf(
            "reachable" to ConfigValue.BooleanValue(reachable),
            "statusCode" to ConfigValue.NumberValue(status.toDouble()),
            "latencyMs" to ConfigValue.NumberValue(latency.toDouble()),
            "url" to ConfigValue.StringValue(finalUrl),
            "error" to ConfigValue.StringValue(error),
        )
    )
}

internal fun ringtoneType(name: String): Int? = when (name) {
    "ringtone" -> RingtoneManager.TYPE_RINGTONE
    "notification" -> RingtoneManager.TYPE_NOTIFICATION
    "alarm" -> RingtoneManager.TYPE_ALARM
    else -> null
}
