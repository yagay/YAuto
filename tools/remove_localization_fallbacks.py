#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path('.')
STRING = r'"(?:\\.|[^"\\])*"'
# userText("stable.key", "English fallback"[, args...]) -> userText("stable.key"[, args...])
FALLBACK = re.compile(rf'userText\(\s*({STRING})\s*,\s*{STRING}\s*([,)])')


def strip_usertext_fallbacks() -> None:
    for path in ROOT.glob('**/src/main/kotlin/**/*.kt'):
        text = path.read_text(encoding='utf-8')
        previous = None
        while previous != text:
            previous = text
            text = FALLBACK.sub(lambda m: f'userText({m.group(1)}{m.group(2)}', text)
        path.write_text(text, encoding='utf-8')


def rewrite_usertext_api() -> None:
    path = ROOT / 'core/model/src/main/kotlin/com/yagay/yauto/core/model/UserText.kt'
    path.write_text('''package com.yagay.yauto.core.model

/**
 * Stable localization boundary for non-Android core/platform code.
 *
 * Production Android installs a resource-backed resolver from Application.onCreate(). Core only
 * emits stable message keys plus formatting arguments, so adding a language never requires a
 * business-logic source change. Headless JVM tools/tests fall back to the stable key.
 */
typealias UserTextResolver = (code: String, args: Array<out Any?>) -> String

object UserText {
    @Volatile
    private var resolver: UserTextResolver? = null

    fun install(resolver: UserTextResolver) {
        this.resolver = resolver
    }

    fun reset() {
        resolver = null
    }

    fun text(code: String, vararg args: Any?): String =
        resolver?.invoke(code, args) ?: code
}

fun installUserTextResolver(resolver: UserTextResolver) = UserText.install(resolver)
fun resetUserTextResolver() = UserText.reset()

fun userText(code: String, vararg args: Any?): String = UserText.text(code, *args)
''', encoding='utf-8')


def rewrite_android_resolver() -> None:
    path = ROOT / 'app/src/main/kotlin/com/yagay/yauto/AndroidUserTextResolver.kt'
    path.write_text('''package com.yagay.yauto

import android.content.Context
import com.yagay.yauto.core.model.installUserTextResolver
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private object AndroidUserTextResolver {
    private val resourceIds = ConcurrentHashMap<String, Int>()

    fun install(context: Context) {
        val appContext = context.applicationContext
        installUserTextResolver { code, args ->
            val id = resourceIds.getOrPut(code) {
                appContext.resources.getIdentifier(resourceName(code), "string", appContext.packageName)
            }
            if (id == 0) {
                // Missing translations are a CI error. Keep a stable diagnostic key instead of
                // falling back to language-specific business-source prose.
                code
            } else {
                runCatching {
                    if (args.isEmpty()) appContext.getString(id) else appContext.getString(id, *args)
                }.getOrElse { code }
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
}

fun installAndroidUserTextResolver(context: Context) = AndroidUserTextResolver.install(context)
''', encoding='utf-8')


