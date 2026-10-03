package com.yagay.yauto.platform.android

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/** Default-app role and input-method states shared by automation reference apps. */
class AndroidRoleStateFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.role_states"
    private val context = context.applicationContext
    private val roles = this.context.getSystemService(RoleManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerRoleHolders(registry)
        registerDefaultIme(registry)
        registerRoleHolderPair(registry)
        registerRoleAvailablePair(registry)
    }

    private fun registerRoleHolders(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.role.holders.get"),
                FeatureKind.ACTION,
                "Get default app role holders",
                "Store packages holding a selected Android default-app role",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Choice("role", "Default app role", true, ROLE_OPTIONS),
                    FieldSchema.Variable("resultVariable", "Store package list in variable", true),
                ),
                keywords = setOf("default browser", "dialer", "sms", "home", "assistant", "role"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val role = roleName(feature.config.string("role", "browser"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
            val variable = feature.config.string("resultVariable").trim()
            if (variable.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
            }
            val holders = if (roles.isRoleAvailable(role)) roles.getRoleHolders(role) else emptyList()
            val output = ConfigValue.ListValue(holders.map(ConfigValue::StringValue))
            ctx.variables.set(variable, output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerDefaultIme(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.ime.default.get"),
                FeatureKind.ACTION,
                "Get default input method",
                "Store the current default input-method package and component",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store input-method object in variable", true)),
                keywords = setOf("keyboard", "ime", "input method", "default keyboard"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val variable = feature.config.string("resultVariable").trim()
            if (variable.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
            }
            val component = defaultImeComponent()
            val packageName = packageFromComponent(component)
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "package" to ConfigValue.StringValue(packageName),
                    "component" to ConfigValue.StringValue(component),
                )
            )
            ctx.variables.set(variable, output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerRoleHolderPair(registry: FeatureRegistry) {
        val fields = listOf(
            FieldSchema.Choice("role", "Default app role", true, ROLE_OPTIONS),
            FieldSchema.AppPicker("package", "App / package", true),
            FieldSchema.Toggle("value", "Is role holder"),
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val role = roleName(feature.config.string("role", "browser")) ?: return@ConditionEvaluator false
            val packageName = feature.config.string("package").resolveVariables(ctx.variables).trim()
            if (packageName.isBlank() || !roles.isRoleAvailable(role)) return@ConditionEvaluator false
            val holds = roles.getRoleHolders(role).contains(packageName)
            holds == feature.config.boolean("value", true)
        }
        registerPair(
            registry,
            "role_holder",
            "Default app role holder",
            "Check whether an application holds a selected Android default-app role",
            FeatureCategory.APP,
            fields,
            evaluator,
        )
    }

    private fun registerRoleAvailablePair(registry: FeatureRegistry) {
        val fields = listOf(
            FieldSchema.Choice("role", "Default app role", true, ROLE_OPTIONS),
            FieldSchema.Toggle("value", "Role available"),
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val role = roleName(feature.config.string("role", "browser")) ?: return@ConditionEvaluator false
            roles.isRoleAvailable(role) == feature.config.boolean("value", true)
        }
        registerPair(
            registry,
            "role_available",
            "Default app role available",
            "Check whether Android exposes a selected default-app role on this device",
            FeatureCategory.APP,
            fields,
            evaluator,
        )
    }

    private fun registerPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        evaluator: ConditionEvaluator,
    ) {
        val state = FeatureDescriptor(
            FeatureId("android.state.$key"),
            FeatureKind.STATE,
            title,
            description,
            category,
            fields = fields,
            keywords = setOf("default app", "role", "keyboard", "ime"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.$key"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private fun defaultImeComponent(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()

    private companion object {
        val ROLE_OPTIONS = listOf("browser", "dialer", "sms", "home", "assistant")
    }
}

internal fun roleName(value: String): String? = when (value) {
    "browser" -> RoleManager.ROLE_BROWSER
    "dialer" -> RoleManager.ROLE_DIALER
    "sms" -> RoleManager.ROLE_SMS
    "home" -> RoleManager.ROLE_HOME
    "assistant" -> RoleManager.ROLE_ASSISTANT
    else -> null
}

internal fun packageFromComponent(component: String): String =
    ComponentName.unflattenFromString(component)?.packageName
        ?: component.substringBefore('/').takeIf { '.' in it }.orEmpty()
