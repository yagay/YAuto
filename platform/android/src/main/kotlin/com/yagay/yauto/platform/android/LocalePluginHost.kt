package com.yagay.yauto.platform.android

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.yagay.yauto.core.model.ConfigValue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal object LocalePluginProtocol {
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val ACTION_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"
    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_EDIT_CONDITION = "com.twofortyfouram.locale.intent.action.EDIT_CONDITION"
    const val ACTION_REQUEST_QUERY = "com.twofortyfouram.locale.intent.action.REQUEST_QUERY"

    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_STRING_ACTIVITY_CLASS_NAME = "com.twofortyfouram.locale.intent.extra.ACTIVITY"
    const val EXTRA_STRING_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    const val EXTRA_STRING_BREADCRUMB = "com.twofortyfouram.locale.intent.extra.BREADCRUMB"

    const val RESULT_SATISFIED = 16
    const val RESULT_UNSATISFIED = 17
    const val RESULT_UNKNOWN = 18
}

internal data class LocalePluginResult(
    val completed: Boolean,
    val resultCode: Int = LocalePluginProtocol.RESULT_UNKNOWN,
    val extras: Bundle? = null,
)

internal data class LocalePluginComponent(
    val packageName: String,
    val className: String,
    val kind: String,
    val label: String,
)

