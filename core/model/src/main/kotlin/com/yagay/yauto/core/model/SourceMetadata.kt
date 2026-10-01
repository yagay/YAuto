package com.yagay.yauto.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SourceMetadata(
    val importerId: String,
    val sourceId: String? = null,
    val sourceType: String? = null,
    val sourceVersion: String? = null,
    val rawReference: String? = null,
)
