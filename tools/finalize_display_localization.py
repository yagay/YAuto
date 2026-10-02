#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path('.')


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf-8')


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    if old in text:
        write(path, text.replace(old, new, 1))
        return
    if new in text:
        return
    raise RuntimeError(f'{path}: expected source fragment not found:\n{old[:220]}')


def replace_all(path: str, old: str, new: str) -> None:
    text = read(path)
    if old in text:
        write(path, text.replace(old, new))
        return
    if new in text:
        return
    raise RuntimeError(f'{path}: expected source fragment not found:\n{old[:220]}')


def set_string(path: str, name: str, value: str) -> None:
    text = read(path)
    escaped = escape(value)
    pattern = re.compile(rf'(<string\s+name="{re.escape(name)}"[^>]*>)(.*?)(</string>)', re.S)
    match = pattern.search(text)
    if match:
        replacement = match.group(1) + escaped + match.group(3)
        updated = text[:match.start()] + replacement + text[match.end():]
    else:
        marker = '</resources>'
        if marker not in text:
            raise RuntimeError(f'{path}: missing </resources>')
        updated = text.replace(marker, f'    <string name="{name}">{escaped}</string>\n{marker}', 1)
    if updated != text:
        write(path, updated)


def ensure_file(path: str, content: str) -> None:
    target = ROOT / path
    if not target.exists() or target.read_text(encoding='utf-8') != content:
        write(path, content)


def patch_home() -> None:
    path = 'ui/home/src/main/kotlin/com/yagay/yauto/ui/home/MacroHomeScreen.kt'
    replace_once(
        path,
        '                        icon = { Text(tabGlyph(item)) },',
        '''                        icon = {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(tabIcon(item)),
                                contentDescription = null,
                            )
                        },''',
    )
    replace_all(path, 'importerNames.joinToString(" / ")', 'localizedList(importerNames)')
    replace_once(
        path,
        '''private fun tabGlyph(tab: HomeTab): String = when (tab) {
    HomeTab.HOME -> "⌂"
    HomeTab.AUTOMATIONS -> "≡"
    HomeTab.FLOWS -> "↳"
    HomeTab.SETTINGS -> "⚙"
}''',
        '''@androidx.annotation.DrawableRes
private fun tabIcon(tab: HomeTab): Int = when (tab) {
    HomeTab.HOME -> R.drawable.ic_home
    HomeTab.AUTOMATIONS -> R.drawable.ic_automations
    HomeTab.FLOWS -> R.drawable.ic_flows
    HomeTab.SETTINGS -> R.drawable.ic_settings
}''',
    )


def patch_automation_editor() -> None:
    path = 'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/MacroAutomationEditorScreen.kt'
    replace_once(
        path,
        '                            Text(if (advanced) "⌃" else "⌄")',
        '''                            Icon(
                                painter = androidx.compose.ui.res.painterResource(
                                    if (advanced) TextR.drawable.ic_arrow_up else TextR.drawable.ic_arrow_down
                                ),
                                contentDescription = stringResource(
                                    if (advanced) TextR.string.icon_collapse else TextR.string.icon_expand
                                ),
                            )''',
    )
    replace_all(path, 'featureSummary(feature)', 'featureSummary(feature, descriptors)')
    replace_once(
        path,
        '''private fun featureSummary(feature: FeatureRef): String = feature.config.entries
    .filterNot { it.key.startsWith("source.") }
    .take(3)
    .joinToString(" · ") { "${it.key}=${it.value.asText()}" }''',
        '''@Composable
private fun featureSummary(feature: FeatureRef, descriptors: List<FeatureDescriptor>): String {
    val descriptor = descriptors.firstOrNull { it.id.value == feature.typeId }
    val items = feature.config.entries
        .filterNot { it.key.startsWith("source.") }
        .take(3)
        .map { entry ->
            val label = descriptor?.let { owner ->
                owner.fields.firstOrNull { it.key == entry.key }?.let { field ->
                    localizedFieldLabelShared(owner.id.value, field)
                }
            } ?: entry.key
            stringResource(TextR.string.flow_config_entry_format, label, entry.value.asText())
        }
    return localizedList(items)
}''',
    )


