package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ShortXVerifiedSystemActionsTest {
    @Test fun `maps verified boolean system toggles`() {
        assertBoolean("SetWifiEnabled", "android.wifi.set", "enabled", true)
        assertBoolean("SetBTEnabled", "android.bluetooth.set", "enabled", false)
        assertBoolean("SetNFCEnabled", "android.nfc.set", "enabled", true)
        assertBoolean("SetLocationEnabled", "android.location.enabled.set", "enabled", false)
        assertBoolean("SetAPMModeEnabled", "android.airplane_mode.set", "enabled", true)
        assertBoolean("SetFlashLightEnabled", "android.torch.set", "enabled", true)
    }

    @Test fun `mobile data preserves unsupported per SIM selection`() {
        val normal = importAction("SetDataEnabled", message(varintField(1, 1), varintField(2, 0)))
        assertEquals("android.mobile_data.set", normal.typeId)
        assertEquals(ConfigValue.BooleanValue(true), normal.config["enabled"])

        val specificSlot = importAction("SetDataEnabled", message(varintField(1, 1), varintField(2, 1), varintField(3, 1)))
        assertEquals("compat.source.action", specificSlot.typeId)
    }

    @Test fun `maps dark mode sync ringer rotation and timeout enums`() {
        val dark = importAction("SetDarkModeEnabled", message(varintField(1, 1)))
        assertEquals("android.display.dark_mode.set", dark.typeId)
        assertEquals(ConfigValue.StringValue("dark"), dark.config["mode"])

        val syncOff = importAction("SetMasterSync", message(varintField(1, 1)))
        assertEquals("android.sync.master.set", syncOff.typeId)
        assertEquals(ConfigValue.BooleanValue(false), syncOff.config["enabled"])
        assertEquals("compat.source.action", importAction("SetMasterSync", message(varintField(1, 2))).typeId)

        val vibrate = importAction("SetRingerMode", message(varintField(1, 1)))
        assertEquals("android.audio.ringer_mode.set", vibrate.typeId)
        assertEquals(ConfigValue.StringValue("vibrate"), vibrate.config["mode"])

        val rotate270 = importAction("SetScreenRotate", message(varintField(1, 3)))
        assertEquals("android.display.rotation.set", rotate270.typeId)
        assertEquals(ConfigValue.StringValue("270"), rotate270.config["rotation"])
        assertEquals("compat.source.action", importAction("SetScreenRotate", message(varintField(1, 4))).typeId)

        val timeout = importAction("SetScreenTimeout", message(varintField(1, 45_000)))
        assertEquals("android.display.screen_timeout.set", timeout.typeId)
        assertEquals(ConfigValue.NumberValue(45_000.0), timeout.config["timeoutMs"])
    }

    @Test fun `maps verified no field text shell url and key actions`() {
        assertEquals("android.screen.wake", importAction("WakeupScreen", byteArrayOf()).typeId)
        assertEquals("system.screen.sleep", importAction("SleepScreen", byteArrayOf()).typeId)

        val tts = importAction("TTS", message(field(1, "hello")))
        assertEquals("android.tts.speak", tts.typeId)
        assertEquals(ConfigValue.StringValue("hello"), tts.config["text"])

        val url = importAction("OpenUrl", message(field(1, "https://example.com")))
        assertEquals("android.uri.open", url.typeId)
        assertEquals(ConfigValue.StringValue("https://example.com"), url.config["uri"])
        assertEquals("compat.source.action", importAction("OpenUrl", message(field(1, "https://example.com"), field(2, "com.example.browser"))).typeId)

        val shell = importAction("ShellCommand", message(field(1, "id"), varintField(2, 1)))
        assertEquals("android.shell.execute", shell.typeId)
        assertEquals(ConfigValue.StringValue("id"), shell.config["command"])

        val key = importAction("InjectKeyCode", message(varintField(1, 85), varintField(2, 1), varintField(3, 0)))
        assertEquals("android.input.keyevent", key.typeId)
        assertEquals(ConfigValue.StringValue("custom"), key.config["key"])
        assertEquals(ConfigValue.NumberValue(85.0), key.config["customKeyCode"])
        assertEquals(ConfigValue.BooleanValue(true), key.config["longPress"])
        assertEquals("compat.source.action", importAction("InjectKeyCode", message(varintField(1, 85), varintField(3, 1))).typeId)
    }

    @Test fun `interactive area screenshot imports only metadata-only protobuf`() {
        val area = importAction("AreaScreenshot", byteArrayOf())
        assertEquals("android.screenshot.area_select", area.typeId)
        val unsupported = importAction("AreaScreenshot", message(varintField(1, 500)))
        assertEquals("compat.source.action", unsupported.typeId)
    }

    @Test fun `auto brightness only converts lossless enable case`() {
        val enabled = importAction("SetAutoBrightness", message(varintField(1, 1)))
        assertEquals("android.display.brightness.set", enabled.typeId)
        assertEquals(ConfigValue.StringValue("auto"), enabled.config["mode"])
        assertEquals("compat.source.action", importAction("SetAutoBrightness", message(varintField(1, 0))).typeId)
    }

    @Test fun `json verified system actions map through same canonical features`() {
        val json = """{"id":"json-system","title":"System","facts":[],"conditions":[],"actions":[
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.SetWifiEnabled","enable":true},
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.SetDarkModeEnabled","enable":false},
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.SetScreenRotate","degree":2},
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.SleepScreen"}
        ]}"""
        val result = ShortXImporter().import(ImportInput("system.json", "application/json", json.toByteArray()))
        assertTrue(result.success)
        val features = result.bundle.automations.single().onEvent.map { (it as ActionNode.Action).feature }
        assertEquals(listOf("android.wifi.set", "android.display.dark_mode.set", "android.display.rotation.set", "system.screen.sleep"), features.map { it.typeId })
        assertEquals(ConfigValue.BooleanValue(true), features[0].config["enabled"])
        assertEquals(ConfigValue.StringValue("light"), features[1].config["mode"])
        assertEquals(ConfigValue.StringValue("180"), features[2].config["rotation"])
    }

    private fun assertBoolean(source: String, target: String, key: String, value: Boolean) {
        val feature = importAction(source, message(varintField(1, if (value) 1 else 0)))
        assertEquals(target, feature.typeId)
        assertEquals(ConfigValue.BooleanValue(value), feature.config[key])
    }

    private fun importAction(shortName: String, payload: ByteArray) = actionFeature(
        ShortXImporter().import(ImportInput("$shortName.rule", null, rule(any(shortName, payload))))
    )

    private fun actionFeature(result: com.yagay.yauto.core.importer.ImportResult) =
        (result.bundle.automations.single().onEvent.single() as ActionNode.Action).feature

    private fun any(shortName: String, payload: ByteArray): ByteArray = message(
        field(1, "type.googleapis.com/tornaco.apps.shortx.core.proto.action.$shortName"),
        field(2, payload),
    )

    private fun rule(actionAny: ByteArray): ByteArray = message(
        field(3, actionAny), field(4, "verified"), field(9, "Verified"), varintField(11, 1),
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
