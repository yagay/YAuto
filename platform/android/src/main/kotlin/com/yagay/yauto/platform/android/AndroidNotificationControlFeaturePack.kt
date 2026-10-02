package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class AndroidNotificationControlFeaturePack : FeaturePack {
    override val id = "android.notification.control"

    override fun install(registry: FeatureRegistry) {
        action(registry, "android.notification.dismiss", "Dismiss notification", "Dismiss the first active notification matching the selected filters") { controller, match, _ ->
            val item = matching(controller, match).firstOrNull() ?: return@action false
            controller.dismiss(item.key)
        }
        action(registry, "android.notification.open", "Open notification", "Open the content intent of the first matching active notification") { controller, match, _ ->
            val item = matching(controller, match).firstOrNull() ?: return@action false
            controller.open(item.key)
        }
        action(
            registry,
            "android.notification.action",
            "Run notification action",
            "Invoke an action button by zero-based index on the first matching active notification",
            extraFields = listOf(FieldSchema.Number("actionIndex", "Action index (0 = first)", min = 0.0)),
        ) { controller, match, feature ->
            val item = matching(controller, match).firstOrNull() ?: return@action false
            val index = feature.config["actionIndex"].numberOrNull()?.toInt() ?: 0
            index >= 0 && index < item.actionCount && controller.invokeAction(item.key, index)
        }
        action(
            registry,
            "android.notification.action_title",
            "Run notification action by title",
            "Invoke the first notification action button whose title matches the configured text",
            extraFields = listOf(
                FieldSchema.Text("actionTitle", "Action title", true),
                FieldSchema.Toggle("exactActionTitle", "Exact action-title match"),
            ),
        ) { controller, match, feature ->
            val expected = feature.config.string("actionTitle")
            val exact = (feature.config["exactActionTitle"] as? ConfigValue.BooleanValue)?.value ?: false
            val candidates = matching(controller, match)
            for (item in candidates) {
                val index = item.actionTitles.indexOfFirst { title ->
                    if (exact) title.equals(expected, ignoreCase = true) else title.contains(expected, ignoreCase = true)
                }
                if (index >= 0 && controller.invokeAction(item.key, index)) return@action true
            }
            false
        }
        registerDismissAll(registry)
        registerQuery(registry)
        registerCount(registry)
        registerActiveState(registry)
        registerCountState(registry)
    }

    private fun registerDismissAll(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.dismiss_all"), FeatureKind.ACTION,
                "Dismiss matching notifications", "Dismiss every active notification matching the selected filters and optionally store the number dismissed",
                FeatureCategory.NOTIFICATION,
                fields = filterFields() + FieldSchema.Variable("resultVariable", "Store dismissed count in variable"),
                accessRequirements = setOf(AccessRequirement.NOTIFICATION_LISTENER),
                keywords = setOf("notification", "dismiss all", "clear", "batch"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val controller = NotificationControlBridge.current()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.notification_listener_disconnected"))
            val items = matching(controller, NotificationMatch.from(feature))
            var dismissed = 0
            for (item in items) if (controller.dismiss(item.key)) dismissed++
            val output = ConfigValue.NumberValue(dismissed.toDouble())
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerQuery(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.query"), FeatureKind.ACTION,
                "Query active notifications", "Store matching active notifications and useful metadata as a list of objects",
                FeatureCategory.NOTIFICATION,
                fields = filterFields() + listOf(
                    FieldSchema.Number("maxCount", "Maximum results", min = 1.0, max = 100.0),
                    FieldSchema.Choice("order", "Result order", options = listOf("newest", "oldest")),
                    FieldSchema.Variable("resultVariable", "Store notification list in variable", true),
                ),
                accessRequirements = setOf(AccessRequirement.NOTIFICATION_LISTENER),
                keywords = setOf("notification", "query", "list", "metadata"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val controller = NotificationControlBridge.current()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.notification_listener_disconnected"))
            val limit = (feature.config["maxCount"].numberOrNull()?.toInt() ?: 50).coerceIn(1, 100)
            val order = feature.config.string("order", "newest")
            val source = matching(controller, NotificationMatch.from(feature))
            val ordered = if (order == "oldest") source.sortedBy { it.postTimeEpochMs } else source.sortedByDescending { it.postTimeEpochMs }
            val output = ConfigValue.ListValue(ordered.take(limit).map(::notificationObject))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerCount(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.count"), FeatureKind.ACTION,
                "Count active notifications", "Count active notifications matching the selected filters and store the result in a variable",
                FeatureCategory.NOTIFICATION,
                fields = filterFields() + FieldSchema.Variable("resultVariable", "Store count in variable", true),
                accessRequirements = setOf(AccessRequirement.NOTIFICATION_LISTENER),
                keywords = setOf("notification", "count", "number"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val controller = NotificationControlBridge.current()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.notification_listener_disconnected"))
            val output = ConfigValue.NumberValue(matching(controller, NotificationMatch.from(feature)).size.toDouble())
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerActiveState(registry: FeatureRegistry) {
        val fields = filterFields()
        val stateDescriptor = FeatureDescriptor(
            FeatureId("android.state.notification_active"), FeatureKind.STATE,
            "Active notification", "Check whether an active notification matches the selected filters",
            FeatureCategory.NOTIFICATION,
            fields = fields,
            accessRequirements = setOf(AccessRequirement.NOTIFICATION_LISTENER),
            keywords = setOf("notification", "active", "exists"), ownerPackId = id,
        )
        val conditionDescriptor = FeatureDescriptor(
            FeatureId("android.condition.notification_active"), FeatureKind.CONDITION,
            "Active notification", "Check whether an active notification matches the selected filters",
            FeatureCategory.NOTIFICATION,
            fields = fields,
            accessRequirements = setOf(AccessRequirement.NOTIFICATION_LISTENER),
            keywords = setOf("notification", "active", "exists"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val controller = NotificationControlBridge.current() ?: return@ConditionEvaluator false
            matching(controller, NotificationMatch.from(feature)).isNotEmpty()
        }
        registry.registerState(stateDescriptor, evaluator)
        registry.registerCondition(conditionDescriptor, evaluator)
    }

    private fun registerCountState(registry: FeatureRegistry) {
        val fields = filterFields() + listOf(
            FieldSchema.Choice("operator", "Comparison", true, listOf("==", "!=", ">", ">=", "<", "<=")),
            FieldSchema.Number("value", "Notification count", true, min = 0.0),
        )
        val state = FeatureDescriptor(
            FeatureId("android.state.notification_count"), FeatureKind.STATE,
            "Active notification count", "Compare the number of matching active notifications with a configured value",
            FeatureCategory.NOTIFICATION, fields = fields,
            accessRequirements = setOf(AccessRequirement.NOTIFICATION_LISTENER),
            keywords = setOf("notification", "count", "compare"), ownerPackId = id,
        )
        val condition = FeatureDescriptor(
            FeatureId("android.condition.notification_count"), FeatureKind.CONDITION,
            "Active notification count", "Compare the number of matching active notifications with a configured value",
            FeatureCategory.NOTIFICATION, fields = fields,
            accessRequirements = setOf(AccessRequirement.NOTIFICATION_LISTENER),
            keywords = setOf("notification", "count", "compare"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val controller = NotificationControlBridge.current() ?: return@ConditionEvaluator false
            val actual = matching(controller, NotificationMatch.from(feature)).size
            val expected = feature.config["value"].numberOrNull()?.toInt() ?: return@ConditionEvaluator false
            compareCount(actual, expected, feature.config.string("operator", "=="))
        }
        registry.registerState(state, evaluator)
        registry.registerCondition(condition, evaluator)
    }

    private fun action(
        registry: FeatureRegistry,
        featureId: String,
        title: String,
        description: String,
        extraFields: List<FieldSchema> = emptyList(),
        block: (NotificationController, NotificationMatch, FeatureRef) -> Boolean,
    ) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(featureId), FeatureKind.ACTION, title, description, FeatureCategory.NOTIFICATION,
                fields = filterFields() + extraFields,
                accessRequirements = setOf(AccessRequirement.NOTIFICATION_LISTENER),
                keywords = setOf("notification", "dismiss", "open", "action"), ownerPackId = id,
            )
        ) { feature, _ ->
            val controller = NotificationControlBridge.current()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.notification_listener_disconnected"))
            val ok = runCatching { block(controller, NotificationMatch.from(feature), feature) }.getOrDefault(false)
            ActionExecutionResult(ok, message = if (ok) null else userText("feature.notification_operation_failed"))
        }
    }

    private fun filterFields() = listOf(
        FieldSchema.AppPicker("package", "App / package"),
        FieldSchema.Text("titleContains", "Title contains"),
        FieldSchema.Text("textContains", "Text contains"),
        FieldSchema.Text("channelId", "Channel ID"),
        FieldSchema.Text("category", "Notification category"),
        FieldSchema.Text("groupKey", "Group key"),
        FieldSchema.Choice("ongoing", "Ongoing", options = listOf("any", "only", "exclude")),
        FieldSchema.Choice("hasActions", "Action buttons", options = listOf("any", "yes", "no")),
    )

    internal data class NotificationMatch(
        val pkg: String,
        val title: String,
        val text: String,
        val channelId: String,
        val category: String,
        val groupKey: String,
        val ongoing: String,
        val hasActions: String,
    ) {
        fun matches(item: ActiveNotificationSnapshot): Boolean =
            (pkg.isBlank() || item.packageName == pkg) &&
                (title.isBlank() || item.title.contains(title, true)) &&
                (text.isBlank() || item.text.contains(text, true)) &&
                (channelId.isBlank() || item.channelId == channelId) &&
                (category.isBlank() || item.category == category) &&
                (groupKey.isBlank() || item.groupKey == groupKey) &&
                when (ongoing) {
                    "only" -> item.ongoing
                    "exclude" -> !item.ongoing
                    else -> true
                } &&
                when (hasActions) {
                    "yes" -> item.actionCount > 0
                    "no" -> item.actionCount == 0
                    else -> true
                }

        companion object {
            fun from(feature: FeatureRef) = NotificationMatch(
                feature.config.string("package").trim(),
                feature.config.string("titleContains"),
                feature.config.string("textContains"),
                feature.config.string("channelId").trim(),
                feature.config.string("category").trim(),
                feature.config.string("groupKey").trim(),
                feature.config.string("ongoing", "any"),
                feature.config.string("hasActions", "any"),
            )
        }
    }
}

internal fun compareCount(actual: Int, expected: Int, operator: String): Boolean = when (operator) {
    "==" -> actual == expected
    "!=" -> actual != expected
    ">" -> actual > expected
    ">=" -> actual >= expected
    "<" -> actual < expected
    "<=" -> actual <= expected
    else -> false
}

private fun matching(controller: NotificationController, match: AndroidNotificationControlFeaturePack.NotificationMatch): List<ActiveNotificationSnapshot> =
    controller.snapshots().filter(match::matches)

private fun notificationObject(item: ActiveNotificationSnapshot): ConfigValue.ObjectValue = ConfigValue.ObjectValue(
    mapOf(
        "key" to ConfigValue.StringValue(item.key),
        "package" to ConfigValue.StringValue(item.packageName),
        "id" to ConfigValue.NumberValue(item.notificationId.toDouble()),
        "tag" to ConfigValue.StringValue(item.tag),
        "title" to ConfigValue.StringValue(item.title),
        "text" to ConfigValue.StringValue(item.text),
        "ongoing" to ConfigValue.BooleanValue(item.ongoing),
        "postTimeEpochMs" to ConfigValue.NumberValue(item.postTimeEpochMs.toDouble()),
        "channelId" to ConfigValue.StringValue(item.channelId),
        "category" to ConfigValue.StringValue(item.category),
        "groupKey" to ConfigValue.StringValue(item.groupKey),
        "actionTitles" to ConfigValue.ListValue(item.actionTitles.map(ConfigValue::StringValue)),
    )
)
