package com.yagay.yauto.platform.android

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/** Runtime access states useful as automation constraints and diagnostics. */
class AndroidAccessStateFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.access_states"
    private val context = context.applicationContext
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private val alarms = this.context.getSystemService(AlarmManager::class.java)
    private val accessibility = this.context.getSystemService(AccessibilityManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerSummary(registry)
        booleanPair(registry, "notification_listener_access", "Notification access", "Check whether YAuto notification-listener access is enabled", ::notificationListenerAccess)
        booleanPair(registry, "usage_stats_access", "Usage access", "Check whether YAuto has app usage access", ::usageStatsAccess)
        booleanPair(registry, "accessibility_service_access", "Accessibility service access", "Check whether the YAuto accessibility service is enabled", ::accessibilityServiceAccess)
    }

    private fun registerSummary(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.access.status"),
                FeatureKind.ACTION,
                "Get Android access status",
                "Store YAuto overlay, settings, notification, DND, exact-alarm, usage and accessibility access states",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store access status object", true)),
                keywords = setOf("permission", "access", "overlay", "notification listener", "usage access", "accessibility"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val variable = feature.config.string("resultVariable").trim()
            if (variable.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
            }
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "overlay" to ConfigValue.BooleanValue(overlayAccess()),
                    "writeSettings" to ConfigValue.BooleanValue(writeSettingsAccess()),
                    "notificationListener" to ConfigValue.BooleanValue(notificationListenerAccess()),
                    "dndPolicy" to ConfigValue.BooleanValue(dndPolicyAccess()),
                    "exactAlarm" to ConfigValue.BooleanValue(exactAlarmAccess()),
                    "usageStats" to ConfigValue.BooleanValue(usageStatsAccess()),
                    "accessibilityService" to ConfigValue.BooleanValue(accessibilityServiceAccess()),
                )
            )
            ctx.variables.set(variable, output)
            ActionExecutionResult(true, output)
        }
    }

    private fun booleanPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        query: () -> Boolean,
    ) {
        val fields = listOf(FieldSchema.Toggle("value", "Granted / enabled"))
        val evaluator = ConditionEvaluator { feature, _ ->
            runCatching(query).getOrDefault(false) == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.$key"),
            FeatureKind.STATE,
            title,
            description,
            FeatureCategory.SYSTEM,
            fields = fields,
            keywords = setOf("permission", "access", "authorization", "system"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.$key"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private fun overlayAccess(): Boolean = Settings.canDrawOverlays(context)

    private fun writeSettingsAccess(): Boolean = Settings.System.canWrite(context)

    private fun notificationListenerAccess(): Boolean =
        NotificationManager.getEnabledListenerPackages(context).contains(context.packageName)

    private fun dndPolicyAccess(): Boolean = notifications.isNotificationPolicyAccessGranted

    private fun exactAlarmAccess(): Boolean = alarms.canScheduleExactAlarms()

    private fun usageStatsAccess(): Boolean = isUsageStatsAccessGranted(context)

    private fun accessibilityServiceAccess(): Boolean =
        accessibility.getEnabledAccessibilityServiceList(AccessibilityManager.FEEDBACK_ALL_MASK)
            .any { info -> info.resolveInfo.serviceInfo.packageName == context.packageName }
}
