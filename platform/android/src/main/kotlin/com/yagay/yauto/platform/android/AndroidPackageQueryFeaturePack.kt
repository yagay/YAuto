package com.yagay.yauto.platform.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class AndroidPackageQueryFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.package_query"
    private val packageManager = context.applicationContext.packageManager

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.installed.list"), FeatureKind.ACTION,
                "List installed applications", "Store installed package names in a list variable with optional system-app and disabled-app filtering",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Toggle("includeSystem", "Include system apps"),
                    FieldSchema.Toggle("includeDisabled", "Include disabled apps"),
                    FieldSchema.Variable("resultVariable", "Store package list in variable", true),
                ),
                keywords = setOf("installed apps", "packages", "application list", "system app"), ownerPackId = id,
            )
        ) { feature, ctx ->
            runCatching {
                val packages = packageManager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
                    .asSequence()
                    .filter { feature.config.boolean("includeSystem", true) || !it.isSystemApplication() }
                    .filter { feature.config.boolean("includeDisabled", true) || it.enabled }
                    .map { it.packageName }
                    .filter(::isValidPackageName)
                    .distinct()
                    .sorted()
                    .map(ConfigValue::StringValue)
                    .toList()
                val output = ConfigValue.ListValue(packages)
                ctx.variables.set(feature.config.string("resultVariable"), output)
                ActionExecutionResult(true, output)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }

        for (kind in listOf(FeatureKind.STATE, FeatureKind.CONDITION)) {
            val typeId = if (kind == FeatureKind.STATE) "android.state.app_installed" else "android.condition.app_installed"
            val descriptor = FeatureDescriptor(
                FeatureId(typeId), kind,
                "Application installed", "Check whether a package exists for the current Android user",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("package", "Package name", true),
                    FieldSchema.Toggle("value", "Installed"),
                ),
                keywords = setOf("installed", "package", "application", "exists"), ownerPackId = id,
            )
            val evaluator = ConditionEvaluator { feature, ctx ->
                val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
                if (!isValidPackageName(pkg)) return@ConditionEvaluator false
                isPackageInstalled(packageManager, pkg) == feature.config.boolean("value", true)
            }
            if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
        }
    }
}

@Suppress("DEPRECATION")
internal fun isPackageInstalled(packageManager: PackageManager, packageName: String): Boolean = runCatching {
    packageManager.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
    true
}.getOrDefault(false)

private fun ApplicationInfo.isSystemApplication(): Boolean =
    flags and ApplicationInfo.FLAG_SYSTEM != 0 || flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
