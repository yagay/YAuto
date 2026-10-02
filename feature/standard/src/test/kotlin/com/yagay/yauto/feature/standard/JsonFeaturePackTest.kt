package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class JsonFeaturePackTest {
    @Test fun `json path parses keys and indexes`() {
        assertEquals(
            listOf(JsonPathPart.Key("user"), JsonPathPart.Key("items"), JsonPathPart.Index(0), JsonPathPart.Key("name")),
            parseJsonPath("$.user.items[0].name"),
        )
        assertNull(parseJsonPath("items[-1]"))
        assertNull(parseJsonPath("items[abc]"))
    }

    @Test fun `get set and remove nested values`() {
        val root = ConfigValue.ObjectValue(mapOf(
            "items" to ConfigValue.ListValue(listOf(
                ConfigValue.ObjectValue(mapOf("name" to ConfigValue.StringValue("old"))),
                ConfigValue.StringValue("second"),
            ))
        ))
        val path = parseJsonPath("items[0].name")!!
        assertEquals(ConfigValue.StringValue("old"), getAtPath(root, path))
        val updated = setAtPath(root, path, ConfigValue.StringValue("new"))!!
        assertEquals(ConfigValue.StringValue("new"), getAtPath(updated, path))
        val removed = removeAtPath(updated, path)!!
        assertNull(getAtPath(removed, path))
    }

    @Test fun `config value round trips through json element`() {
        val source = ConfigValue.ObjectValue(mapOf(
            "ok" to ConfigValue.BooleanValue(true),
            "count" to ConfigValue.NumberValue(2.0),
            "items" to ConfigValue.ListValue(listOf(ConfigValue.StringValue("a"), ConfigValue.NullValue)),
        ))
        val encoded = source.toJsonElement()
        val parsed = Json.parseToJsonElement(encoded.toString()).toConfigValue()
        assertEquals(source, parsed)
    }
}
