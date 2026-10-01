package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ShortXImporterTest {
    @Test
    fun `maps ShowToast Any payload to native toast action`() {
        val showToast = message(field(1, "Hello from ShortX"))
        val any = message(
            field(1, "type.googleapis.com/tornaco.apps.shortx.core.proto.action.ShowToast"),
            field(2, showToast),
        )
        val rawRule = message(
            field(3, any),
            field(4, "rule-1"),
            field(9, "Toast rule"),
            varintField(11, 1),
        )

        val result = ShortXImporter().import(ImportInput("shortx.rule", "application/octet-stream", rawRule))

        assertTrue(result.success)
        assertEquals(1, result.bundle.automations.size)
        val feature = (result.bundle.automations.single().onEvent.single() as ActionNode.Action).feature
        assertEquals("android.toast.show", feature.typeId)
        assertEquals("Hello from ShortX", (feature.config["text"] as ConfigValue.StringValue).value)
        assertTrue(result.trace.any { it.status == "MAPPED" && it.targetId == "android.toast.show" })
    }

    @Test
    fun `preserves recognized but unsafe conversion with suggested target`() {
        val delay = message(field(2, "variable_or_expression"))
        val any = message(
            field(1, "type.googleapis.com/tornaco.apps.shortx.core.proto.action.Delay"),
            field(2, delay),
        )
        val rawRule = message(field(3, any), field(4, "rule-2"), field(9, "Delay rule"))

        val result = ShortXImporter().import(ImportInput("shortx.rule", null, rawRule))

        assertTrue(result.success)
        val feature = (result.bundle.automations.single().onEvent.single() as ActionNode.Action).feature
        assertEquals("compat.source.action", feature.typeId)
        assertTrue(result.issues.any { it.suggestedFeatureId == "core.delay" })
    }

    private fun message(vararg chunks: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        chunks.forEach(::write)
    }.toByteArray()

    private fun field(number: Int, value: String): ByteArray = field(number, value.toByteArray())

    private fun field(number: Int, value: ByteArray): ByteArray = message(
        varint(((number shl 3) or 2).toLong()),
        varint(value.size.toLong()),
        value,
    )

    private fun varintField(number: Int, value: Long): ByteArray = message(
        varint((number shl 3).toLong()),
        varint(value),
    )

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val out = ByteArrayOutputStream()
        do {
            var b = (remaining and 0x7f).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) b = b or 0x80
            out.write(b)
        } while (remaining != 0L)
        return out.toByteArray()
    }
}
