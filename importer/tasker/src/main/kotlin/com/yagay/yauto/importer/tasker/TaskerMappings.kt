package com.yagay.yauto.importer.tasker

import com.yagay.yauto.core.importer.sourceFeature
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import org.w3c.dom.Element
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Native mappings for Tasker action codes whose exported argument layout is stable and known. */
object TaskerMappings {
    fun nativeAction(action: Element, code: String, importerId: String, raw: String): FeatureRef? {
        pluginFeature(action, importerId, raw, "android.plugin.locale.action")?.let { return it }
        return when (code) {
        "20" -> launchApp(action, importerId, code, raw)
        "25" -> noConfigAction(action, importerId, code, raw, "android.home.launch")
        "30" -> wait(action, importerId, code, raw)
        "61" -> vibrate(action, importerId, code, raw)
        "63" -> javaCode(action, importerId, code, raw)
        "102" -> openFile(action, importerId, code, raw)
        "104" -> openUri(action, importerId, code, raw)
        "105" -> setClipboard(action, importerId, code, raw)
        "123" -> runShell(action, importerId, code, raw)
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
        "375" -> adbWifi(action, importerId, code, raw)
        "410" -> writeFile(action, importerId, code, raw)
        "547" -> variableSet(action, importerId, code, raw)
        "549" -> variableClear(action, importerId, code, raw)
        "664" -> javaFunction(action, importerId, code, raw)
        "665" -> javaObject(action, importerId, code, raw)
        "548" -> flash(action, importerId, code, raw)
        "806" -> noConfigAction(action, importerId, code, raw, "android.screen.wake")
        "808" -> autoBrightness(action, importerId, code, raw)
        "812" -> displayTimeout(action, importerId, code, raw)
        "822" -> booleanAction(action, importerId, code, raw, "android.display.auto_rotate.set")
        else -> null
        }
    }