def patch_flow_editor() -> None:
    path = 'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/MacroFlowEditorScreen.kt'
    replace_once(
        path,
        '''                            subtitle = buildString {
                                append(parameter.type.name)
                                if (parameter.required) append(stringResource(TextR.string.flow_parameter_required_suffix))
                                append(defaultLabel(parameter))
                            },''',
        '                            subtitle = flowParameterSummary(parameter),',
    )
    replace_once(
        path,
        '''                    actions.forEachIndexed { index, node ->
                        val feature = (node as? ActionNode.Action)?.feature
                        MacroItemRow(
                            title = if (feature != null) {
                                descriptors.firstOrNull { it.id.value == feature.typeId }?.let { localizedFeatureTitle(it) }
                                    ?: feature.typeId
                            } else {
                                flowNodeTitle(node, flows)
                            },
                            subtitle = feature?.config?.entries
                                ?.filterNot { it.key.startsWith("source.") }
                                ?.take(3)
                                ?.joinToString(" · ") { "${it.key}=${flowValueText(it.value)}" },''',
        '''                    actions.forEachIndexed { index, node ->
                        val feature = (node as? ActionNode.Action)?.feature
                        val descriptor = feature?.let { ref ->
                            descriptors.firstOrNull { it.id.value == ref.typeId }
                        }
                        val summary = feature?.config?.entries
                            ?.filterNot { it.key.startsWith("source.") }
                            ?.take(3)
                            ?.map { entry ->
                                val label = descriptor?.let { owner ->
                                    owner.fields.firstOrNull { it.key == entry.key }?.let { field ->
                                        localizedFieldLabelShared(owner.id.value, field)
                                    }
                                } ?: entry.key
                                stringResource(TextR.string.flow_config_entry_format, label, flowValueText(entry.value))
                            }
                            ?.let(::localizedList)
                        MacroItemRow(
                            title = descriptor?.let { localizedFeatureTitle(it) }
                                ?: feature?.typeId
                                ?: flowNodeTitle(node, flows),
                            subtitle = summary,''',
    )
    replace_once(path, '                            subtitle = parameter.type.name,', '                            subtitle = valueTypeLabel(parameter.type),')
    replace_once(
        path,
        '                        Text(stringResource(TextR.string.flow_parameter_type_format, type.name))',
        '                        Text(stringResource(TextR.string.flow_parameter_type_format, valueTypeLabel(type)))',
    )
    replace_once(path, '                                text = { Text(item.name) },', '                                text = { Text(valueTypeLabel(item)) },')
    replace_once(
        path,
        '''@Composable
private fun defaultLabel(parameter: FlowParameter): String = when (parameter.defaultValue) {
    ConfigValue.NullValue -> ""
    else -> stringResource(TextR.string.flow_default_suffix_format, flowValueText(parameter.defaultValue))
}''',
        '''@Composable
private fun flowParameterSummary(parameter: FlowParameter): String {
    val parts = buildList {
        add(valueTypeLabel(parameter.type))
        if (parameter.required) add(stringResource(TextR.string.flow_parameter_required))
        if (parameter.defaultValue != ConfigValue.NullValue) {
            add(stringResource(TextR.string.flow_default_value_format, flowValueText(parameter.defaultValue)))
        }
    }
    return localizedList(parts)
}

@Composable
private fun valueTypeLabel(type: ValueType): String = stringResource(
    when (type) {
        ValueType.STRING -> TextR.string.flow_value_type_string
        ValueType.NUMBER -> TextR.string.flow_value_type_number
        ValueType.BOOLEAN -> TextR.string.flow_value_type_boolean
        ValueType.LIST -> TextR.string.flow_value_type_list
        ValueType.OBJECT -> TextR.string.flow_value_type_object
        ValueType.APP -> TextR.string.flow_value_type_app
        ValueType.PACKAGE -> TextR.string.flow_value_type_package
        ValueType.COMPONENT -> TextR.string.flow_value_type_component
        ValueType.URI -> TextR.string.flow_value_type_uri
        ValueType.FILE -> TextR.string.flow_value_type_file
        ValueType.DATE_TIME -> TextR.string.flow_value_type_date_time
        ValueType.DURATION -> TextR.string.flow_value_type_duration
        ValueType.COLOR -> TextR.string.flow_value_type_color
        ValueType.LOCATION -> TextR.string.flow_value_type_location
        ValueType.ANY -> TextR.string.flow_value_type_any
    }
)''',
    )


