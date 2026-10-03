package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.ActionFailurePolicy
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ShortXImporterTest {
    @Test fun `JSON toast preserves disabled state note and default continue policy`() {
        val json = """{"id":"json-toast","title":"Toast","facts":[],"conditions":[],"actions":[{"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.ShowToast","message":"hello","isDisabled":true,"note":"keep note"}]}"""
        val result = ShortXImporter().import(ImportInput("toast.json", "application/json", json.toByteArray()))
        assertTrue(result.success)
        val action = result.bundle.automations.single().onEvent.single() as ActionNode.Action
        assertEquals("android.toast.show", action.feature.typeId)
        assertEquals(false, action.enabled)
        assertEquals("keep note", action.comment)
        assertEquals(ActionFailurePolicy.CONTINUE, action.failurePolicy)
    }

    @Test fun `protobuf disabled state is retained and ShortX break policy stays lossless`() {
        val toast = message(field(1, "disabled"), varintField(98, 1), field(99, "comment"))
        // ShortX ActionOnError enum is Continue=0, Break=1. Break is intentionally preserved as a
        // compatibility node until every related execution semantic is mapped losslessly.
        val guarded = message(field(1, "guarded"), varintField(97, 1))
        val raw = message(field(4, "flags"), field(9, "Flags"),
            field(3, message(field(1, "type.googleapis.com/tornaco.apps.shortx.core.proto.action.ShowToast"), field(2, toast))),
            field(3, message(field(1, "type.googleapis.com/tornaco.apps.shortx.core.proto.action.ShowToast"), field(2, guarded))))
        val result = ShortXImporter().import(ImportInput("flags.rule", null, raw))
        assertTrue(result.success)
        val actions = result.bundle.automations.single().onEvent.map { it as ActionNode.Action }
        assertEquals(false, actions[0].enabled)
        assertEquals("comment", actions[0].comment)
        assertEquals(ActionFailurePolicy.CONTINUE, actions[0].failurePolicy)
        assertEquals("compat.source.action", actions[1].feature.typeId)
    }

    @Test fun `maps ShowToast Any payload to native toast action`() {
        val result = ShortXImporter().import(ImportInput("shortx.rule", "application/octet-stream",
            rule("rule-1", "Toast rule", any("ShowToast", message(field(1, "Hello from ShortX"))))))
        assertTrue(result.success)
        val feature = actionFeature(result)
        assertEquals("android.toast.show", feature.typeId)
        assertEquals("Hello from ShortX", (feature.config["text"] as ConfigValue.StringValue).value)
        assertTrue(result.trace.any { it.status == "MAPPED" && it.targetId == "android.toast.show" })
    }

    @Test fun `maps numeric Delay using protobuf TimeUnit`() {
        val delay = message(field(2, "2.5"), varintField(5, 1))
        val result = ShortXImporter().import(ImportInput("delay.rule", null, rule("delay", "Delay", any("Delay", delay))))
        assertTrue(result.success)
        val feature = actionFeature(result)
        assertEquals("core.delay", feature.typeId)
        assertEquals(2500.0, (feature.config["durationMs"] as ConfigValue.NumberValue).value, 0.0)
    }

    @Test fun `maps current-user LaunchApp embedded AppPkg`() {
        val appPkg = message(field(1, "com.example.app"), varintField(2, 0))
        val result = ShortXImporter().import(ImportInput("launch.rule", null,
            rule("launch", "Launch", any("LaunchApp", message(field(1, appPkg))))))
        assertTrue(result.success)
        val feature = actionFeature(result)
        assertEquals("android.app.launch", feature.typeId)
        assertEquals("com.example.app", (feature.config["package"] as ConfigValue.StringValue).value)
    }

    @Test fun `keeps other-user LaunchApp as compatibility node`() {
        val appPkg = message(field(1, "com.example.app"), varintField(2, 10))
        val result = ShortXImporter().import(ImportInput("launch-user.rule", null,
            rule("launch-user", "Launch user", any("LaunchApp", message(field(1, appPkg))))))
        assertTrue(result.success)
        assertEquals("compat.source.action", actionFeature(result).typeId)
        assertTrue(result.issues.any { it.suggestedFeatureId == "android.app.launch" })
    }

    @Test fun `maps text WriteClipboard but preserves file clipboard`() {
        val textResult = ShortXImporter().import(ImportInput("clip.rule", null,
            rule("clip", "Clipboard", any("WriteClipboard", message(field(1, "hello"))))))
        assertEquals("android.clipboard.set", actionFeature(textResult).typeId)
        assertEquals("hello", (actionFeature(textResult).config["text"] as ConfigValue.StringValue).value)

        val fileResult = ShortXImporter().import(ImportInput("clip-file.rule", null,
            rule("clip-file", "Clipboard file", any("WriteClipboard", message(field(1, "label"), field(2, "/tmp/file"))))))
        assertEquals("compat.source.action", actionFeature(fileResult).typeId)
    }

    @Test fun `maps verified accessibility input actions`() {
        val text = ShortXImporter().import(ImportInput("input-text.rule", null,
            rule("input-text", "Input", any("InputText", message(field(1, "hello"))))))
        assertEquals("accessibility.input_text", actionFeature(text).typeId)
        assertEquals(ConfigValue.StringValue("hello"), actionFeature(text).config["text"])

        val tap = ShortXImporter().import(ImportInput("tap.rule", null,
            rule("tap", "Tap", any("InputTap", message(field(3, "120"), field(4, "450"))))))
        assertEquals("accessibility.gesture.tap", actionFeature(tap).typeId)
        assertEquals(ConfigValue.NumberValue(120.0), actionFeature(tap).config["x"])
        assertEquals(ConfigValue.NumberValue(450.0), actionFeature(tap).config["y"])

        val swipe = ShortXImporter().import(ImportInput("swipe.rule", null,
            rule("swipe", "Swipe", any("InputSwipe", message(
                field(11, "10"), field(12, "20"), field(13, "300"), field(14, "500"), field(15, "250")
            )))))
        assertEquals("accessibility.gesture.swipe", actionFeature(swipe).typeId)
        assertEquals(ConfigValue.NumberValue(250.0), actionFeature(swipe).config["durationMs"])
    }

    @Test fun `maps non-regex zero-timeout view id and preserves unsupported variants`() {
        val native = ShortXImporter().import(ImportInput("view.rule", null,
            rule("view", "View", any("FindAndClickViewById", message(field(1, "com.example:id/ok"))))))
        assertEquals("accessibility.click_view_id", actionFeature(native).typeId)
        assertEquals(ConfigValue.StringValue("com.example:id/ok"), actionFeature(native).config["viewId"])

        val regex = ShortXImporter().import(ImportInput("view-regex.rule", null,
            rule("view-regex", "View regex", any("FindAndClickViewById", message(field(1, ".*:id/ok"), varintField(2, 1))))))
        assertEquals("compat.source.action", actionFeature(regex).typeId)
        assertTrue(regex.issues.any { it.suggestedFeatureId == "accessibility.click_view_id" })

        val text = ShortXImporter().import(ImportInput("view-text.rule", null,
            rule("view-text", "View text", any("FindAndClickViewByText", message(field(1, "OK"))))))
        assertEquals("compat.source.action", actionFeature(text).typeId)
        assertTrue(text.issues.any { it.suggestedFeatureId == "accessibility.click_text" })
    }

    @Test fun `maps verified ShortX status bar audio focus and ringtone actions`() {
        val expand = ShortXImporter().import(
            ImportInput("expand.rule", null, rule("expand", "Expand", any("ExpandNotification", message())))
        )
        assertEquals("android.status_bar.control", actionFeature(expand).typeId)
        assertEquals(ConfigValue.StringValue("notifications"), actionFeature(expand).config["mode"])

        val requestFocus = ShortXImporter().import(
            ImportInput("focus.rule", null, rule("focus", "Focus", any("RequestAudioFocus", message(varintField(1, 1)))))
        )
        assertEquals("android.audio.focus.request", actionFeature(requestFocus).typeId)
        assertEquals(ConfigValue.StringValue("gain"), actionFeature(requestFocus).config["gain"])

        val abandonFocus = ShortXImporter().import(
            ImportInput("focus-off.rule", null, rule("focus-off", "Focus off", any("RequestAudioFocus", message(varintField(1, 0)))))
        )
        assertEquals("android.audio.focus.abandon", actionFeature(abandonFocus).typeId)

        val ringtonePayload = message(
            field(
                1,
                message(
                    field(1, "Default ringtone"),
                    field(2, "content://media/internal/audio/media/1"),
                    varintField(3, 1),
                ),
            )
        )
        val ringtone = ShortXImporter().import(
            ImportInput("ringtone.rule", null, rule("ringtone", "Ringtone", any("PlayRingtone", ringtonePayload)))
        )
        assertEquals("android.audio.play", actionFeature(ringtone).typeId)
        assertEquals(
            ConfigValue.StringValue("content://media/internal/audio/media/1"),
            actionFeature(ringtone).config["source"],
        )
    }

    @Test fun `preserves variable gesture expressions instead of guessing`() {
        val tap = ShortXImporter().import(ImportInput("tap-var.rule", null,
            rule("tap-var", "Tap var", any("InputTap", message(field(3, "${'$'}x"), field(4, "100"))))))
        assertEquals("compat.source.action", actionFeature(tap).typeId)
        assertTrue(tap.issues.any { it.suggestedFeatureId == "accessibility.gesture.tap" })
    }

    @Test fun `preserves recognized but unsafe conversion with suggested target`() {
        val result = ShortXImporter().import(ImportInput("shortx.rule", null,
            rule("rule-2", "Delay rule", any("Delay", message(field(2, "variable_or_expression"))))))
        assertTrue(result.success)
        assertEquals("compat.source.action", actionFeature(result).typeId)
        assertTrue(result.issues.any { it.suggestedFeatureId == "core.delay" })
    }

    private fun actionFeature(result: com.yagay.yauto.core.importer.ImportResult) =
        (result.bundle.automations.single().onEvent.single() as ActionNode.Action).feature

    private fun any(shortName: String, payload: ByteArray): ByteArray = message(
        field(1, "type.googleapis.com/tornaco.apps.shortx.core.proto.action.$shortName"),
        field(2, payload),
    )

    private fun rule(id: String, title: String, actionAny: ByteArray): ByteArray = message(
        field(3, actionAny), field(4, id), field(9, title), varintField(11, 1),
    )

    private fun message(vararg chunks: ByteArray): ByteArray = ByteArrayOutputStream().apply { chunks.forEach(::write) }.toByteArray()
    private fun field(number: Int, value: String): ByteArray = field(number, value.toByteArray())
    private fun field(number: Int, value: ByteArray): ByteArray = message(varint(((number shl 3) or 2).toLong()), varint(value.size.toLong()), value)
    private fun varintField(number: Int, value: Long): ByteArray = message(varint((number shl 3).toLong()), varint(value))
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
