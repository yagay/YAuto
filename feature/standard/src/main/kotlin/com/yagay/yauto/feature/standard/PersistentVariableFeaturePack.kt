package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.DefinitionFeaturePack
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.PersistentVariableControl
import com.yagay.yauto.feature.standard.persistent.persistentVariableActions
import com.yagay.yauto.feature.standard.persistent.persistentVariablePredicates

class PersistentVariableFeaturePack(
    control: PersistentVariableControl,
) : FeaturePack {
    override val id: String = "standard.variable.persistent"

    private val delegate = DefinitionFeaturePack(
        id,
        persistentVariableActions(control) + persistentVariablePredicates(control),
    )

    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}
