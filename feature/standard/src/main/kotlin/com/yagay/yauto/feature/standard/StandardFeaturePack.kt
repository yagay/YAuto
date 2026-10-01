package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.FeaturePack

object StandardFeaturePacks {
    fun all(): List<FeaturePack> = listOf(
        CoreFeaturePack(),
        VariableFeaturePack(),
        SystemFeaturePack(),
        CompatibilityFeaturePack(),
    )
}
