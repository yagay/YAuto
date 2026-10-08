package com.yagay.yauto.platform.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class RuntimePermissionRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val group = intent.getStringExtra(EXTRA_GROUP).orEmpty()
        val permissions = runtimePermissionGroup(group)
        if (permissions.isEmpty() || permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            finish()
            return
        }
        requestPermissions(permissions, REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }

    companion object {
        const val EXTRA_GROUP = "permissionGroup"
        private const val REQUEST_CODE = 7301
    }
}

class AndroidRuntimePermissionFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.runtime_permissions"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.permission.request"),
                FeatureKind.ACTION,
                "Request runtime permission",
                "Open the Android runtime permission prompt for a selected YAuto capability group",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Choice("group", "Permission group", true, RUNTIME_PERMISSION_GROUPS)),
                keywords = setOf("permission", "grant", "runtime", "calendar", "contacts", "sms", "microphone"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val group = feature.config.string("group")
            if (runtimePermissionGroup(group).isEmpty()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.permission_group_unknown"))
            }
            if (runtimePermissionGranted(context, group)) {
                return@registerAction ActionExecutionResult(true, ConfigValue.BooleanValue(true))
            }
            runCatching {
                context.startActivity(
                    Intent(context, RuntimePermissionRequestActivity::class.java)
                        .putExtra(RuntimePermissionRequestActivity.EXTRA_GROUP, group)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                ActionExecutionResult(true, ConfigValue.BooleanValue(false))
            }.getOrElse {
                ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.permission.settings.open"),
                FeatureKind.ACTION,
                "Open app permission settings",
                "Open Android application settings so permissions can be reviewed manually",
                FeatureCategory.SYSTEM,
                keywords = setOf("permission", "settings", "grant", "app info"),
                ownerPackId = id,
            )
        ) { _, _ ->
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                ActionExecutionResult(true)
            }.getOrElse {
                ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
        }

        val evaluator = ConditionEvaluator { feature, _ ->
            runtimePermissionGranted(context, feature.config.string("group")) == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.runtime_permission"),
            FeatureKind.STATE,
            "Runtime permission granted",
            "Check whether a selected runtime permission group is currently granted",
            FeatureCategory.SYSTEM,
            fields = listOf(
                FieldSchema.Choice("group", "Permission group", true, RUNTIME_PERMISSION_GROUPS),
                FieldSchema.Toggle("value", "Granted"),
            ),
            keywords = setOf("permission", "runtime", "granted"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.runtime_permission"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }
}

internal val RUNTIME_PERMISSION_GROUPS = listOf(
    "calendar", "contacts", "call_log", "sms", "phone", "microphone", "camera", "location", "bluetooth", "notifications"
)

internal fun runtimePermissionGroup(group: String): Array<String> = when (group) {
    "calendar" -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    "contacts" -> arrayOf(Manifest.permission.READ_CONTACTS)
    "call_log" -> arrayOf(Manifest.permission.READ_CALL_LOG)
    "sms" -> arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
    "phone" -> arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS)
    "microphone" -> arrayOf(Manifest.permission.RECORD_AUDIO)
    "camera" -> arrayOf(Manifest.permission.CAMERA)
    "location" -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    "bluetooth" -> arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    "notifications" -> arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    else -> emptyArray()
}

/**
 * Feature-specific checks avoid requiring SMS send permission just to query messages, or
 * calendar write permission just to read events. Generic permission-group checks stay strict.
 */
fun runtimePermissionsForFeature(group: String, featureId: String): Array<String> = when {
    group == "phone" && featureId == "android.phone.call" ->
        arrayOf(Manifest.permission.CALL_PHONE)
    group == "phone" && featureId in setOf("android.phone.answer", "android.phone.end") ->
        arrayOf(Manifest.permission.ANSWER_PHONE_CALLS)
    group == "phone" && featureId in setOf(
        "android.sim.subscriptions.query", "android.telephony.subscription.info",
        "android.cell_tower.query",
    ) -> arrayOf(Manifest.permission.READ_PHONE_STATE)
    group == "sms" && featureId == "android.sms.send" ->
        arrayOf(Manifest.permission.SEND_SMS)
    group == "sms" && (featureId == "android.sms.query" || featureId.startsWith("android.event.sms_")) ->
        arrayOf(Manifest.permission.READ_SMS)
    group == "calendar" && featureId in setOf(
        "android.calendar.event.insert", "android.calendar.event.update",
        "android.calendar.attendee.set", "android.calendar.reminder.set",
    ) -> arrayOf(Manifest.permission.WRITE_CALENDAR)
    group == "calendar" && (
        featureId.startsWith("android.calendar.") ||
        featureId == "android.state.calendar_event" ||
        featureId == "android.condition.calendar_event" ||
        featureId == "android.event.calendar_changed"
    ) -> arrayOf(Manifest.permission.READ_CALENDAR)
    else -> runtimePermissionGroup(group)
}

internal fun runtimePermissionGranted(context: Context, group: String): Boolean {
    val permissions = runtimePermissionGroup(group)
    return permissions.isNotEmpty() && permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

internal fun runtimePermissionGranted(context: Context, group: String, featureId: String): Boolean {
    val permissions = runtimePermissionsForFeature(group, featureId)
    return permissions.isNotEmpty() && permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
