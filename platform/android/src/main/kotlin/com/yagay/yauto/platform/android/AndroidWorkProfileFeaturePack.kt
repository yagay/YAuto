package com.yagay.yauto.platform.android

import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.registry.ConditionEvaluator
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

/**
 * Work/managed-profile convenience states. Android does not expose a public managed-profile
 * classifier for arbitrary UserHandles, so related profiles are treated as the automation target.
 */
class AndroidWorkProfileFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.work_profile"
    private val users = context.applicationContext.getSystemService(UserManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerPair(registry, "available", "Work/managed profile available", "Check whether Android exposes a related user profile") {
            relatedProfiles().isNotEmpty()
        }
        registerPair(registry, "unlocked", "Work/managed profile unlocked", "Check whether at least one related user profile is currently unlocked") {
            relatedProfiles().any { runCatching { users.isUserUnlocked(it) }.getOrDefault(false) }
        }
        registerEvent(registry, "android.event.work_profile_available", "Work profile became available", "android.event.managed_profile_available")
        registerEvent(registry, "android.event.work_profile_unavailable", "Work profile became unavailable", "android.event.managed_profile_unavailable")
        registerEvent(registry, "android.event.work_profile_unlocked", "Work profile unlocked", "android.event.managed_profile_unlocked")
    }

    private fun relatedProfiles(): List<UserHandle> {
        val current = Process.myUserHandle()
        return runCatching { users.userProfiles.filter { it != current } }.getOrDefault(emptyList())
    }

    private fun registerPair(
        registry: FeatureRegistry,
        suffix: String,
        title: String,
        description: String,
        query: () -> Boolean,
    ) {
        val evaluator = ConditionEvaluator { feature, _ ->
            runCatching { query() }.getOrDefault(false) == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.work_profile_$suffix"),
            FeatureKind.STATE,
            title,
            description,
            FeatureCategory.SYSTEM,
            fields = listOf(FieldSchema.Toggle("value", "Enabled / true")),
            keywords = setOf("work profile", "managed profile", "user profile", "macrodroid"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.work_profile_$suffix"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private fun registerEvent(registry: FeatureRegistry, featureId: String, title: String, sourceTypeId: String) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId(featureId),
                FeatureKind.EVENT,
                title,
                "Trigger when Android reports a managed-profile lifecycle change",
                FeatureCategory.SYSTEM,
                keywords = setOf("work profile", "managed profile", "profile", "macrodroid"),
                ownerPackId = id,
            )
        ) { _, context -> context.event.typeId == sourceTypeId }
    }
}
