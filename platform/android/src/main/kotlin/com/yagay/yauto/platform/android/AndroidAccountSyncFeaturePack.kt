package com.yagay.yauto.platform.android

import android.accounts.Account
import android.content.ContentResolver
import android.os.Bundle
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidAccountSyncFeaturePack : FeaturePack {
    override val id: String = "android.account_sync"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sync.account.request"), FeatureKind.ACTION,
                "Sync account", "Request an immediate sync for a specific account and content authority",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("accountName", "Account name", true),
                    FieldSchema.Text("accountType", "Account type", true),
                    FieldSchema.Text("authority", "Content authority", true),
                    FieldSchema.Toggle("expedited", "Expedited"),
                    FieldSchema.Toggle("ignoreSettings", "Ignore sync settings"),
                ),
                fieldBehaviors = mapOf(
                    "accountName" to FieldBehavior(supportsVariables = true),
                    "accountType" to FieldBehavior(supportsVariables = true),
                    "authority" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("account", "sync", "authority", "request sync"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("accountName").resolveVariables(ctx.variables).trim()
            val type = feature.config.string("accountType").resolveVariables(ctx.variables).trim()
            val authority = feature.config.string("authority").resolveVariables(ctx.variables).trim()
            if (name.isBlank() || type.isBlank() || !AUTHORITY.matches(authority)) {
                return@registerAction ActionExecutionResult(false)
            }
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, feature.config.boolean("expedited", true))
                putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, feature.config.boolean("ignoreSettings"))
            }
            runCatching {
                ContentResolver.requestSync(Account(name, type), authority, extras)
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false) }
        }

        val fields = listOf(
            FieldSchema.Text("accountName", "Account name", true),
            FieldSchema.Text("accountType", "Account type", true),
            FieldSchema.Text("authority", "Content authority", true),
            FieldSchema.Choice("status", "Status", true, listOf("active", "pending")),
            FieldSchema.Toggle("value", "Matches"),
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val name = feature.config.string("accountName").resolveVariables(ctx.variables).trim()
            val type = feature.config.string("accountType").resolveVariables(ctx.variables).trim()
            val authority = feature.config.string("authority").resolveVariables(ctx.variables).trim()
            if (name.isBlank() || type.isBlank() || !AUTHORITY.matches(authority)) return@ConditionEvaluator false
            val account = Account(name, type)
            val actual = when (feature.config.string("status", "active")) {
                "pending" -> ContentResolver.isSyncPending(account, authority)
                else -> ContentResolver.isSyncActive(account, authority)
            }
            actual == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.account_sync"), FeatureKind.STATE,
            "Account sync state", "Check whether a specific account sync is active or pending",
            FeatureCategory.APP,
            fields = fields,
            keywords = setOf("account", "sync", "active", "pending"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.account_sync"), kind = FeatureKind.CONDITION), evaluator)
    }

    private companion object {
        val AUTHORITY = Regex("[A-Za-z0-9_.-]{1,255}")
    }
}
