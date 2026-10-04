package com.yagay.yauto.importer.tasker

import com.yagay.yauto.core.importer.sourceFeature
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import org.w3c.dom.Element

/** Native mappings for Tasker action codes whose exported argument layout is stable and known. */
object TaskerMappings {
    fun nativeAction(action: Element, code: String, importerId: String, raw: String): FeatureRef? = when (code) {
        "20" -> launchApp(action, importerId, code, raw)
        "25" -> noConfigAction(action, importerId, code, raw, "android.home.launch")
        "30" -> wait(action, importerId, code, raw)
        "61" -> vibrate(action, importerId, code, raw)
        "102" -> openFile(action, importerId, code, raw)
        "104" -> openUri(action, importerId, code, raw)
        "105" -> setClipboard(action, importerId, code, raw)
        "123" -> runRootShell(action, importerId, code, raw)
        "245" -> keyAction(action, importerId, code, raw, "back")
        "247" -> keyAction(action, importerId, code, raw, "recents")
        "248" -> noConfigAction(action, importerId, code, raw, "system.screen.sleep")
        "254" -> booleanAction(action, importerId, code, raw, "android.audio.speakerphone.set")
        "294" -> booleanAction(action, importerId, code, raw, "android.bluetooth.set")
        "301" -> booleanAction(action, importerId, code, raw, "android.audio.microphone_mute.set")
        "317" -> booleanAction(action, importerId, code, raw, "android.nfc.set")
        "331" -> booleanAction(action, importerId, code, raw, "android.sync.master.set")
        "333" -> booleanAction(action, importerId, code, raw, "android.airplane_mode.set")
        "425" -> booleanAction(action, importerId, code, raw, "android.wifi.set")
        "433" -> booleanAction(action, importerId, code, raw, "android.mobile_data.set")
        "511" -> torch(action, importerId, code, raw)
        "547" -> variableSet(action, importerId, code, raw)
        "548" -> flash(action, importerId, code, raw)
        "806" -> noConfigAction(action, importerId, code, raw, "android.screen.wake")
        "808" -> autoBrightness(action, importerId, code, raw)
        "812" -> displayTimeout(action, importerId, code, raw)
        "822" -> booleanAction(action, importerId, code, raw, "android.display.auto_rotate.set")
        else -> null
    }


    fun nativeContext(
        context: Element,
        code: String,
        tagName: String,
        importerId: String,
        raw: String,
    ): FeatureRef? {
        if (tagName != "Event") return null
        val target = when (code) {
            "208" -> "android.event.screen_on"
            "210" -> "android.event.screen_off"
            "300" -> "android.event.date_changed"
            "302" -> "android.event.time_changed"
            "304" -> "android.event.timezone_changed"
            "411" -> "android.event.boot"
            "422" -> "android.event.storage_low"
            "429" -> "android.event.locale_changed"
            "1000" -> "android.event.user_present"
            else -> return null
        }
        if (!context.otherArgsAreDefault(emptySet())) return null
        return sourceFeature(target, importerId, "TaskerEvent:" + code, raw)
    }

    fun returnValue(action: Element): String? = action.stringArg(0)

    fun stopTaskTarget(action: Element): String? =
        action.stringArg(0)?.trim()?.takeIf { it.isNotEmpty() }

    fun performTaskTarget(action: Element): String? = action.stringArg(0)?.takeIf { it.isNotBlank() }
    fun performTaskParam1(action: Element): String? = action.stringArg(2)?.takeIf { it.isNotBlank() }
    fun performTaskParam2(action: Element): String? = action.stringArg(3)?.takeIf { it.isNotBlank() }
    fun performTaskResultVariable(action: Element): String? = action.stringArg(4)?.takeIf { it.isNotBlank() }

