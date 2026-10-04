package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.DefinitionFeaturePack
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry

class TaskerConditionFeaturePack : FeaturePack {
    override val id: String = "standard.tasker_condition"
    private val delegate = DefinitionFeaturePack(id, TaskerConditionFeatures.definitions)
    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}
