package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.DefinitionFeaturePack
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.feature.standard.data.DataRandomFeatures
import com.yagay.yauto.feature.standard.data.DataTextTransformFeatures

class DataFeaturePack : FeaturePack {
    override val id: String = "standard.data"

    private val delegate = DefinitionFeaturePack(
        id,
        DataTextTransformFeatures.definitions + DataRandomFeatures.standardDefinitions,
    )

    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}
