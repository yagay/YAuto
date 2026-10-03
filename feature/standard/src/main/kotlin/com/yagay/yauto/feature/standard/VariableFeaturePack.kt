package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.DefinitionFeaturePack
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.feature.standard.variable.VariableCollectionFeatures
import com.yagay.yauto.feature.standard.variable.VariableConditionFeatures
import com.yagay.yauto.feature.standard.variable.VariableScalarFeatures

/** Groups YAuto variable features without owning individual feature logic. */
class VariableFeaturePack : FeaturePack {
    override val id: String = "standard.variable"

    private val delegate = DefinitionFeaturePack(
        id = id,
        definitions = buildList {
            addAll(VariableScalarFeatures.definitions)
            addAll(VariableCollectionFeatures.definitions)
            addAll(VariableConditionFeatures.definitions)
        },
    )

    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}
