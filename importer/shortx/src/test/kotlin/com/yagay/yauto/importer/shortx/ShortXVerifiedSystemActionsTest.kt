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

    @Test fun `YAuto scoped status icon payloads are imported without changing their slots`() {
        val set = importAction("SetStatusBarIcon", message(field(1, "yauto_monitor"), field(2, "android:drawable/ic_dialog_info")))
        assertEquals("android.status_icon.control", set.typeId)
        assertEquals(ConfigValue.StringValue("monitor"), set.config["slot"])
        assertEquals(ConfigValue.StringValue("show"), set.config["mode"])
        assertEquals(ConfigValue.StringValue("info"), set.config["icon"])
        val remove = importAction("RemoveStatusBarIcon", message(field(1, "yauto_monitor")))
        assertEquals("android.status_icon.control", remove.typeId)
        assertEquals(ConfigValue.StringValue("remove"), remove.config["mode"])
        assertEquals("compat.source.action", importAction("SetStatusBarIcon", message(field(1, "wifi"), field(2, "android:drawable/ic_dialog_info"))).typeId)
        assertEquals("compat.source.action", importAction("SetStatusBarIcon", message(field(1, "yauto_monitor"), field(2, "custom_png"))).typeId)
        val framework = importAction("SetStatusBarIcon", message(
            field(1, "yauto_monitor"), field(2, "android:drawable/ic_menu_camera"),
        ))
        assertEquals("android.status_icon.control", framework.typeId)
        assertEquals(ConfigValue.StringValue("android_drawable"), framework.config["iconSource"])
        assertEquals(ConfigValue.StringValue("ic_menu_camera"), framework.config["drawable"])
        assertEquals("compat.source.action", importAction("RemoveStatusBarIcon", message(field(1, "alarm"))).typeId)
        assertEquals("compat.source.action", importAction("RemoveStatusBarIcon", message(field(1, "yauto_monitor"), varintField(2, 1))).typeId)
    }

    @Test fun `ShortX repeated service stops only map unambiguous components`() {
        val stop = importAction("StopService", message(
            field(1, message(field(1, "com.example.app/.Worker"))),
            field(1, message(field(1, "com.example.app/.OtherWorker"))),
        ))
        assertEquals("android.service.control", stop.typeId)
        assertEquals(ConfigValue.StringValue("stop"), stop.config["mode"])
        assertEquals(ConfigValue.StringValue(listOf("com.example.app/.Worker", "com.example.app/.OtherWorker").joinToString("\n")), stop.config["components"])
        assertEquals("compat.source.action", importAction("StopService", message(field(1, message(field(2, "com.example/.Worker"))))).typeId)
        assertEquals("compat.source.action", importAction("StopService", message(field(1, message(field(1, "com.example/.Worker"), varintField(2, 1))))).typeId)
    }

    @Test fun `ShortX status icon JSON only maps explicitly supported fields`() {
        val json = """{"id":"icons","title":"Icons","actions":[
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.SetStatusBarIcon","slot":"yauto_monitor","icon":"android:drawable/ic_dialog_info"},
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.RemoveStatusBarIcon","slot":"yauto_monitor"},
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.SetStatusBarIcon","slot":"wifi","icon":"android:drawable/ic_dialog_info"}
        ]}"""
        val result = ShortXImporter().import(ImportInput("icons.json", "application/json", json.toByteArray()))
        assertTrue(result.success)
        assertEquals(listOf("android.status_icon.control", "android.status_icon.control", "compat.source.action"),
            result.bundle.automations.single().onEvent.map { (it as ActionNode.Action).feature.typeId })
    }

    @Test fun `ShortX documented structured JSON stop service imports explicit targets`() {
        val json = """{"title":"stop","actions":[{"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.StopService",
            "services":[{"pkg":{"pkgName":"com.example.app","userId":10},"className":"com.example.app.Worker"},
                        {"pkg":{"pkgName":"com.example.app","userId":10},"className":".OtherWorker"}]}]}"""
        val result = ShortXImporter().import(ImportInput("stop.json","application/json",json.toByteArray()))
        assertTrue(result.success)
        val action = (result.bundle.automations.single().onEvent.single() as ActionNode.Action).feature
        assertEquals("android.service.control", action.typeId)
        assertEquals(ConfigValue.StringValue("stop"), action.config["mode"])
        assertEquals(ConfigValue.NumberValue(10.0), action.config["userId"])
        assertEquals(ConfigValue.StringValue("com.example.app/com.example.app.Worker\ncom.example.app/.OtherWorker"),
            action.config["components"])
    }

    @Test fun `ShortX JSON StartService maps explicit Intent including flags and typed extras`() {
        val json = """{"title":"start","actions":[{"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.StartService",
            "intent":{"pkgName":"com.example.app","className":".Worker",
                "action":"com.example.action.RUN","data":"example://job/1","flags":16,
                "extras":[{"key":"label","type":2,"value":"it's running"},{"key":"retries","type":0,"value":"3"}]},
            "userId":10,"isForegroundService":true}]}"""
        val result = ShortXImporter().import(ImportInput("start.json","application/json",json.toByteArray()))
        assertTrue(result.success)
        val action = (result.bundle.automations.single().onEvent.single() as ActionNode.Action).feature
        assertEquals("android.service.control", action.typeId)
        assertEquals(ConfigValue.StringValue("start_foreground"), action.config["mode"])
        assertEquals(ConfigValue.StringValue("com.example.app/.Worker"), action.config["component"])
        assertEquals(ConfigValue.NumberValue(10.0), action.config["userId"])
        assertEquals(ConfigValue.NumberValue(16.0), action.config["intentFlags"])
        assertTrue((action.config["intentExtrasJson"] as ConfigValue.StringValue).value.contains("retries"))
    }

    @Test fun `ShortX unknown or implicit Service Intent stays original compatibility node`() {
        fun imported(any: String): String {
            val json = """{"title":"safety","actions":[$any]}"""
            val result = ShortXImporter().import(ImportInput("safety.json","application/json",json.toByteArray()))
            return (result.bundle.automations.single().onEvent.single() as ActionNode.Action).feature.typeId
        }
        val prefix = """"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.StartService","""
        assertEquals("compat.source.action", imported("{$prefix\"intent\":{\"action\":\"android.intent.action.VIEW\"}}"))
        assertEquals("compat.source.action", imported("{$prefix\"intent\":{\"pkgName\":\"com.example\",\"className\":\".Worker\",\"categories\":[\"default\"]}}"))
        assertEquals("compat.source.action", imported("{$prefix\"intent\":{\"pkgName\":\"com.example\",\"className\":\".Worker\",\"flags\":-1}}"))
        assertEquals("compat.source.action", imported("{$prefix\"intent\":{\"pkgName\":\"com.example\",\"className\":\".Worker\",\"extras\":[{\"key\":\"x\",\"type\":99,\"value\":\"bad\"}]}}"))
        val stopPrefix = """"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.StopService","""
        assertEquals("compat.source.action", imported("{$stopPrefix\"services\":[{\"pkg\":{\"pkgName\":\"com.example\",\"userId\":0},\"className\":\".A\"},{\"pkg\":{\"pkgName\":\"com.example\",\"userId\":10},\"className\":\".B\"}]}"))
    }

    @Test fun `verified no-field ShortX system and recording actions map directly`() {
        assertEquals("android.global_actions.show", importAction("ShowGlobalActionsMenu", byteArrayOf()).typeId)
        assertEquals("android.audio.record.stop", importAction("StopAudioRecording", byteArrayOf()).typeId)
        assertEquals("compat.source.action", importAction("ShowGlobalActionsMenu", message(varintField(1, 2))).typeId)
        assertEquals("compat.source.action", importAction("StopAudioRecording", message(field(1, "unknown"))).typeId)
    }

    @Test fun `GetScreenOnTime protobuf matches official source enum and variable`() {
        val fromScreenOff = importAction("GetScreenOnTime", message(varintField(1, 0)))
        assertEquals("android.screen_on_time.get", fromScreenOff.typeId)
        assertEquals(ConfigValue.StringValue("last_screen_off"), fromScreenOff.config["from"])
        assertEquals(ConfigValue.StringValue("screenOnTime"), fromScreenOff.config["resultVariable"])
        val fromBoot = importAction("GetScreenOnTime", message(varintField(1, 1)))
        assertEquals("android.screen_on_time.get", fromBoot.typeId)
        assertEquals(ConfigValue.StringValue("system_ready"), fromBoot.config["from"])
        assertEquals("compat.source.action", importAction("GetScreenOnTime", message(varintField(1, 2))).typeId)
        assertEquals("compat.source.action", importAction("GetScreenOnTime", message(varintField(1, 0), varintField(2, 1))).typeId)
    }

    @Test fun `GetScreenOnTime JSON respects verified start-mode enum`() {
        val raw = """{"title":"screen time","actions":[
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.GetScreenOnTime","from":1},
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.GetScreenOnTime","from":3}
        ]}"""
        val result = ShortXImporter().import(ImportInput("screen.json", "application/json", raw.toByteArray()))
        assertTrue(result.success)
        assertEquals(listOf("android.screen_on_time.get", "compat.source.action"),
            result.bundle.automations.single().onEvent.map { (it as ActionNode.Action).feature.typeId })
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

    @Test fun `ShortX media playback enums map all seven native commands`() {
        val commands = listOf("play", "pause", "next", "previous", "fast_forward", "rewind", "stop")
        commands.forEachIndexed { number, expected ->
            val feature = importAction("MediaPlayback", message(varintField(1, number.toLong())))
            assertEquals("android.media.transport", feature.typeId)
            assertEquals(ConfigValue.StringValue(expected), feature.config["command"])
        }
        assertEquals("compat.source.action", importAction("MediaPlayback", message(varintField(1, 7))).typeId)
        assertEquals("compat.source.action", importAction("MediaPlayback", message(varintField(1, 4_294_967_296L))).typeId)
        assertEquals("compat.source.action", importAction("MediaPlayback", message(varintField(1, 1), field(2, "extra"))).typeId)
    }

    @Test fun `ShortX no operation preserves metadata and rejects unknown business fields`() {
        assertEquals("core.noop", importAction("NoAction", message(field(1, "custom.icon"))).typeId)
        assertEquals("compat.source.action", importAction("NoAction", message(varintField(2, 1))).typeId)
    }

    @Test fun `ShortX set volume imports exact index not a device dependent percentage`() {
        val volume = importAction("SetVolume", message(varintField(1, 3), varintField(2, 8)))
        assertEquals("android.audio.volume.set", volume.typeId)
        assertEquals(ConfigValue.StringValue("media"), volume.config["stream"])
        assertEquals(ConfigValue.StringValue("index"), volume.config["unit"])
        assertEquals(ConfigValue.NumberValue(8.0), volume.config["index"])
        assertEquals("compat.source.action", importAction("SetVolume", message(varintField(1, 11), varintField(2, 8))).typeId)
        assertEquals("compat.source.action", importAction("SetVolume", message(varintField(1, 6), varintField(2, 8))).typeId)
        assertEquals("compat.source.action", importAction("SetVolume", message(varintField(1, 3), varintField(2, 1001))).typeId)
        assertEquals("compat.source.action", importAction("SetVolume", message(varintField(1, 4_294_967_299L), varintField(2, 8))).typeId)
        assertEquals("compat.source.action", importAction("SetVolume", message(varintField(1, 3), varintField(2, 4_294_967_304L))).typeId)
    }

    @Test fun `JSON native actions convert media noop and raw volume`() {
        val json = """{"id":"shortx-expanded","title":"Actions","actions":[
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.MediaPlayback","action":"MediaPlaybackAction_SkipToNext"},
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.NoAction","icon":"circle"},
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.SetVolume","type":2,"index":4},
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.SetVolume","type":11,"index":4}
        ]}"""
        val result = ShortXImporter().import(ImportInput("actions.json", "application/json", json.toByteArray()))
        assertTrue(result.success)
        val refs = result.bundle.automations.single().onEvent.map { (it as ActionNode.Action).feature }
        assertEquals(listOf("android.media.transport", "core.noop", "android.audio.volume.set", "compat.source.action"),
            refs.map { it.typeId })
        assertEquals(ConfigValue.StringValue("next"), refs[0].config["command"])
        assertEquals(ConfigValue.StringValue("ring"), refs[2].config["stream"])
        assertEquals(ConfigValue.NumberValue(4.0), refs[2].config["index"])
    }

    @Test fun `ShortX StartAppProcess retains package identity and Android user`() {
        val first = message(field(1, "com.example.alpha"), varintField(2, 10))
        val second = message(field(1, "com.example.beta"), varintField(2, 10))
        val start = importAction("StartAppProcess", message(
            field(1, first), field(1, second), field(2, "saved_group"),
        ))
        assertEquals("android.app.process.start", start.typeId)
        assertEquals(ConfigValue.StringValue("com.example.alpha\ncom.example.beta"), start.config["packages"])
        assertEquals(ConfigValue.StringValue("saved_group"), start.config["packageSets"])
        assertEquals(ConfigValue.NumberValue(10.0), start.config["userId"])
        assertEquals("compat.source.action", importAction("StartAppProcess", message(
            field(1, first), field(1, message(field(1, "com.example.beta"), varintField(2, 0))),
        )).typeId)
        assertEquals("compat.source.action", importAction("StartAppProcess", message(field(1, first), varintField(3, 1))).typeId)
    }

    @Test fun `ShortX process by package preserves explicit user and rejects unknown fields`() {
        val pair = message(field(1, "com.example.alpha"), field(2, "12"))
        val start = importAction("StartAppProcessByPkg", message(field(1, pair)))
        assertEquals("android.app.process.start", start.typeId)
        assertEquals(ConfigValue.NumberValue(12.0), start.config["userId"])
        assertEquals("compat.source.action", importAction("StartAppProcessByPkg", message(
            field(1, message(field(1, "com.example.alpha"), field(2, "not_number"))),
        )).typeId)
    }

    @Test fun `ShortX chip show hide and unknown interaction chains`() {
        val show = importAction("ShowStatusBarChip", message(
            field(1, "Sync running"), field(2, "android:drawable/ic_dialog_info"),
        ))
        assertEquals("android.status_chip.control", show.typeId)
        assertEquals(ConfigValue.StringValue("Sync running"), show.config["text"])
        assertEquals(ConfigValue.StringValue("android_drawable"), show.config["iconMode"])
        val hide = importAction("HideStatusBarClip", byteArrayOf())
        assertEquals("android.status_chip.control", hide.typeId)
        assertEquals(ConfigValue.StringValue("hide"), hide.config["mode"])
        assertEquals("compat.source.action", importAction("ShowStatusBarChip", message(
            field(1, "Sync running"), field(3, message(field(1, "another-action"))),
        )).typeId)
        assertEquals("compat.source.action", importAction("ShowStatusBarChip", message(
            field(1, "Sync running"), field(2, "unsupported://art"),
        )).typeId)
    }

    @Test fun `ShortX JSON process and text chip convert without losing metadata`() {
        val json = """{"id":"shortx-nonplugin","title":"Actions","actions":[
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.StartAppProcess","appPkg":[
                {"pkgName":"com.example.alpha","userId":0}]},
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.ShowStatusBarChip","text":"Work","icon":""},
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.HideStatusBarClip"},
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.ShowStatusBarChip",
                "text":"With handlers","clickAction":[{"@type":"unknown"}]}
        ]}"""
        val result = ShortXImporter().import(ImportInput("chips.json", "application/json", json.toByteArray()))
        assertTrue(result.success)
        val refs = result.bundle.automations.first().onEvent.map { (it as ActionNode.Action).feature }
        assertEquals(listOf("android.app.process.start", "android.status_chip.control",
            "android.status_chip.control", "android.status_chip.control"), refs.map { it.typeId })
        val handler = result.bundle.automations.single { it.id.value.endsWith("-chip-3-click") }
        assertEquals("android.event.status_chip_interaction", handler.activation.events.single().typeId)
        assertEquals("compat.source.action", (handler.onEvent.single() as ActionNode.Action).feature.typeId)
        assertEquals(ConfigValue.StringValue("sx_0_3"), handler.activation.events.single().config["chipId"])
    }

    @Test fun `ShortX chip callback actions are imported as click and long-click rules`() {
        val json = """{"id":"chip-callback","title":"Chip","actions":[
            {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.ShowStatusBarChip",
                "text":"Sync","clickAction":[
                    {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.ShowToast","message":"Clicked"}],
                "longClickAction":[
                    {"@type":"type.googleapis.com/tornaco.apps.shortx.core.proto.action.Delay","time":10}]}
        ]}"""
        val result = ShortXImporter().import(ImportInput("chip-callback.json", "application/json", json.toByteArray()))
        assertTrue(result.success)
        val bundle = result.bundle.automations
        assertEquals(3, bundle.size)
        val action = (bundle.first().onEvent.single() as ActionNode.Action).feature
        assertEquals("android.status_chip.control", action.typeId)
        assertEquals(ConfigValue.StringValue("sx_0_0"), action.config["chipId"])
        val click = bundle.single { it.id.value.endsWith("-chip-0-click") }
        val long = bundle.single { it.id.value.endsWith("-chip-0-long_click") }
        assertEquals("android.toast.show", (click.onEvent.single() as ActionNode.Action).feature.typeId)
        assertEquals("core.delay", (long.onEvent.single() as ActionNode.Action).feature.typeId)
        assertEquals(ConfigValue.StringValue("click"), click.activation.events.single().config["gesture"])
        assertEquals(ConfigValue.StringValue("long_click"), long.activation.events.single().config["gesture"])
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