internal class LocalePluginHost(private val context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fireSetting(
        packageName: String,
        receiverClass: String,
        bundleJson: String,
        ordered: Boolean,
        timeoutMs: Long,
    ): LocalePluginResult {
        val component = resolveReceiver(
            packageName,
            receiverClass,
            LocalePluginProtocol.ACTION_FIRE_SETTING,
        ) ?: return LocalePluginResult(false)
        val intent = Intent(LocalePluginProtocol.ACTION_FIRE_SETTING)
            .setComponent(component)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_FROM_BACKGROUND)
            .putExtra(LocalePluginProtocol.EXTRA_BUNDLE, parseBundle(bundleJson))
            .putExtra(LocalePluginProtocol.EXTRA_STRING_ACTIVITY_CLASS_NAME, normalizeClass(packageName, receiverClass))
        return if (!ordered) {
            runCatching {
                appContext.sendBroadcast(intent)
                LocalePluginResult(true)
            }.getOrElse { LocalePluginResult(false) }
        } else {
            sendOrdered(intent, timeoutMs, 0)
        }
    }

    suspend fun queryCondition(
        packageName: String,
        receiverClass: String,
        bundleJson: String,
        timeoutMs: Long,
        passthrough: Bundle? = null,
    ): LocalePluginResult {
        val component = resolveReceiver(
            packageName,
            receiverClass,
            LocalePluginProtocol.ACTION_QUERY_CONDITION,
        ) ?: return LocalePluginResult(false)
        val intent = Intent(LocalePluginProtocol.ACTION_QUERY_CONDITION)
            .setComponent(component)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_FROM_BACKGROUND)
            .putExtra(LocalePluginProtocol.EXTRA_BUNDLE, parseBundle(bundleJson))
            .putExtra(LocalePluginProtocol.EXTRA_STRING_ACTIVITY_CLASS_NAME, normalizeClass(packageName, receiverClass))
        passthrough?.keySet()?.forEach { key ->
            when (val value = passthrough.get(key)) {
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Float -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                is Bundle -> intent.putExtra(key, value)
            }
        }
        return sendOrdered(intent, timeoutMs, LocalePluginProtocol.RESULT_UNKNOWN)
    }

    fun scan(): List<LocalePluginComponent> {
        val pm = appContext.packageManager
        fun receivers(action: String, kind: String): List<LocalePluginComponent> =
            runCatching {
                pm.queryBroadcastReceivers(Intent(action), 0).mapNotNull { info ->
                    val receiver = info.activityInfo ?: return@mapNotNull null
                    LocalePluginComponent(
                        packageName = receiver.packageName,
                        className = receiver.name,
                        kind = kind,
                        label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(receiver.name),
                    )
                }
            }.getOrDefault(emptyList())

        return (
            receivers(LocalePluginProtocol.ACTION_FIRE_SETTING, "action") +
                receivers(LocalePluginProtocol.ACTION_QUERY_CONDITION, "condition")
            )
            .distinctBy { it.kind + "|" + it.packageName + "|" + it.className }
            .sortedWith(compareBy(LocalePluginComponent::kind, LocalePluginComponent::label))
    }

    fun bundleAsConfig(bundle: Bundle?): ConfigValue = ConfigValue.ObjectValue(
        bundle?.keySet().orEmpty().associateWith { key -> anyToConfig(bundle?.get(key)) }
    )

    private suspend fun sendOrdered(
        intent: Intent,
        timeoutMs: Long,
        initialCode: Int,
    ): LocalePluginResult {
        val deferred = CompletableDeferred<LocalePluginResult>()
        val completed = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (completed.compareAndSet(false, true)) {
                    deferred.complete(
                        LocalePluginResult(
                            completed = true,
                            resultCode = resultCode,
                            extras = getResultExtras(false),
                        )
                    )
                }
            }
        }
        val sent = runCatching {
            appContext.sendOrderedBroadcast(
                intent,
                null,
                receiver,
                mainHandler,
                initialCode,
                null,
                null,
            )
            true
        }.getOrDefault(false)
        if (!sent) return LocalePluginResult(false)
        return withTimeoutOrNull(timeoutMs.coerceIn(100L, 120_000L)) { deferred.await() }
            ?: LocalePluginResult(false)
    }

    private fun resolveReceiver(
        packageName: String,
        configuredClass: String,
        action: String,
    ): ComponentName? {
        val pkg = packageName.trim()
        if (!PACKAGE.matches(pkg)) return null
        val normalized = normalizeClass(pkg, configuredClass)
        if (normalized.isNotBlank() && CLASS.matches(normalized)) {
            val explicit = ComponentName(pkg, normalized)
            val resolvesExplicitly = runCatching {
                appContext.packageManager.queryBroadcastReceivers(
                    Intent(action).setComponent(explicit),
                    0,
                ).isNotEmpty()
            }.getOrDefault(false)
            if (resolvesExplicitly) return explicit
        }

        val candidates = runCatching {
            appContext.packageManager.queryBroadcastReceivers(
                Intent(action).setPackage(pkg),
                0,
            ).mapNotNull { it.activityInfo?.let { info -> ComponentName(info.packageName, info.name) } }
                .distinct()
        }.getOrDefault(emptyList())
        return candidates.singleOrNull()
    }

    private fun normalizeClass(packageName: String, className: String): String {
        val cls = className.trim()
        if (cls.isBlank()) return ""
        return if (cls.startsWith(".")) packageName.trim() + cls else cls
    }

    private fun parseBundle(raw: String): Bundle {
        if (raw.isBlank()) return Bundle()
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return Bundle()
        return jsonObjectToBundle(root)
    }

    private fun jsonObjectToBundle(obj: JsonObject): Bundle = Bundle().apply {
        obj.forEach { (key, value) -> putJsonValue(this, key, value) }
    }

    private fun putJsonValue(bundle: Bundle, key: String, value: JsonElement) {
        when (value) {
            JsonNull -> bundle.putString(key, null)
            is JsonObject -> bundle.putBundle(key, jsonObjectToBundle(value))
            is JsonArray -> {
                val strings = value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                bundle.putStringArrayList(key, ArrayList(strings))
            }
            is JsonPrimitive -> when {
                value.booleanOrNull != null -> bundle.putBoolean(key, value.booleanOrNull!!)
                value.longOrNull != null -> bundle.putLong(key, value.longOrNull!!)
                value.doubleOrNull != null -> bundle.putDouble(key, value.doubleOrNull!!)
                else -> bundle.putString(key, value.contentOrNull.orEmpty())
            }
        }
    }

    private fun anyToConfig(value: Any?): ConfigValue = when (value) {
        null -> ConfigValue.NullValue
        is Boolean -> ConfigValue.BooleanValue(value)
        is Byte -> ConfigValue.NumberValue(value.toDouble())
        is Short -> ConfigValue.NumberValue(value.toDouble())
        is Int -> ConfigValue.NumberValue(value.toDouble())
        is Long -> ConfigValue.NumberValue(value.toDouble())
        is Float -> ConfigValue.NumberValue(value.toDouble())
        is Double -> ConfigValue.NumberValue(value)
        is CharSequence -> ConfigValue.StringValue(value.toString())
        is Bundle -> bundleAsConfig(value)
        is ArrayList<*> -> ConfigValue.ListValue(value.map(::anyToConfig))
        is Array<*> -> ConfigValue.ListValue(value.map(::anyToConfig))
        is IntArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is LongArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is FloatArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is DoubleArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it) })
        is BooleanArray -> ConfigValue.ListValue(value.map { ConfigValue.BooleanValue(it) })
        else -> ConfigValue.StringValue(value.toString())
    }

    private companion object {
        val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        val CLASS = Regex("[A-Za-z0-9_.$]+(?:\\.[A-Za-z0-9_.$]+)+")
    }
}
