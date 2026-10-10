package com.yagay.yauto.importer.macrodroid

import com.yagay.yauto.core.importer.*
import com.yagay.yauto.core.model.*
import kotlinx.serialization.json.*
import java.util.UUID


/** Canonical MacroDroid JSON field readers shared by importer and source discovery. */
internal fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.number(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.doubleOrNull }
internal fun JsonObject.array(vararg keys: String): List<JsonElement> = keys.firstNotNullOfOrNull { this[it] as? JsonArray }?.toList().orEmpty()
internal fun JsonObject.primitiveText(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull