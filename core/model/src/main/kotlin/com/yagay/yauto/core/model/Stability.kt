package com.yagay.yauto.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Stability {
    STABLE,
    BETA,
    EXPERIMENTAL,
    DEPRECATED,
}
