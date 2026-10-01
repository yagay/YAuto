package com.yagay.yauto.importer.macrodroid

import com.yagay.yauto.core.importer.EmptySourceFeatureMapper

/**
 * Verified classType -> native Feature mappings belong here.
 * Keep this conservative: a mapping is only added when its source fields are also translated,
 * otherwise YAuto preserves the source node as a compatibility placeholder.
 */
object MacroDroidMappings {
    val mapper = EmptySourceFeatureMapper
}