def patch_feature_picker() -> None:
    path = 'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/MacroFeaturePicker.kt'
    replace_once(
        path,
        'import com.yagay.yauto.ui.design.MacroPalette\n',
        'import com.yagay.yauto.ui.design.MacroPalette\nimport com.yagay.yauto.ui.design.localizedList\n',
    )
    replace_once(
        path,
        '                subtitle + " · " + stringResource(TextR.string.feature_picker_favorite_hint),',
        '''                stringResource(
                    TextR.string.feature_picker_category_hint_format,
                    subtitle,
                    stringResource(TextR.string.feature_picker_favorite_hint),
                ),''',
    )
    replace_once(
        path,
        "                                CapabilityBadge(it.value.substringAfterLast('.'))",
        '                                CapabilityBadge(capabilityLabel(it.value))',
    )
    replace_once(
        path,
        '                    requirements.joinToString(" + ") { accessRequirementLabelNonComposable(it) },',
        '                    localizedList(requirements.map { accessRequirementLabelNonComposable(it) }),',
    )
    replace_once(
        path,
        '                stringResource(TextR.string.implementation_pros_format, pros.joinToString(" · ")),',
        '                stringResource(TextR.string.implementation_pros_format, localizedList(pros)),',
    )
    replace_once(
        path,
        '                stringResource(TextR.string.implementation_cons_format, cons.joinToString(" · ")),',
        '                stringResource(TextR.string.implementation_cons_format, localizedList(cons)),',
    )
    replace_all(
        path,
        'label = { Text(label + if (field.required) " *" else "") },',
        '''label = {
                    Text(
                        if (field.required) stringResource(TextR.string.editor_required_field_format, label)
                        else label
                    )
                },''',
    )
    marker = '''@Composable
private fun accessRequirementLabel(requirement: AccessRequirement): String = stringResource(accessRequirementResource(requirement))
'''
    addition = '''@Composable
private fun capabilityLabel(capabilityId: String): String = stringResource(
    when (capabilityId) {
        "privileged.shell" -> TextR.string.capability_privileged_shell
        "android.app.launch" -> TextR.string.capability_app_launch
        "android.toast" -> TextR.string.capability_toast
        "android.accessibility" -> TextR.string.capability_accessibility
        "android.notification_listener" -> TextR.string.capability_notification_listener
        "android.systemui" -> TextR.string.capability_system_ui
        "android.lsposed" -> TextR.string.capability_lsposed
        else -> TextR.string.capability_other
    }
)

'''
    text = read(path)
    if addition not in text:
        if marker not in text:
            raise RuntimeError(f'{path}: access requirement marker not found')
        write(path, text.replace(marker, addition + marker, 1))


def patch_action_tree() -> None:
    path = 'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/ActionTreeEditor.kt'
    replace_once(
        path,
        '''        scalarKinds.forEach { (kind, label) ->
            TextButton(onClick = {
                onChange(
                    when (kind) {
                        ValueEditorKind.NUMBER -> ConfigValue.NumberValue(0.0)
                        ValueEditorKind.BOOLEAN -> ConfigValue.BooleanValue(false)
                        ValueEditorKind.NULL -> ConfigValue.NullValue
                        ValueEditorKind.TEXT -> ConfigValue.StringValue("")
                    }
                )
            }) { Text(if (kind == currentKind && value !is ConfigValue.ListValue && value !is ConfigValue.ObjectValue) "[$label]" else label) }
        }''',
        '''        scalarKinds.forEach { (kind, label) ->
            FilterChip(
                selected = kind == currentKind && value !is ConfigValue.ListValue && value !is ConfigValue.ObjectValue,
                onClick = {
                    onChange(
                        when (kind) {
                            ValueEditorKind.NUMBER -> ConfigValue.NumberValue(0.0)
                            ValueEditorKind.BOOLEAN -> ConfigValue.BooleanValue(false)
                            ValueEditorKind.NULL -> ConfigValue.NullValue
                            ValueEditorKind.TEXT -> ConfigValue.StringValue("")
                        }
                    )
                },
                label = { Text(label) },
            )
        }''',
    )


