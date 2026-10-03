package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.DefinitionFeaturePack
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.feature.standard.privileged.PrivilegedAppFeatures
import com.yagay.yauto.feature.standard.privileged.PrivilegedDisplayPowerFeatures
import com.yagay.yauto.feature.standard.privileged.PrivilegedNetworkFeatures
import com.yagay.yauto.feature.standard.privileged.PrivilegedSettingsFeatures

class PrivilegedAndroidFeaturePack : FeaturePack {
    override val id: String = "standard.android.privileged"

    private val delegate = DefinitionFeaturePack(
        id,
        PrivilegedAppFeatures.definitions +
            PrivilegedNetworkFeatures.definitions +
            PrivilegedDisplayPowerFeatures.definitions +
            PrivilegedSettingsFeatures.definitions,
    )

    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}