    private fun wait(action: Element, importerId: String, code: String, raw: String): FeatureRef {
        // Kept compatible with Tasker's currently exported Wait layout already covered by importer tests.
        val milliseconds = action.intArg(0) ?: 0L
        val seconds = action.intArg(1) ?: 0L
        val minutes = action.intArg(2) ?: 0L
        val hours = action.intArg(3) ?: 0L
        val days = action.intArg(4) ?: 0L
        val total = milliseconds + seconds * 1_000L + minutes * 60_000L + hours * 3_600_000L + days * 86_400_000L
        return sourceFeature(
            "core.delay", importerId, "TaskerAction:$code", raw,
            extra = mapOf("durationMs" to ConfigValue.NumberValue(total.coerceAtLeast(0).toDouble())),
        )
    }

    private fun flash(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        val text = action.stringArg(0) ?: return null
        return sourceFeature(
            "android.toast.show", importerId, "TaskerAction:$code", raw,
            extra = mapOf("text" to ConfigValue.StringValue(text)),
        )
    }

    private fun launchApp(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        val pkg = action.arg(0)?.childText("appPkg")?.takeIf { it.isNotBlank() } ?: return null
        return sourceFeature(
            "android.app.launch", importerId, "TaskerAction:$code", raw,
            extra = mapOf("package" to ConfigValue.StringValue(pkg)),
        )
    }

    private fun variableSet(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        val name = action.stringArg(0)?.takeIf { it.isNotBlank() } ?: return null
        val value = action.stringArg(1) ?: ""
        return sourceFeature(
            "variable.set", importerId, "TaskerAction:$code", raw,
            extra = mapOf(
                "name" to ConfigValue.StringValue(name),
                "value" to ConfigValue.StringValue(value),
            ),
        )
    }

    private fun setClipboard(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        val text = action.stringArg(0) ?: return null
        return sourceFeature(
            "android.clipboard.set", importerId, "TaskerAction:$code", raw,
            extra = mapOf("text" to ConfigValue.StringValue(text)),
        )
    }

    private fun runRootShell(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        // Tasker 6.x exports timeout separately and keeps Use Root in arg2. Non-root shell is left
        // as a compatibility node until YAuto has a dedicated unprivileged shell backend.
        if (action.intArg(2) != 1L) return null
        val command = action.stringArg(0)?.takeIf { it.isNotBlank() } ?: return null
        val resultVariable = action.stringArg(3).orEmpty()
        return sourceFeature(
            "system.shell.execute", importerId, "TaskerAction:$code", raw,
            extra = buildMap {
                put("command", ConfigValue.StringValue(command))
                if (resultVariable.isNotBlank()) put("resultVariable", ConfigValue.StringValue(resultVariable))
            },
        )
    }

    private fun booleanAction(
        action: Element,
        importerId: String,
        code: String,
        raw: String,
        target: String,
    ): FeatureRef? {
        val enabled = action.booleanArg(0) ?: return null
        if (!action.otherArgsAreDefault(setOf(0))) return null
        return sourceFeature(
            target, importerId, "TaskerAction:$code", raw,
            extra = mapOf("enabled" to ConfigValue.BooleanValue(enabled)),
        )
    }

    private fun noConfigAction(
        action: Element,
        importerId: String,
        code: String,
        raw: String,
        target: String,
    ): FeatureRef? {
        if (!action.otherArgsAreDefault(emptySet())) return null
        return sourceFeature(target, importerId, "TaskerAction:$code", raw)
    }

    private fun keyAction(action: Element, importerId: String, code: String, raw: String, key: String): FeatureRef? {
        if (!action.otherArgsAreDefault(emptySet())) return null
        return sourceFeature(
            "android.input.keyevent", importerId, "TaskerAction:$code", raw,
            extra = mapOf(
                "key" to ConfigValue.StringValue(key),
                "longPress" to ConfigValue.BooleanValue(false),
            ),
        )
    }

    private fun vibrate(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        val duration = action.intArg(0)?.takeIf { it in 1L..60_000L } ?: return null
        if (!action.otherArgsAreDefault(setOf(0))) return null
        return sourceFeature(
            "android.vibrate", importerId, "TaskerAction:$code", raw,
            extra = mapOf("durationMs" to ConfigValue.NumberValue(duration.toDouble())),
        )
    }

