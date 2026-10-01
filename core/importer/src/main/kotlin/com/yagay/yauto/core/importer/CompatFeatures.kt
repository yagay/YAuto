package com.yagay.yauto.core.importer

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef

object CompatFeatureIds {
    const val SOURCE_EVENT = "compat.source.event"
    const val SOURCE_STATE = "compat.source.state"
    const val SOURCE_CONDITION = "compat.source.condition"
    const val SOURCE_ACTION = "compat.source.action"
}

fun sourceFeature(
    targetTypeId: String,
    importerId: String,
    sourceType: String,
    raw: String? = null,
    extra: Map<String, ConfigValue> = emptyMap(),
): FeatureRef = FeatureRef(
    typeId = targetTypeId,
    schemaVersion = 1,
    config = buildMap {
        put("source.importer", ConfigValue.StringValue(importerId))
        put("source.type", ConfigValue.StringValue(sourceType))
        raw?.let { put("source.raw", ConfigValue.StringValue(it.take(32_000))) }
        putAll(extra)
    },
)
