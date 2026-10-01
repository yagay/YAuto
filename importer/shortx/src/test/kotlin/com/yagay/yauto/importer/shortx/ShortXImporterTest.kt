package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ShortXImporterTest {
    @Test fun `JSON toast preserves disabled state and note`() {
        val json = """{"id":"json-toast","title":"Toast","facts":[],"conditions":[],"actions":[{"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.ShowToast","message":"hello","isDisabled":true,"note":"keep note"}]}"""
        val result = ShortXImporter().import(ImportInput("toast.json", "application/json", json.toByteArray()))
        assertTrue(result.success)
        val action = result.bundle.automations.single().onEvent.single() as ActionNode.Action
        assertEquals("android.toast.show", action.feature.typeId)
        assertEquals(false, action.enabled)
        assertEquals("keep note", action.comment)
    }

    @Test fun `protobuf disabled state and unsupported error handling are retained`() {
        val toast = message(field(1, "disabled"), varintField(98, 1), field(99, "comment"))
        val guarded = message(field(1, "guarded"), varintField(97, 2))
        val raw = message(field(4, "flags"), field(9, "Flags"),
            field(3, message(field(1, "type.googleapis.com/tornaco.apps.shortx.core.proto.action.ShowToast"), field(2, toast))),
            field(3, message(field(1, "type.googleapis.com/tornaco.apps.shortx.core.proto.action.ShowToast"), field(2, guarded))))
        val result = ShortXImporter().import(ImportInput("flags.rule", null, raw))
        assertTrue(result.success)
        val actions = result.bundle.automations.single().onEvent.map { it as ActionNode.Action }
        assertEquals(false, actions[0].enabled)
        assertEquals("comment", actions[0].comment)
        assertEquals("compat.source.action", actions[1].feature.typeId)
    }
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
