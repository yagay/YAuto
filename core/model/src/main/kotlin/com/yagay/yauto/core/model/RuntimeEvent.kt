package com.yagay.yauto.core.model

import kotlinx.serialization.Serializable

@Serializable
data class RuntimeEvent(
    val typeId: String,
    val payload: ConfigMap = emptyMap(),
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val source: String = "runtime",
)
