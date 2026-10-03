package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.DefinitionFeaturePack
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.feature.standard.data.DataListFeatures
import com.yagay.yauto.feature.standard.data.DataMathFeatures
import com.yagay.yauto.feature.standard.data.DataRandomFeatures
import com.yagay.yauto.feature.standard.data.DataTextUtilityFeatures

class DataUtilityFeaturePack : FeaturePack {
    override val id: String = "standard.data.utility"

    private val delegate = DefinitionFeaturePack(
        id,
        DataMathFeatures.definitions +
            DataRandomFeatures.utilityDefinitions +
            DataTextUtilityFeatures.definitions +
            DataListFeatures.definitions,
    )

    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}
