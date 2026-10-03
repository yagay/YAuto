package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.DefinitionFeaturePack
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.feature.standard.time.TimeArithmeticFeatures
import com.yagay.yauto.feature.standard.time.TimeControlFeatures
import com.yagay.yauto.feature.standard.time.TimeConversionFeatures

class TimeUtilityFeaturePack : FeaturePack {
    override val id: String = "standard.time.utility"

    private val delegate = DefinitionFeaturePack(
        id,
        TimeConversionFeatures.definitions +
            TimeArithmeticFeatures.definitions +
            TimeControlFeatures.definitions,
    )

    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}
