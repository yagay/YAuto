package com.yagay.yauto.core.model

import kotlinx.serialization.Serializable

@Serializable
data class FeatureRef(
    val typeId: String,
    val schemaVersion: Int = 1,
    val config: ConfigMap = emptyMap(),
)
