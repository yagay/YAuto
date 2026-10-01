package com.yagay.yauto.platform.android

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidFeaturePack(
    private val context: Context,
) : FeaturePack {
    override val id: String = "android"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(FeatureId("android.toast.show"), FeatureKind.ACTION, "Show toast", "Show an Android toast", FeatureCategory.NOTIFICATION, fields = listOf(FieldSchema.Text("text", "Text", true, true)), ownerPackId = id)
        ) { feature, _ ->
            Toast.makeText(context, feature.config.string("text"), Toast.LENGTH_SHORT).show()
            ActionExecutionResult(true)
        }
        registry.registerAction(
            FeatureDescriptor(FeatureId("android.app.launch"), FeatureKind.ACTION, "Launch app", "Launch an installed application", FeatureCategory.APP, fields = listOf(FieldSchema.AppPicker("package", "App", true)), ownerPackId = id)
        ) { feature, _ ->
            val pkg = feature.config.string("package")
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent == null) ActionExecutionResult(false, message = "No launch intent for $pkg")
            else { context.startActivity(intent); ActionExecutionResult(true) }
        }
    }
}
