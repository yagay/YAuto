package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.DefinitionFeaturePack
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.feature.standard.time.timePredicateFeatures
import java.time.Clock

class TimeFeaturePack(
    clock: Clock = Clock.systemDefaultZone(),
) : FeaturePack {
    override val id: String = "standard.time"

    private val delegate = DefinitionFeaturePack(id, timePredicateFeatures(clock))

    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}
