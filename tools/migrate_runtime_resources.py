#!/usr/bin/env python3
from __future__ import annotations

import html
import re
from pathlib import Path

ROOT = Path('.')


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, text: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')


def extract_map(text: str, marker: str) -> dict[str, str]:
    start = text.index(marker) + len(marker)
    end = text.index('\n    )', start)
    body = text[start:end]
    out: dict[str, str] = {}
    for match in re.finditer(r'"([^"\\]+)"\s+to\s+"((?:\\.|[^"\\])*)",', body):
        key = match.group(1)
        value = match.group(2)
        value = value.replace('\\"', '"').replace('\\n', '\n').replace('\\t', '\t').replace('\\\\', '\\')
        out[key] = value
    return out


def resource_name(code: str) -> str:
    return 'runtime_' + re.sub(r'[^a-z0-9]+', '_', code.lower()).strip('_')


def android_format(value: str) -> str:
    index = 0
    def repl(match: re.Match[str]) -> str:
        nonlocal index
        index += 1
        return f'%{index}${match.group(1)}'
    return re.sub(r'%(?!\d+\$)([sdf])', repl, value)


def xml_text(value: str) -> str:
    return html.escape(android_format(value), quote=False).replace("'", '&apos;')


def write_runtime_resources(en: dict[str, str], zh: dict[str, str]) -> None:
    if set(en) != set(zh):
        missing_zh = sorted(set(en) - set(zh))
        missing_en = sorted(set(zh) - set(en))
        raise RuntimeError(f'UserText catalog mismatch: missing zh={missing_zh}, missing en={missing_en}')

    def render(values: dict[str, str]) -> str:
        lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
        for code in sorted(values):
            lines.append(f'    <string name="{resource_name(code)}">{xml_text(values[code])}</string>')
        lines.append('</resources>')
        return '\n'.join(lines) + '\n'

    write('ui/design/src/main/res/values/runtime_messages.xml', render(en))
    write('ui/design/src/main/res/values-zh-rCN/runtime_messages.xml', render(zh))


def replace_core_catalog() -> None:
    write('core/model/src/main/kotlin/com/yagay/yauto/core/model/UserText.kt', '''package com.yagay.yauto.core.model

import java.util.Locale

/**
 * Stable localization boundary for non-Android core/platform code.
 *
 * Core emits stable message keys and a default fallback. Android installs a resource-backed
 * resolver at process start, so adding languages only requires Android resource files. Headless
 * JVM tests/tools keep using the fallback without depending on android.* APIs.
 */
typealias UserTextResolver = (code: String, fallback: String?, args: Array<out Any?>) -> String

object UserText {
    @Volatile
    private var resolver: UserTextResolver? = null

    fun install(resolver: UserTextResolver) {
        this.resolver = resolver
    }

    fun reset() {
        resolver = null
    }

    fun text(code: String, fallback: String? = null, vararg args: Any?): String {
        resolver?.let { installed ->
            return installed(code, fallback, args)
        }
        return formatFallback(fallback ?: code, args)
    }

    private fun formatFallback(pattern: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return pattern
        return runCatching { String.format(Locale.getDefault(), pattern, *args) }.getOrElse { pattern }
    }
}

fun installUserTextResolver(resolver: UserTextResolver) = UserText.install(resolver)
fun resetUserTextResolver() = UserText.reset()

fun userText(code: String, fallback: String? = null, vararg args: Any?): String =
    UserText.text(code, fallback, *args)
''')


