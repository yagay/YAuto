package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.DefinitionFeaturePack
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry

class ExpressionFeaturePack : FeaturePack {
    override val id: String = "standard.expression"
    private val delegate = DefinitionFeaturePack(id, ExpressionFeatures.definitions)
    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}
