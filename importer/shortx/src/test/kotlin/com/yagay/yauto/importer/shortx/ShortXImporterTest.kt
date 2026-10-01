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
        val any = any("ShowToast", showToast)
        val rawRule = rule("rule-1", "Toast rule", any)

        val result = ShortXImporter().import(ImportInput("shortx.rule", "application/octet-stream", rawRule))

        assertTrue(result.success)
        assertEquals(1, result.bundle.automations.size)
        val feature = (result.bundle.automations.single().onEvent.single() as ActionNode.Action).feature
        assertEquals("android.toast.show", feature.typeId)
        assertEquals("Hello from ShortX", (feature.config["text"] as ConfigValue.StringValue).value)
        assertTrue(result.trace.any { it.status == "MAPPED" && it.targetId == "android.toast.show" })
    }

    @Test
    fun `maps numeric Delay using protobuf TimeUnit`() {
        val delay = message(field(2, "2.5"), varintField(5, 1)) // seconds
        val result = ShortXImporter().import(ImportInput("delay.rule", null, rule("delay", "Delay", any("Delay", delay))))

        assertTrue(result.success)
        val feature = actionFeature(result)
        assertEquals("core.delay", feature.typeId)
        assertEquals(2500.0, (feature.config["durationMs"] as ConfigValue.NumberValue).value, 0.0)
    }

    @Test
    fun `maps current-user LaunchApp embedded AppPkg`() {
        val appPkg = message(field(1, "com.example.app"), varintField(2, 0))
        val launch = message(field(1, appPkg))
        val result = ShortXImporter().import(ImportInput("launch.rule", null, rule("launch", "Launch", any("LaunchApp", launch))))

        assertTrue(result.success)
        val feature = actionFeature(result)
        assertEquals("android.app.launch", feature.typeId)
        assertEquals("com.example.app", (feature.config["package"] as ConfigValue.StringValue).value)
    }

    @Test
    fun `keeps other-user LaunchApp as compatibility node`() {
        val appPkg = message(field(1, "com.example.app"), varintField(2, 10))
        val launch = message(field(1, appPkg))
        val result = ShortXImporter().import(ImportInput("launch-user.rule", null, rule("launch-user", "Launch user", any("LaunchApp", launch))))

        assertTrue(result.success)
        assertEquals("compat.source.action", actionFeature(result).typeId)
        assertTrue(result.issues.any { it.suggestedFeatureId == "android.app.launch" })
    }

    @Test
    fun `maps text WriteClipboard but preserves file clipboard`() {
        val textResult = ShortXImporter().import(ImportInput("clip.rule", null,
            rule("clip", "Clipboard", any("WriteClipboard", message(field(1, "hello"))))))
        assertEquals("android.clipboard.set", actionFeature(textResult).typeId)
        assertEquals("hello", (actionFeature(textResult).config["text"] as ConfigValue.StringValue).value)

        val fileResult = ShortXImporter().import(ImportInput("clip-file.rule", null,
            rule("clip-file", "Clipboard file", any("WriteClipboard", message(field(1, "label"), field(2, "/tmp/file"))))))
        assertEquals("compat.source.action", actionFeature(fileResult).typeId)
    }

    @Test
    fun `preserves recognized but unsafe conversion with suggested target`() {
        val delay = message(field(2, "variable_or_expression"))
        val rawRule = rule("rule-2", "Delay rule", any("Delay", delay))

        val result = ShortXImporter().import(ImportInput("shortx.rule", null, rawRule))

        assertTrue(result.success)
        val feature = actionFeature(result)
        assertEquals("compat.source.action", feature.typeId)
        assertTrue(result.issues.any { it.suggestedFeatureId == "core.delay" })
    }

    private fun actionFeature(result: com.yagay.yauto.core.importer.ImportResult) =
        (result.bundle.automations.single().onEvent.single() as ActionNode.Action).feature

    private fun any(shortName: String, payload: ByteArray): ByteArray = message(
        field(1, "type.googleapis.com/tornaco.apps.shortx.core.proto.action.$shortName"),
        field(2, payload),
    )

    private fun rule(id: String, title: String, actionAny: ByteArray): ByteArray = message(
        field(3, actionAny),
        field(4, id),
        field(9, title),
        varintField(11, 1),
    )

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
