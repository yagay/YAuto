package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.core.model.userText

class AndroidNotificationControlFeaturePack : FeaturePack {
    override val id = "android.notification.control"

    override fun install(registry: FeatureRegistry) {
        action(registry, "android.notification.dismiss", "Dismiss notification", "Dismiss the first active notification matching app/title/text filters") { controller, match, _ ->
            val item = controller.snapshots().firstOrNull { match.matches(it) } ?: return@action false
            controller.dismiss(item.key)
        }
        action(registry, "android.notification.open", "Open notification", "Open the content intent of the first matching active notification") { controller, match, _ ->
            val item = controller.snapshots().firstOrNull { match.matches(it) } ?: return@action false
            controller.open(item.key)
        }
        action(registry, "android.notification.action", "Run notification action", "Invoke an action button on the first matching active notification") { controller, match, feature ->
            val item = controller.snapshots().firstOrNull { match.matches(it) } ?: return@action false
            val index = feature.config["actionIndex"].let { (it as? com.yagay.yauto.core.model.ConfigValue.NumberValue)?.value?.toInt() ?: 0 }
            index >= 0 && index < item.actionCount && controller.invokeAction(item.key, index)
        }

        val fields = filterFields()
        val stateDescriptor = FeatureDescriptor(
            FeatureId("android.state.notification_active"), FeatureKind.STATE,
            "Active notification", "Check whether an active notification matches the selected filters",
            FeatureCategory.NOTIFICATION,
            fields = fields,
            accessRequirements = setOf(AccessRequirement.NOTIFICATION_LISTENER),
            keywords = setOf("notification", "active", "exists"), ownerPackId = id,
        )
        val conditionDescriptor = stateDescriptor.copy(
            id = FeatureId("android.condition.notification_active"),
            kind = FeatureKind.CONDITION,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val controller = NotificationControlBridge.current() ?: return@ConditionEvaluator false
            val match = NotificationMatch.from(feature)
            controller.snapshots().any { match.matches(it) }
        }
        registry.registerState(stateDescriptor, evaluator)
        registry.registerCondition(conditionDescriptor, evaluator)
    }

    private fun action(
        registry: FeatureRegistry,
        id: String,
        title: String,
        description: String,
        block: (NotificationController, NotificationMatch, com.yagay.yauto.core.model.FeatureRef) -> Boolean,
    ) {
        val extra = if (id == "android.notification.action") listOf(FieldSchema.Number("actionIndex", "Action index (0 = first)", min = 0.0)) else emptyList()
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(id), FeatureKind.ACTION, title, description, FeatureCategory.NOTIFICATION,
                fields = filterFields() + extra,
                accessRequirements = setOf(AccessRequirement.NOTIFICATION_LISTENER),
                keywords = setOf("notification", "dismiss", "open", "action"),
                ownerPackId = this.id,
            )
        ) { feature, _ ->
            val controller = NotificationControlBridge.current()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.notification_listener_disconnected"))
            val ok = runCatching { block(controller, NotificationMatch.from(feature), feature) }.getOrDefault(false)
            ActionExecutionResult(ok, message = if (ok) null else "No matching notification or operation failed")
        }
    }

    private fun filterFields() = listOf(
        FieldSchema.AppPicker("package", "App / package"),
        FieldSchema.Text("titleContains", "Title contains"),
        FieldSchema.Text("textContains", "Text contains"),
        FieldSchema.Choice("ongoing", "Ongoing", options = listOf("any", "only", "exclude")),
    )

    private data class NotificationMatch(
        val pkg: String,
        val title: String,
        val text: String,
        val ongoing: String,
    ) {
        fun matches(item: ActiveNotificationSnapshot): Boolean =
            (pkg.isBlank() || item.packageName == pkg) &&
                (title.isBlank() || item.title.contains(title, true)) &&
                (text.isBlank() || item.text.contains(text, true)) &&
                when (ongoing) {
                    "only" -> item.ongoing
                    "exclude" -> !item.ongoing
                    else -> true
                }

        companion object {
            fun from(feature: com.yagay.yauto.core.model.FeatureRef) = NotificationMatch(
                feature.config.string("package").trim(),
                feature.config.string("titleContains"),
                feature.config.string("textContains"),
                feature.config.string("ongoing", "any"),
            )
        }
    }
}