def patch_macro_style() -> None:
    path = 'ui/design/src/main/kotlin/com/yagay/yauto/ui/design/MacroStyle.kt'
    replace_once(
        path,
        'import androidx.compose.ui.graphics.Color\n',
        'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.res.stringResource\n',
    )
    replace_once(
        path,
        '''                Text(
                    buildString {
                        append(title)
                        count?.let { append("  ($it)") }
                    },''',
        '''                Text(
                    count?.let { stringResource(R.string.macro_section_title_count_format, title, it) } ?: title,''',
    )


def patch_quick_settings() -> None:
    path = 'platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidQuickSettingsFeaturePack.kt'
    replace_once(
        path,
        '    fun label(slot: Int): String = prefs.getString("label_$slot", null).orEmpty().ifBlank { "YAuto $slot" }',
        '    fun label(slot: Int): String = prefs.getString("label_$slot", null).orEmpty().ifBlank { userText("qs.tile.default_label", slot) }',
    )


def create_shared_formatting() -> None:
    ensure_file(
        'ui/design/src/main/kotlin/com/yagay/yauto/ui/design/LocalizedFormatting.kt',
        '''package com.yagay.yauto.ui.design

/** Locale-aware list formatting for visible UI copy. */
fun localizedList(values: Iterable<String>): String {
    val items = values.toList()
    return when (items.size) {
        0 -> ""
        1 -> items.single()
        else -> android.icu.text.ListFormatter.getInstance().format(*items.toTypedArray())
    }
}
''',
    )