def add_android_resolver() -> None:
    write('app/src/main/kotlin/com/yagay/yauto/AndroidUserTextResolver.kt', '''package com.yagay.yauto

import android.content.Context
import com.yagay.yauto.core.model.installUserTextResolver
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private object AndroidUserTextResolver {
    private val resourceIds = ConcurrentHashMap<String, Int>()

    fun install(context: Context) {
        val appContext = context.applicationContext
        installUserTextResolver { code, fallback, args ->
            val id = resourceIds.getOrPut(code) {
                appContext.resources.getIdentifier(resourceName(code), "string", appContext.packageName)
            }
            if (id != 0) {
                runCatching {
                    if (args.isEmpty()) appContext.getString(id) else appContext.getString(id, *args)
                }.getOrElse { formatFallback(fallback ?: code, args) }
            } else {
                formatFallback(fallback ?: code, args)
            }
        }
    }

    private fun resourceName(code: String): String = buildString {
        append("runtime_")
        var underscore = false
        code.lowercase(Locale.ROOT).forEach { ch ->
            if (ch.isLetterOrDigit()) {
                append(ch)
                underscore = false
            } else if (!underscore) {
                append('_')
                underscore = true
            }
        }
    }.trimEnd('_')

    private fun formatFallback(pattern: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return pattern
        return runCatching { String.format(Locale.getDefault(), pattern, *args) }.getOrElse { pattern }
    }
}

fun installAndroidUserTextResolver(context: Context) = AndroidUserTextResolver.install(context)
''')

    path = 'app/src/main/kotlin/com/yagay/yauto/YAutoApplication.kt'
    text = read(path)
    old = '''class YAutoApplication : Application() {
    val graph: AppGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppGraph(this) }
}
'''
    new = '''class YAutoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installAndroidUserTextResolver(this)
    }

    val graph: AppGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppGraph(this) }
}
'''
    if old in text:
        text = text.replace(old, new)
    elif 'installAndroidUserTextResolver(this)' not in text:
        raise RuntimeError('YAutoApplication layout changed')
    write(path, text)


def make_feature_phrases_resource_backed_for_all_locales() -> None:
    path = 'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/FeatureTextResources.kt'
    text = read(path)
    old = '''    private fun phrase(text: String): String? {
        if (!chinese) return null
        return resource("feature_phrase_${resourceKey(text)}")
    }
'''
    new = '''    private fun phrase(text: String): String? =
        resource("feature_phrase_${resourceKey(text)}")
'''
    if old in text:
        text = text.replace(old, new)
    elif 'private fun phrase(text: String): String? =' not in text:
        raise RuntimeError('FeatureTextResolver phrase function changed')
    write(path, text)


def harden_guard_for_resource_backed_runtime_messages() -> None:
    path = '.github/scripts/check-localization.py'
    text = read(path)
    text = text.replace('''CJK_KOTLIN_ALLOW = {
    Path("core/model/src/main/kotlin/com/yagay/yauto/core/model/UserText.kt"),
}
''', '''CJK_KOTLIN_ALLOW: set[Path] = set()
''')

    anchor = '''    # Runtime/import/diagnostic wrapper prose must be localized. Raw stack traces, logcat, shell
'''
    check = '''    # Every userText() key must have default-English and zh-CN Android resources. This keeps
    # runtime/diagnostic/import messages on the same standard Android localization path as UI copy.
    runtime_en = resource_keys(Path("ui/design/src/main/res/values"), failures)
    runtime_zh = resource_keys(Path("ui/design/src/main/res/values-zh-rCN"), failures)
    for path in kotlin:
        text = path.read_text(encoding="utf-8")
        for match in re.finditer(r'userText\\(\\s*"([^"]+)"', text):
            name = "runtime_" + re.sub(r'[^a-z0-9]+', '_', match.group(1).lower()).strip('_')
            if name not in runtime_en:
                failures.append(f"{path}:{line_number(text, match.start())}: missing default runtime string resource: {name}")
            if name not in runtime_zh:
                failures.append(f"{path}:{line_number(text, match.start())}: missing zh-CN runtime string resource: {name}")

'''
    if check not in text:
        text = text.replace(anchor, check + anchor)
    write(path, text)


def main() -> None:
    source = read('core/model/src/main/kotlin/com/yagay/yauto/core/model/UserText.kt')
    if 'private val english = mapOf(' in source:
        en = extract_map(source, 'private val english = mapOf(')
        zh = extract_map(source, 'private val simplifiedChinese = mapOf(')
        write_runtime_resources(en, zh)
        replace_core_catalog()
    elif not (ROOT / 'ui/design/src/main/res/values/runtime_messages.xml').exists():
        raise RuntimeError('Core catalog already migrated but runtime resources are missing')

    add_android_resolver()
    make_feature_phrases_resource_backed_for_all_locales()
    harden_guard_for_resource_backed_runtime_messages()
    print('Runtime messages migrated to standard Android resources.')


if __name__ == '__main__':
    main()