def close_feature_fallbacks() -> None:
    path = ROOT / 'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/FeatureTextResources.kt'
    text = path.read_text(encoding='utf-8')
    text = re.sub(
        r'''    fun title\(descriptor: FeatureDescriptor\): String =\n        resource\("feature_\$\{resourceKey\(descriptor\.id\.value\)\}_title"\)\n            \?: phrase\(descriptor\.title\)\n            \?: if \(chinese\) genericTitle\(descriptor\) else descriptor\.title\n''',
        '''    fun title(descriptor: FeatureDescriptor): String =\n        resource("feature_${resourceKey(descriptor.id.value)}_title")\n            ?: phrase(descriptor.title)\n            ?: genericTitle(descriptor)\n''',
        text,
    )
    text = re.sub(
        r'''    fun description\(descriptor: FeatureDescriptor\): String =\n        resource\("feature_\$\{resourceKey\(descriptor\.id\.value\)\}_description"\)\n            \?: if \(chinese\) context\.getString\(TextR\.string\.feature_generic_description_format, title\(descriptor\)\)\n            else descriptor\.description\n''',
        '''    fun description(descriptor: FeatureDescriptor): String =\n        resource("feature_${resourceKey(descriptor.id.value)}_description")\n            ?: phrase(descriptor.description)\n            ?: context.getString(TextR.string.feature_generic_description_format, title(descriptor))\n''',
        text,
    )
    text = re.sub(
        r'''    fun fieldLabel\(descriptorId: String, field: FieldSchema\): String =\n        resource\("feature_\$\{resourceKey\(descriptorId\)\}_field_\$\{resourceKey\(field\.key\)\}"\)\n            \?: phrase\(field\.label\)\n            \?: if \(chinese\) context\.getString\(TextR\.string\.feature_generic_parameter\) else field\.label\n''',
        '''    fun fieldLabel(descriptorId: String, field: FieldSchema): String =\n        resource("feature_${resourceKey(descriptorId)}_field_${resourceKey(field.key)}")\n            ?: phrase(field.label)\n            ?: context.getString(TextR.string.feature_generic_parameter)\n''',
        text,
    )
    text = re.sub(
        r'''    fun choiceOption\(descriptorId: String, fieldKey: String, option: String\): String =\n        resource\("feature_\$\{resourceKey\(descriptorId\)\}_field_\$\{resourceKey\(fieldKey\)\}_option_\$\{resourceKey\(option\)\}"\)\n            \?: phrase\(option\)\n            \?: if \(chinese\) context\.getString\(TextR\.string\.feature_generic_option\) else option\n''',
        '''    fun choiceOption(descriptorId: String, fieldKey: String, option: String): String =\n        resource("feature_${resourceKey(descriptorId)}_field_${resourceKey(fieldKey)}_option_${resourceKey(option)}")\n            ?: phrase(option)\n            ?: context.getString(TextR.string.feature_generic_option)\n''',
        text,
    )
    # Locale is still used by rememberFeatureTextResolver; resolver itself no longer branches by language.
    text = re.sub(r'''    private val chinese: Boolean\n        get\(\) = locale\.language\.equals\("zh", ignoreCase = true\)\n\n''', '', text)
    path.write_text(text, encoding='utf-8')


def harden_guard() -> None:
    path = ROOT / '.github/scripts/check-localization.py'
    text = path.read_text(encoding='utf-8')
    marker = '    # Android manifests must not hardcode human-readable labels/descriptions.\n'
    block = '''    # userText call sites must carry only a stable key and formatting args. A literal second\n    # argument is a language-specific fallback and defeats the Android resource architecture.\n    for path in kotlin:\n        text = path.read_text(encoding="utf-8")\n        for match in re.finditer(r'userText\\(\\s*"[^"]+"\\s*,\\s*"', text):\n            failures.append(f"{path}:{line_number(text, match.start())}: remove language-specific userText fallback; keep only key + args")\n\n    # Feature UI must never expose raw descriptor prose when a resource is missing.\n    feature_resolver = Path("ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/FeatureTextResources.kt")\n    if feature_resolver.exists():\n        resolver_text = feature_resolver.read_text(encoding="utf-8")\n        for pattern in (r'else\\s+descriptor\\.title', r'else\\s+descriptor\\.description', r'else\\s+field\\.label', r'else\\s+option'):\n            match = re.search(pattern, resolver_text)\n            if match:\n                failures.append(f"{feature_resolver}:{line_number(resolver_text, match.start())}: raw FeatureDescriptor display fallback is forbidden")\n\n'''
    if block not in text:
        text = text.replace(marker, block + marker)
    path.write_text(text, encoding='utf-8')


def main() -> None:
    strip_usertext_fallbacks()
    rewrite_usertext_api()
    rewrite_android_resolver()
    close_feature_fallbacks()
    harden_guard()
    print('Language-specific Kotlin fallbacks removed.')


if __name__ == '__main__':
    main()