def create_navigation_icons() -> None:
    icons = {
        'ic_home.xml': 'M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z',
        'ic_automations.xml': 'M3,5h2v2H3V5m4,0h14v2H7V5M3,11h2v2H3v-2m4,0h14v2H7v-2M3,17h2v2H3v-2m4,0h14v2H7v-2',
        'ic_flows.xml': 'M5,3h6v5h4v4h4v9h-2v-7h-4v-4h-2v4H7v7H5V3',
        'ic_settings.xml': 'M4,6h10v2H4V6m14,-2h2v6h-2V4M4,11h4v2H4v-2m8,-2h2v6h-2V9m-8,7h10v2H4v-2m14,-2h2v6h-2v-6',
    }
    for name, data in icons.items():
        ensure_file(
            f'ui/design/src/main/res/drawable/{name}',
            f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path android:fillColor="#FF000000" android:pathData="{data}" />
</vector>
''',
        )


def patch_resources() -> None:
    en_strings = 'ui/design/src/main/res/values/strings.xml'
    zh_strings = 'ui/design/src/main/res/values-zh-rCN/strings.xml'
    en_editor = 'ui/design/src/main/res/values/editor_strings.xml'
    zh_editor = 'ui/design/src/main/res/values-zh-rCN/editor_strings.xml'
    en_flow = 'ui/design/src/main/res/values/flow_strings.xml'
    zh_flow = 'ui/design/src/main/res/values-zh-rCN/flow_strings.xml'
    en_tree = 'ui/design/src/main/res/values/action_tree_strings.xml'
    zh_tree = 'ui/design/src/main/res/values-zh-rCN/action_tree_strings.xml'
    en_auto = 'ui/design/src/main/res/values/automation_strings.xml'
    zh_auto = 'ui/design/src/main/res/values-zh-rCN/automation_strings.xml'
    en_runtime = 'ui/design/src/main/res/values/runtime_messages.xml'
    zh_runtime = 'ui/design/src/main/res/values-zh-rCN/runtime_messages.xml'

    for path, values in (
        (en_strings, {
            'icon_expand': 'Expand',
            'icon_collapse': 'Collapse',
            'macro_section_title_count_format': '%1$s (%2$d)',
            'feature_picker_category_hint_format': '%1$s. %2$s',
            'capability_privileged_shell': 'Privileged shell',
            'capability_app_launch': 'App launch',
            'capability_toast': 'Toast',
            'capability_accessibility': 'Accessibility',
            'capability_notification_listener': 'Notification access',
            'capability_system_ui': 'System UI',
            'capability_lsposed': 'LSPosed',
            'capability_other': 'Advanced capability',
        }),
        (zh_strings, {
            'icon_expand': '展开',
            'icon_collapse': '收起',
            'macro_section_title_count_format': '%1$s（%2$d）',
            'feature_picker_category_hint_format': '%1$s。%2$s',
            'capability_privileged_shell': '特权 Shell',
            'capability_app_launch': '启动应用',
            'capability_toast': 'Toast 提示',
            'capability_accessibility': '无障碍服务',
            'capability_notification_listener': '通知访问',
            'capability_system_ui': '系统界面',
            'capability_lsposed': 'LSPosed',
            'capability_other': '高级能力',
        }),
        (en_editor, {
            'editor_favorite_prefix': 'Favorite: %1$s',
            'editor_required_field_format': '%1$s (required)',
        }),
        (zh_editor, {
            'editor_favorite_prefix': '已收藏：%1$s',
            'editor_required_field_format': '%1$s（必填）',
        }),
        (en_flow, {
            'flow_add_action_hint': 'Use the add button to add an action',
            'flow_default_value_format': 'Default: %1$s',
            'flow_config_entry_format': '%1$s: %2$s',
            'flow_value_type_string': 'Text',
            'flow_value_type_number': 'Number',
            'flow_value_type_boolean': 'Boolean',
            'flow_value_type_list': 'List',
            'flow_value_type_object': 'Object',
            'flow_value_type_app': 'App',
            'flow_value_type_package': 'Package',
            'flow_value_type_component': 'Component',
            'flow_value_type_uri': 'URI',
            'flow_value_type_file': 'File',
            'flow_value_type_date_time': 'Date and time',
            'flow_value_type_duration': 'Duration',
            'flow_value_type_color': 'Color',
            'flow_value_type_location': 'Location',
            'flow_value_type_any': 'Any value',
        }),
        (zh_flow, {
            'flow_add_action_hint': '使用添加按钮加入动作',
            'flow_default_value_format': '默认值：%1$s',
            'flow_config_entry_format': '%1$s：%2$s',
            'flow_value_type_string': '文本',
            'flow_value_type_number': '数字',
            'flow_value_type_boolean': '布尔值',
            'flow_value_type_list': '列表',
            'flow_value_type_object': '对象',
            'flow_value_type_app': '应用',
            'flow_value_type_package': '软件包',
            'flow_value_type_component': '组件',
            'flow_value_type_uri': 'URI',
            'flow_value_type_file': '文件',
            'flow_value_type_date_time': '日期和时间',
            'flow_value_type_duration': '时长',
            'flow_value_type_color': '颜色',
            'flow_value_type_location': '位置',
            'flow_value_type_any': '任意值',
        }),
        (en_tree, {
            'tree_add': 'Add',
            'tree_add_branch': 'Add branch',
            'tree_add_parallel_branch': 'Add parallel branch',
            'value_add_item': 'Add item',
            'value_add_parameter': 'Add parameter',
            'predicate_add_condition': 'Add condition',
        }),
        (zh_tree, {
            'tree_add': '添加',
            'tree_add_branch': '添加分支',
            'tree_add_parallel_branch': '添加并行分支',
            'value_add_item': '添加项目',
            'value_add_parameter': '添加参数',
            'predicate_add_condition': '添加条件',
        }),
        (en_auto, {
            'automation_add_event_hint': 'Use the add button to add a trigger',
            'automation_add_state_hint': 'Use the add button to add a persistent state',
            'automation_add_action_hint': 'Use the add button to add an action',
            'automation_add_local_variable': 'Add local variable',
        }),
        (zh_auto, {
            'automation_add_event_hint': '使用添加按钮加入触发器',
            'automation_add_state_hint': '使用添加按钮加入持续状态',
            'automation_add_action_hint': '使用添加按钮加入动作',
            'automation_add_local_variable': '添加局部变量',
        }),
        (en_runtime, {'runtime_qs_tile_default_label': 'YAuto %1$d'}),
        (zh_runtime, {'runtime_qs_tile_default_label': 'YAuto %1$d'}),
    ):
        for name, value in values.items():
            set_string(path, name, value)


def harden_guard() -> None:
    path = '.github/scripts/check-localization.py'
    replace_once(
        path,
        'BANNED_ICON_LITERALS = {"‹", "›", "＋", "⋮", "↑", "↓", "←", "→", "▶", "◀", "✓", "✕", "×"}',
        'BANNED_ICON_CHARS = {"‹", "›", "＋", "⋮", "↑", "↓", "←", "→", "▶", "◀", "✓", "✕", "×", "⌂", "≡", "↳", "⚙", "⌃", "⌄", "★", "☆"}\nHARDCODED_DISPLAY_SEPARATOR = re.compile(r\'(?:joinToString|append)\\(\\s*"\\s*(?:·|\\+|/)\\s*"\\s*\\)|\\+\\s*"\\s*(?:·|\\*|/|\\+)\\s*"\')\nRAW_ENUM_TEXT = re.compile(r\'\\bText\\s*\\([^\\n)]*\\.name\\b\')\nRAW_CAPABILITY_BADGE = re.compile(r\'\\bCapabilityBadge\\s*\\([^\\n]*(?:substringAfterLast|\\.value\\b)\')',
    )
    replace_once(
        path,
        '''            value = "".join(node.itertext()).strip()
            if value in BANNED_ICON_LITERALS:
                failures.append(f"{path}: icon-like character {value!r} must be a vector/image icon, not a string resource")''',
        '''            value = "".join(node.itertext()).strip()
            banned = sorted({ch for ch in value if ch in BANNED_ICON_CHARS})
            if banned:
                failures.append(
                    f"{path}: icon-like character(s) {''.join(banned)!r} must use vector/image icons or normal localized wording"
                )
            if folder.name == "values" and CJK.search(value):
                failures.append(f"{path}: default resource contains CJK translated copy: {name}")''',
    )
    replace_once(
        path,
        '''            if ANDROID_VISIBLE_LITERAL.search(line) or TOAST_LITERAL.search(line):
                failures.append(f"{path}:{line_no}: hardcoded Android-visible text must use localized resources/userText(): {line.strip()}")''',
        '''            if ANDROID_VISIBLE_LITERAL.search(line) or TOAST_LITERAL.search(line):
                failures.append(f"{path}:{line_no}: hardcoded Android-visible text must use localized resources/userText(): {line.strip()}")
            if path.parts[0] in {"app", "ui"} and any(ch in line for ch in BANNED_ICON_CHARS):
                failures.append(f"{path}:{line_no}: character glyph used as UI/icon state; use a vector icon or localized wording: {line.strip()}")
            if path.parts[0] in {"app", "ui"} and HARDCODED_DISPLAY_SEPARATOR.search(line):
                failures.append(f"{path}:{line_no}: hardcoded display separator/required marker; use localized formatting: {line.strip()}")
            if path.parts[0] in {"app", "ui"} and RAW_ENUM_TEXT.search(line):
                failures.append(f"{path}:{line_no}: raw enum .name is visible; map it to a localized label: {line.strip()}")
            if path.parts[0] in {"app", "ui"} and RAW_CAPABILITY_BADGE.search(line):
                failures.append(f"{path}:{line_no}: raw capability ID is visible; map it to a localized label: {line.strip()}")''',
    )
    replace_once(
        path,
        '''    # Resource parity: every default UI string has a zh-CN counterpart and vice versa.
    for module in (Path("ui/design/src/main/res"), Path("platform/accessibility/src/main/res")):
        en = resource_keys(module / "values", failures)
        zh = resource_keys(module / "values-zh-rCN", failures)
        for missing in sorted(en - zh):
            failures.append(f"{module}: missing zh-CN string resource: {missing}")
        for missing in sorted(zh - en):
            failures.append(f"{module}: missing default English string resource: {missing}")''',
        '''    # Resource parity is automatic for every string-bearing Android module and every locale
    # directory. Adding a module or language therefore cannot silently bypass localization checks.
    format_token = re.compile(r"(?<!%)%(?!%)(?:\\d+\\$)?[a-zA-Z]")

    def values_map(folder: Path) -> dict[str, str]:
        out: dict[str, str] = {}
        if not folder.exists():
            return out
        for xml in folder.glob("*.xml"):
            root = ET.parse(xml).getroot()
            for node in root.findall("string"):
                name = node.attrib.get("name")
                if name:
                    out[name] = "".join(node.itertext()).strip()
        return out

    def locale_name(folder: Path) -> str | None:
        qualifier = folder.name.removeprefix("values-")
        if re.fullmatch(r"[a-z]{2,3}(?:-r[A-Z]{2})?", qualifier):
            parts = qualifier.split("-r", 1)
            return parts[0] if len(parts) == 1 else f"{parts[0]}-{parts[1]}"
        if qualifier.startswith("b+"):
            return qualifier[2:].replace("+", "-")
        return None

    discovered_locales: set[str] = set()
    for default in sorted(ROOT.glob("**/src/main/res/values")):
        base = values_map(default)
        if not base:
            continue
        module = default.parent
        zh_dir = module / "values-zh-rCN"
        if not zh_dir.exists():
            failures.append(f"{module}: string-bearing module is missing values-zh-rCN")
        locale_dirs = [p for p in module.glob("values-*") if p.is_dir() and locale_name(p)]
        for locale_dir in sorted(locale_dirs):
            locale = locale_name(locale_dir)
            assert locale is not None
            discovered_locales.add(locale)
            translated = values_map(locale_dir)
            for missing in sorted(base.keys() - translated.keys()):
                failures.append(f"{module}: missing {locale} string resource: {missing}")
            for extra in sorted(translated.keys() - base.keys()):
                failures.append(f"{module}: {locale} resource has no default counterpart: {extra}")
            for name in sorted(base.keys() & translated.keys()):
                if sorted(format_token.findall(base[name])) != sorted(format_token.findall(translated[name])):
                    failures.append(
                        f"{locale_dir}: format placeholders differ for {name}: "
                        f"default={format_token.findall(base[name])}, locale={format_token.findall(translated[name])}"
                    )

    locale_config = Path("app/src/main/res/xml/locales_config.xml")
    if locale_config.exists():
        declared = set(re.findall(r'android:name="([^"]+)"', locale_config.read_text(encoding="utf-8")))
        required = {"en"} | discovered_locales
        for locale in sorted(required - declared):
            failures.append(f"{locale_config}: locale {locale!r} has resources but is not declared")''',
    )
    replace_once(
        path,
        '    print("Localization guard passed: UI/icon/accessibility copy is resource-backed and runtime/import/diagnostic wrapper copy is localized.")',
        '    print("Localization guard passed: visible copy, icon semantics, locale parity, format placeholders, machine-label boundaries and runtime/import/diagnostic messages are localization-safe.")',
    )


def main() -> None:
    create_shared_formatting()
    create_navigation_icons()
    patch_home()
    patch_automation_editor()
    patch_flow_editor()
    patch_feature_picker()
    patch_action_tree()
    patch_macro_style()
    patch_quick_settings()
    patch_resources()
    harden_guard()
    print('Display localization and future-locale guard finalized.')


if __name__ == '__main__':
    main()
