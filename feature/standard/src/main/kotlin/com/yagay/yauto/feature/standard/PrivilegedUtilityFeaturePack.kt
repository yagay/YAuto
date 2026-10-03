package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.DefinitionFeaturePack
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.feature.standard.privileged.PrivilegedInputCaptureFeatures

class PrivilegedUtilityFeaturePack : FeaturePack {
    override val id: String = "standard.android.utilities"

    private val delegate = DefinitionFeaturePack(id, PrivilegedInputCaptureFeatures.definitions)

    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}
