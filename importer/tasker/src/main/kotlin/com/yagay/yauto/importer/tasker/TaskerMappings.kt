package com.yagay.yauto.importer.tasker

import com.yagay.yauto.core.importer.sourceFeature
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import org.w3c.dom.Element

/** Native mappings for Tasker action codes whose exported argument layout is stable and known. */
object TaskerMappings {
    fun nativeAction(action: Element, code: String, importerId: String, raw: String): FeatureRef? = when (code) {
        "30" -> wait(action, importerId, code, raw)
        "548" -> flash(action, importerId, code, raw)
        "20" -> launchApp(action, importerId, code, raw)
        "547" -> variableSet(action, importerId, code, raw)
        "105" -> setClipboard(action, importerId, code, raw)
        "123" -> runRootShell(action, importerId, code, raw)
        else -> null
    }

    fun performTaskTarget(action: Element): String? = action.stringArg(0)?.takeIf { it.isNotBlank() }
    fun performTaskParam1(action: Element): String? = action.stringArg(2)?.takeIf { it.isNotBlank() }
    fun performTaskParam2(action: Element): String? = action.stringArg(3)?.takeIf { it.isNotBlank() }
    fun performTaskResultVariable(action: Element): String? = action.stringArg(4)?.takeIf { it.isNotBlank() }

    private fun wait(action: Element, importerId: String, code: String, raw: String): FeatureRef {
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
        // Tasker's arg2 is the Use Root flag. Non-root Run Shell is deliberately left as a
        // compatibility action until YAuto has a dedicated unprivileged shell backend.
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

    private fun Element.childText(name: String): String? =
        elementChildren().firstOrNull { it.tagName == name }?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun Element.elementChildren(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
}