    private fun openFile(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        val path = action.stringArg(0)?.takeIf { it.isNotBlank() } ?: return null
        val mimeType = action.stringArg(1).orEmpty()
        if (!action.otherArgsAreDefault(setOf(0, 1))) return null
        return sourceFeature(
            "android.file.open", importerId, "TaskerAction:$code", raw,
            extra = buildMap {
                put("path", ConfigValue.StringValue(path))
                if (mimeType.isNotBlank()) put("mimeType", ConfigValue.StringValue(mimeType))
            },
        )
    }

    private fun openUri(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        val uri = action.stringArg(0)?.takeIf { it.isNotBlank() } ?: return null
        if (!action.otherArgsAreDefault(setOf(0))) return null
        return sourceFeature(
            "android.uri.open", importerId, "TaskerAction:$code", raw,
            extra = mapOf("uri" to ConfigValue.StringValue(uri)),
        )
    }

    private fun torch(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        val mode = action.intArg(0)?.toInt() ?: return null
        if (mode !in 0..1 || !action.otherArgsAreDefault(setOf(0))) return null // 2 = toggle, not lossless.
        return sourceFeature(
            "android.torch.set", importerId, "TaskerAction:$code", raw,
            extra = mapOf("enabled" to ConfigValue.BooleanValue(mode == 1)),
        )
    }

    private fun autoBrightness(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        val enabled = action.booleanArg(0) ?: return null
        if (!enabled || !action.otherArgsAreDefault(setOf(0))) return null
        // Turning auto brightness off needs the previous/manual brightness level to preserve behavior.
        return sourceFeature(
            "android.display.brightness.set", importerId, "TaskerAction:$code", raw,
            extra = mapOf("mode" to ConfigValue.StringValue("auto")),
        )
    }

    private fun displayTimeout(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        // Tasker exports Display Timeout as seconds, minutes, hours in arg0..arg2.
        val seconds = action.intArg(0) ?: 0L
        val minutes = action.intArg(1) ?: 0L
        val hours = action.intArg(2) ?: 0L
        if (seconds !in 0L..59L || minutes !in 0L..59L || hours !in 0L..24L) return null
        if (!action.otherArgsAreDefault(setOf(0, 1, 2))) return null
        val totalMs = seconds * 1_000L + minutes * 60_000L + hours * 3_600_000L
        if (totalMs !in 1_000L..86_400_000L) return null
        return sourceFeature(
            "android.display.screen_timeout.set", importerId, "TaskerAction:$code", raw,
            extra = mapOf("timeoutMs" to ConfigValue.NumberValue(totalMs.toDouble())),
        )
    }

    private fun Element.arg(index: Int): Element? = elementChildren().firstOrNull { it.getAttribute("sr") == "arg$index" }

    private fun Element.stringArg(index: Int): String? {
        val arg = arg(index) ?: return null
        return when {
            arg.hasAttribute("val") -> arg.getAttribute("val")
            else -> arg.textContent?.trim()
        }?.takeIf { it.isNotEmpty() }
    }

    private fun Element.intArg(index: Int): Long? {
        val arg = arg(index) ?: return null
        return arg.getAttribute("val").takeIf { it.isNotBlank() }?.toLongOrNull()
            ?: arg.textContent?.trim()?.toLongOrNull()
    }

    private fun Element.booleanArg(index: Int): Boolean? = when (intArg(index)) {
        0L -> false
        1L -> true
        else -> null
    }

    private fun Element.otherArgsAreDefault(usedIndexes: Set<Int>): Boolean = elementChildren()
        .filter { it.getAttribute("sr").startsWith("arg") }
        .all { element ->
            val index = element.getAttribute("sr").removePrefix("arg").toIntOrNull() ?: return@all false
            if (index in usedIndexes) true else {
                val raw = if (element.hasAttribute("val")) element.getAttribute("val") else element.textContent?.trim().orEmpty()
                raw.isBlank() || raw == "0" || raw == "false"
            }
        }

    private fun Element.childText(name: String): String? =
        elementChildren().firstOrNull { it.tagName == name }?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun Element.elementChildren(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
}
