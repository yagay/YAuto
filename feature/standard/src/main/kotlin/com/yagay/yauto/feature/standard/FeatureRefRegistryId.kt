package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.registry.FeatureId

/** Typed registry identity for a runtime feature reference. */
internal val FeatureRef.id: FeatureId
    get() = FeatureId(typeId)
