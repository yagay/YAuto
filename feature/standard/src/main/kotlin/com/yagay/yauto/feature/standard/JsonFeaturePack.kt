package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.DefinitionFeaturePack
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.feature.standard.json.JsonConversionFeatures
import com.yagay.yauto.feature.standard.json.JsonInspectFeatures
import com.yagay.yauto.feature.standard.json.JsonPathFeatures

class JsonFeaturePack : FeaturePack {
    override val id: String = "standard.json"

    private val delegate = DefinitionFeaturePack(
        id,
        JsonConversionFeatures.definitions +
            JsonPathFeatures.definitions +
            JsonInspectFeatures.definitions,
    )

    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}