    fun nativeContext(
        context: Element,
        code: String,
        tagName: String,
        importerId: String,
        raw: String,
    ): FeatureRef? {
        if (tagName == "Event") {
            pluginFeature(context, importerId, raw, "android.event.plugin_locale")?.let { return it }
        } else if (tagName == "State") {
            pluginFeature(context, importerId, raw, "android.plugin.locale.condition")?.let { return it }
        }
        if (tagName != "Event") return null
        if (code == "599") return intentReceived(context, importerId, raw)
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

    fun forVariable(action: Element): String? =
        action.stringArg(0)?.trim()?.takeIf { it.startsWith("%") }

    fun forItems(action: Element): String? =
        action.stringArg(1)?.takeIf { it.isNotBlank() }

    fun forMode(action: Element): Long = action.intArg(2) ?: 0L

    fun waitUntilPollIntervalMs(action: Element): Long {
        val milliseconds = action.intArg(0) ?: 0L
        val seconds = action.intArg(1) ?: 0L
        val minutes = action.intArg(2) ?: 0L
        val hours = action.intArg(3) ?: 0L
        val total = milliseconds + seconds * 1_000L + minutes * 60_000L + hours * 3_600_000L
        return total.takeIf { it > 0L }?.coerceIn(100L, 60_000L) ?: 1_000L
    }

    fun performTaskTarget(action: Element): String? = action.stringArg(0)?.takeIf { it.isNotBlank() }
    fun performTaskParam1(action: Element): String? = action.stringArg(2)?.takeIf { it.isNotBlank() }
    fun performTaskParam2(action: Element): String? = action.stringArg(3)?.takeIf { it.isNotBlank() }
    fun performTaskResultVariable(action: Element): String? = action.stringArg(4)?.takeIf { it.isNotBlank() }

    private fun javaCode(
        action: Element,
        importerId: String,
        code: String,
        raw: String,
    ): FeatureRef? {
        val script = action.stringArg(0)?.takeIf { it.isNotBlank() } ?: return null
        val timeoutSeconds = action.intArg(1)?.coerceIn(0L, 600L) ?: 0L
        val resultVariable = action.stringArg(2).orEmpty()
        return sourceFeature(
            "script.tasker.beanshell.execute",
            importerId,
            "TaskerAction:" + code,
            raw,
            extra = buildMap {
                put("script", ConfigValue.StringValue(script))
                if (timeoutSeconds > 0L) {
                    put("timeoutMs", ConfigValue.NumberValue(timeoutSeconds * 1_000.0))
                }
                if (resultVariable.isNotBlank()) {
                    put("resultVariable", ConfigValue.StringValue(resultVariable))
                }
            },
        )
    }

    private fun pluginFeature(
        element: Element,
        importerId: String,
        raw: String,
        target: String,
    ): FeatureRef? {
        val bundle = element.arg(0)?.takeIf { it.tagName == "Bundle" } ?: return null
        val packageName = element.stringArg(1)?.trim().orEmpty()
        val activityClass = element.stringArg(2)?.trim().orEmpty()
        if (!PACKAGE.matches(packageName) || activityClass.isBlank()) return null
        val timeoutSeconds = element.intArg(3)?.coerceIn(0L, 3_600L) ?: 10L
        return sourceFeature(
            target,
            importerId,
            "TaskerPlugin",
            raw,
            extra = mapOf(
                "package" to ConfigValue.StringValue(packageName),
                "receiverClass" to ConfigValue.StringValue(activityClass),
                "bundleJson" to ConfigValue.StringValue(bundleToJson(bundle).toString()),
                "timeoutMs" to ConfigValue.NumberValue((timeoutSeconds.coerceAtLeast(1L) * 1_000L).toDouble()),
            ),
        )
    }

    private fun bundleToJson(bundle: Element): JsonObject {
        val values = bundle.elementChildren().firstOrNull { it.tagName == "Vals" } ?: bundle
        val children = values.elementChildren()
        val types = children
            .filter { it.tagName.endsWith("-type") }
            .associate { it.tagName.removeSuffix("-type") to it.textContent.trim() }
        val pairs = linkedMapOf<String, JsonPrimitive>()
        children
            .filterNot { it.tagName.endsWith("-type") }
            .forEach { child ->
                val key = child.tagName
                val raw = child.textContent.orEmpty()
                val type = types[key].orEmpty()
                val value = when {
                    type.endsWith("Boolean") -> JsonPrimitive(raw.equals("true", true) || raw == "1")
                    type.endsWith("Integer") || type.endsWith("Long") ->
                        raw.trim().toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw)
                    type.endsWith("Float") || type.endsWith("Double") ->
                        raw.trim().toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw)
                    else -> JsonPrimitive(raw)
                }
                pairs[key] = value
            }
        return JsonObject(pairs)
    }

    private fun javaFunction(
        action: Element,
        importerId: String,
        code: String,
        raw: String,
    ): FeatureRef? {
        val resultTarget = action.stringArg(0).orEmpty()
        val target = action.stringArg(1)?.takeIf(String::isNotBlank) ?: return null
        val signature = action.stringArg(2)?.takeIf(String::isNotBlank) ?: return null
        return sourceFeature(
            "script.java_function",
            importerId,
            "TaskerAction:" + code,
            raw,
            extra = buildMap {
                put("resultTarget", ConfigValue.StringValue(resultTarget))
                put("target", ConfigValue.StringValue(target))
                put("signature", ConfigValue.StringValue(signature))
                for (index in 0..6) {
                    val value = action.stringArg(index + 3).orEmpty()
                    put("arg" + index, ConfigValue.StringValue(value))
                }
            },
        )
    }

    private fun javaObject(
        action: Element,
        importerId: String,
        code: String,
        raw: String,
    ): FeatureRef? {
        val mode = action.intArg(0) ?: return null
        val name = action.stringArg(1).orEmpty()
        return when (mode) {
            0L -> if (name.isBlank()) null else sourceFeature(
                "script.java_object.manage",
                importerId,
                "TaskerAction:" + code,
                raw,
                extra = mapOf(
                    "operation" to ConfigValue.StringValue("delete"),
                    "name" to ConfigValue.StringValue(name),
                ),
            )
            else -> null
        }
    }

    private fun adbWifi(
        action: Element,
        importerId: String,
        code: String,
        raw: String,
    ): FeatureRef? {
        val command = action.stringArg(0)?.takeIf(String::isNotBlank) ?: return null
        return sourceFeature(
            "android.adb_wifi.command",
            importerId,
            "TaskerAction:" + code,
            raw,
            extra = buildMap {
                put("command", ConfigValue.StringValue(command))
                put("transport", ConfigValue.StringValue("privileged"))
                action.stringArg(1)?.takeIf(String::isNotBlank)?.let {
                    put("resultVariable", ConfigValue.StringValue(it))
                }
            },
        )
    }

    private fun wait(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        // Tasker 6.x exports Wait as hours, minutes, milliseconds and seconds in arg0..arg3.
        val hours = action.intArg(0) ?: 0L
        val minutes = action.intArg(1) ?: 0L
        val milliseconds = action.intArg(2) ?: 0L
        val seconds = action.intArg(3) ?: 0L
        if (hours !in 0L..24L || minutes !in 0L..59L || milliseconds !in 0L..999L || seconds !in 0L..59L) return null
        if (!action.otherArgsAreDefault(setOf(0, 1, 2, 3))) return null
        val total = hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + milliseconds
        return sourceFeature(
            "core.delay", importerId, "TaskerAction:$code", raw,
            extra = mapOf("durationMs" to ConfigValue.NumberValue(total.toDouble())),
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

    private fun runShell(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        // Tasker 6.x: arg0 command, arg1 Use Root, arg2 timeout seconds, arg3 stdout, arg4 stderr, arg5 stdin.
        val command = action.stringArg(0)?.takeIf(String::isNotBlank) ?: return null
        val useRoot = action.booleanArg(1) ?: false
        val timeoutSeconds = (action.intArg(2) ?: 0L).coerceAtLeast(0L)
        if (timeoutSeconds > 300L) return null
        val stdoutVariable = action.stringArg(3).orEmpty()
        val stderrVariable = action.stringArg(4).orEmpty()
        val stdin = action.stringArg(5).orEmpty()
        if (stdin.isNotBlank() || !action.otherArgsAreDefault(setOf(0, 1, 2, 3, 4, 5))) return null
        return sourceFeature(
            if (useRoot) "android.shell.execute" else "android.shell.execute_unprivileged",
            importerId,
            "TaskerAction:$code",
            raw,
            extra = buildMap {
                put("command", ConfigValue.StringValue(command))
                if (timeoutSeconds > 0L) put("timeoutMs", ConfigValue.NumberValue(timeoutSeconds * 1_000.0))
                if (stdoutVariable.isNotBlank()) put("stdoutVariable", ConfigValue.StringValue(stdoutVariable))
                if (stderrVariable.isNotBlank()) put("stderrVariable", ConfigValue.StringValue(stderrVariable))
            },
        )
    }

    private fun writeFile(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        val path = action.stringArg(0)?.takeIf(String::isNotBlank) ?: return null
        var text = action.stringArg(1).orEmpty()
        val append = action.booleanArg(2) ?: false
        val addNewline = action.booleanArg(3) ?: false
        if (!action.otherArgsAreDefault(setOf(0, 1, 2, 3))) return null
        if (addNewline) text += "\n"
        return sourceFeature(
            "file.write_text",
            importerId,
            "TaskerAction:$code",
            raw,
            extra = mapOf(
                "path" to ConfigValue.StringValue(path),
                "text" to ConfigValue.StringValue(text),
                "append" to ConfigValue.BooleanValue(append),
                "createParents" to ConfigValue.BooleanValue(false),
            ),
        )
    }

    private fun variableClear(action: Element, importerId: String, code: String, raw: String): FeatureRef? {
        val name = action.stringArg(0)?.takeIf(String::isNotBlank) ?: return null
        if (!action.otherArgsAreDefault(setOf(0))) return null
        return sourceFeature(
            "variable.clear",
            importerId,
            "TaskerAction:$code",
            raw,
            extra = mapOf("name" to ConfigValue.StringValue(name)),
        )
    }

    private fun intentReceived(context: Element, importerId: String, raw: String): FeatureRef? {
        val action = context.stringArg(0)?.takeIf(String::isNotBlank) ?: return null
        val priority = context.intArg(1) ?: 0L
        val stopEvent = context.intArg(2) ?: 0L
        val category = context.stringArg(3).orEmpty()
        val data = context.stringArg(4).orEmpty()
        if (priority != 0L || stopEvent != 0L || category.isNotBlank() || data.isNotBlank()) return null
        return sourceFeature(
            "android.event.broadcast",
            importerId,
            "TaskerEvent:599",
            raw,
            extra = mapOf("action" to ConfigValue.StringValue(action)),
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

    private val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    private fun Element.elementChildren(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
}
