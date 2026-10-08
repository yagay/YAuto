package com.yagay.yauto.feature.standard.persistent

import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.assertEquals
import org.junit.Test

class PersistentVariableMutationTest {
    @Test
    fun `numeric mutations preserve typed values`() {
        assertEquals(
            ConfigValue.NumberValue(6.0),
            mutatePersistentValue(
                ConfigValue.NumberValue(5.0),
                "plus_one",
                ConfigValue.NullValue,
            ),
        )
        assertEquals(
            ConfigValue.NumberValue(2.5),
            mutatePersistentValue(
                ConfigValue.NumberValue(5.0),
                "minus_delta",
                ConfigValue.NumberValue(2.5),
            ),
        )
    }

    @Test
    fun `list mutations cover ShortX ordering operations`() {
        val source = ConfigValue.ListValue(
            listOf(
                ConfigValue.StringValue("a"),
                ConfigValue.StringValue("b"),
            )
        )
        assertEquals(
            ConfigValue.ListValue(
                listOf(
                    ConfigValue.StringValue("z"),
                    ConfigValue.StringValue("a"),
                    ConfigValue.StringValue("b"),
                )
            ),
            mutatePersistentValue(source, "append_first", ConfigValue.StringValue("z")),
        )
        assertEquals(
            ConfigValue.ListValue(
                listOf(
                    ConfigValue.StringValue("b"),
                    ConfigValue.StringValue("a"),
                )
            ),
            mutatePersistentValue(source, "reverse", ConfigValue.NullValue),
        )
    }

    @Test
    fun `unsupported mutation returns null instead of corrupting a variable`() {
        assertEquals(
            null,
            mutatePersistentValue(
                ConfigValue.StringValue("value"),
                "plus_one",
                ConfigValue.NullValue,
            ),
        )
    }
}
