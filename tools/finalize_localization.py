#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path('.')
CJK = re.compile(r'[\u3400-\u4dbf\u4e00-\u9fff]')


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, text: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')


def replace(path: str, old: str, new: str, *, required: bool = True) -> None:
    text = read(path)
    if old not in text:
        if required:
            raise RuntimeError(f'missing replacement anchor in {path}: {old[:100]!r}')
        return
    write(path, text.replace(old, new))


def ensure_import(path: str, import_line: str) -> None:
    text = read(path)
    if import_line in text or 'import com.yagay.yauto.core.model.*' in text:
        return
    lines = text.splitlines()
    import_indexes = [i for i, line in enumerate(lines) if line.startswith('import ')]
    if not import_indexes:
        raise RuntimeError(f'no import section in {path}')
    lines.insert(import_indexes[-1] + 1, import_line)
    write(path, '\n'.join(lines) + ('\n' if text.endswith('\n') else ''))


def set_string(path: str, name: str, value: str) -> None:
    text = read(path)
    pattern = re.compile(rf'(<string name="{re.escape(name)}">)(.*?)(</string>)')
    escaped = value.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    if pattern.search(text):
        text = pattern.sub(lambda m: m.group(1) + escaped + m.group(3), text, count=1)
    else:
        block = f'    <string name="{name}">{escaped}</string>\n'
        text = text.replace('</resources>', block + '</resources>')
    write(path, text)


def add_catalog_entries() -> None:
    path = 'core/model/src/main/kotlin/com/yagay/yauto/core/model/UserText.kt'
    text = read(path)
    en_anchor = '        "compat.action_not_mapped" to "Imported action is not mapped yet: %s",\n'
    zh_anchor = '        "compat.action_not_mapped" to "导入的动作尚未映射：%s",\n'
    en = '''        "engine.execution_start" to "Execution started: %s · %s",\n        "engine.phase_enter" to "Enter",\n        "engine.phase_event" to "Event",\n        "engine.phase_exit" to "Exit",\n        "engine.node_start" to "Node started",\n        "engine.node_end" to "Node completed",\n        "engine.action_start" to "Start action: %s",\n        "engine.flow_call" to "Call flow: %s",\n        "engine.condition_result" to "Condition %s: %s",\n        "value.true" to "True",\n        "value.false" to "False",\n        "capability.selected_backend_unavailable" to "Selected backend '%s' is unavailable or does not support %s",\n        "capability.no_backend_available" to "No backend is available for %s",\n        "capability.backend_error" to "Backend error: %s",\n        "capability.all_backends_failed" to "All backends failed for %s",\n        "feature.operation_failed" to "Operation failed: %s",\n        "feature.variable_not_list" to "Variable is not a list",\n        "feature.maximum_below_minimum" to "Maximum is below minimum",\n        "feature.variable_name_empty" to "Variable name is empty",\n        "feature.destination_variable_empty" to "Destination variable is empty",\n        "feature.list_index_out_of_bounds" to "List index is out of bounds",\n        "feature.key_empty" to "Key is empty",\n        "feature.variable_not_object" to "Variable is not an object",\n        "feature.no_compatible_app" to "No compatible application was found",\n        "feature.modify_settings_denied" to "Modify system settings access is not granted",\n        "feature.notification_permission_denied" to "Notification permission is not granted",\n        "feature.invalid_package_name" to "Invalid package name",\n        "feature.sensor_unavailable" to "Sensor is not available",\n        "feature.sensor_timeout" to "Sensor read timed out",\n        "feature.location_unavailable" to "Location permission is missing or no last known location is available",\n        "feature.token_too_short" to "Token must be at least 16 characters",\n        "feature.no_launch_intent" to "No launch intent is available for %s",\n        "feature.uri_empty" to "URI is empty",\n        "feature.http_url_required" to "URL must use http:// or https://",\n        "feature.result_variable_empty" to "Result variable is empty",\n        "feature.clipboard_unavailable" to "Clipboard is unavailable: %s",\n        "feature.camera_permission_denied" to "Camera permission is not granted",\n        "feature.notification_policy_denied" to "Notification policy access is not granted",\n        "feature.notification_listener_disconnected" to "Notification listener is not connected",\n        "feature.shortcut_id_label_required" to "Shortcut ID and label are required",\n        "accessibility.unsupported_operation" to "Unsupported accessibility operation: %s",\n        "accessibility.operation_failed" to "Accessibility operation did not complete successfully",\n'''
    zh = '''        "engine.execution_start" to "开始执行：%s · %s",\n        "engine.phase_enter" to "进入",\n        "engine.phase_event" to "事件",\n        "engine.phase_exit" to "退出",\n        "engine.node_start" to "节点开始执行",\n        "engine.node_end" to "节点执行完成",\n        "engine.action_start" to "开始动作：%s",\n        "engine.flow_call" to "调用流程：%s",\n        "engine.condition_result" to "条件 %s：%s",\n        "value.true" to "是",\n        "value.false" to "否",\n        "capability.selected_backend_unavailable" to "所选后端“%s”不可用或不支持 %s",\n        "capability.no_backend_available" to "没有可用于 %s 的后端",\n        "capability.backend_error" to "后端错误：%s",\n        "capability.all_backends_failed" to "%s 的所有后端均执行失败",\n        "feature.operation_failed" to "操作失败：%s",\n        "feature.variable_not_list" to "变量不是列表",\n        "feature.maximum_below_minimum" to "最大值小于最小值",\n        "feature.variable_name_empty" to "变量名为空",\n        "feature.destination_variable_empty" to "目标变量名为空",\n        "feature.list_index_out_of_bounds" to "列表索引超出范围",\n        "feature.key_empty" to "键为空",\n        "feature.variable_not_object" to "变量不是对象",\n        "feature.no_compatible_app" to "没有找到兼容的应用",\n        "feature.modify_settings_denied" to "尚未授予修改系统设置权限",\n        "feature.notification_permission_denied" to "尚未授予通知权限",\n        "feature.invalid_package_name" to "应用包名无效",\n        "feature.sensor_unavailable" to "传感器不可用",\n        "feature.sensor_timeout" to "读取传感器超时",\n        "feature.location_unavailable" to "缺少位置权限或没有可用的最后位置",\n        "feature.token_too_short" to "令牌长度至少需要 16 个字符",\n        "feature.no_launch_intent" to "应用 %s 没有可用的启动入口",\n        "feature.uri_empty" to "URI 为空",\n        "feature.http_url_required" to "网址必须使用 http:// 或 https://",\n        "feature.result_variable_empty" to "结果变量名为空",\n        "feature.clipboard_unavailable" to "剪贴板不可用：%s",\n        "feature.camera_permission_denied" to "尚未授予相机权限",\n        "feature.notification_policy_denied" to "尚未授予勿扰模式访问权限",\n        "feature.notification_listener_disconnected" to "通知监听服务未连接",\n        "feature.shortcut_id_label_required" to "快捷方式 ID 和名称为必填项",\n        "accessibility.unsupported_operation" to "不支持的无障碍操作：%s",\n        "accessibility.operation_failed" to "无障碍操作未成功完成",\n'''
    if '"engine.execution_start"' not in text:
        text = text.replace(en_anchor, en_anchor + en, 1)
        text = text.replace(zh_anchor, zh_anchor + zh, 1)
    write(path, text)


def add_icons_and_strings() -> None:
    drawables = {
        'ic_back.xml': '''<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#FF000000" android:pathData="M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.42,-1.41L7.83,13H20z"/></vector>\n''',
        'ic_add.xml': '''<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#FF000000" android:pathData="M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6z"/></vector>\n''',
        'ic_more.xml': '''<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#FF000000" android:pathData="M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2M12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2M12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2"/></vector>\n''',
        'ic_arrow_up.xml': '''<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#FF000000" android:pathData="M4,12l1.41,1.41L11,7.83V20h2V7.83l5.59,5.58L20,12l-8,-8z"/></vector>\n''',
        'ic_arrow_down.xml': '''<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#FF000000" android:pathData="M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.59,-5.58L4,12l8,8z"/></vector>\n''',
        'ic_chevron_right.xml': '''<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#FF000000" android:pathData="M9.29,6.71a1,1 0,0 0,0 1.42L13.17,12l-3.88,3.88a1,1 0,1 0,1.42 1.41l4.59,-4.58a1,1 0,0 0,0 -1.42l-4.59,-4.58a1,1 0,0 0,-1.42 0z"/></vector>\n''',
        'ic_info.xml': '''<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#FF000000" android:pathData="M11,17h2v-6h-2v6M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8M11,9h2V7h-2v2"/></vector>\n''',
    }
    for name, body in drawables.items():
        write(f'ui/design/src/main/res/drawable/{name}', body)

    en_path = 'ui/design/src/main/res/values/strings.xml'
    zh_path = 'ui/design/src/main/res/values-zh-rCN/strings.xml'
    en = {
        'icon_back': 'Back', 'icon_add': 'Add', 'icon_more_options': 'More options',
        'icon_move_up': 'Move up', 'icon_move_down': 'Move down',
        'icon_open_details': 'Open details', 'icon_information': 'Information',
        'home_add_automation_button': 'Add automation',
        'home_add_flow_button': 'Add flow / action block',
        'home_tile_add_automation_subtitle': 'Trigger, action and constraint',
        'feature_picker_favorite_hint': 'Use More options to add or remove a favorite',
    }
    zh = {
        'icon_back': '返回', 'icon_add': '添加', 'icon_more_options': '更多选项',
        'icon_move_up': '上移', 'icon_move_down': '下移',
        'icon_open_details': '查看详情', 'icon_information': '信息',
        'home_add_automation_button': '添加自动化',
        'home_add_flow_button': '添加流程 / 动作块',
        'home_tile_add_automation_subtitle': '触发器、动作和约束',
        'feature_picker_favorite_hint': '使用“更多选项”添加或取消收藏',
    }
    for name, value in en.items(): set_string(en_path, name, value)
    for name, value in zh.items(): set_string(zh_path, name, value)


def icon_call(drawable: str, desc: str, tint: str | None = None) -> str:
    tail = f', tint = {tint}' if tint else ''
    return (
        'androidx.compose.material3.Icon('
        f'painter = androidx.compose.ui.res.painterResource(com.yagay.yauto.ui.design.R.drawable.{drawable}), '
        f'contentDescription = androidx.compose.ui.res.stringResource(com.yagay.yauto.ui.design.R.string.{desc}){tail})'
    )


def replace_symbol_icons() -> None:
    back = icon_call('ic_back', 'icon_back')
    add = icon_call('ic_add', 'icon_add')
    more = icon_call('ic_more', 'icon_more_options')
    up = icon_call('ic_arrow_up', 'icon_move_up')
    down = icon_call('ic_arrow_down', 'icon_move_down')
    chevron = icon_call('ic_chevron_right', 'icon_open_details')
    info = icon_call('ic_info', 'icon_information')

    simple = {
        'app/src/main/kotlin/com/yagay/yauto/RuntimeSettingsScreen.kt': [('Text("‹")', back)],
        'ui/diagnostics/src/main/kotlin/com/yagay/yauto/ui/diagnostics/DiagnosticsScreen.kt': [('Text("‹")', back)],
        'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/ActionTreeEditor.kt': [('Text("↑")', up), ('Text("↓")', down)],
        'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/MacroAutomationEditorScreen.kt': [('Text("‹")', back)],
        'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/MacroFlowEditorScreen.kt': [('Text("‹")', back)],
        'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/MacroGlobalVariablesScreen.kt': [('Text("‹")', back), ('Text("＋")', add)],
        'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/MacroFeaturePicker.kt': [('Text("›")', chevron)],
        'ui/home/src/main/kotlin/com/yagay/yauto/ui/home/MacroHomeScreen.kt': [('Text("＋")', add), ('Text("i", style = MaterialTheme.typography.headlineMedium)', info), ('Text("›")', chevron)],
    }
    for path, reps in simple.items():
        text = read(path)
        for old, new in reps:
            text = text.replace(old, new)
        write(path, text)

    path = 'ui/design/src/main/kotlin/com/yagay/yauto/ui/design/MacroStyle.kt'
    text = read(path)
    text = text.replace('Text("＋", color = Color.White, style = MaterialTheme.typography.headlineSmall)', icon_call('ic_add', 'icon_add', 'Color.White'))
    text = text.replace('TextButton(onClick = onMenu, modifier = Modifier.align(Alignment.CenterVertically)) { Text("⋮") }', f'IconButton(onClick = onMenu, modifier = Modifier.align(Alignment.CenterVertically)) {{ {more} }}')
    text = text.replace('Text("＋", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.headlineMedium)', icon_call('ic_add', 'icon_add', 'Color.White.copy(alpha = .9f)'))
    write(path, text)

    # Feature picker uses Close text at root and a back icon on nested pages.
    path = 'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/MacroFeaturePicker.kt'
    text = read(path)
    old = '''                            Text(\n                                if (page == PickerPage.Categories) {\n                                    stringResource(TextR.string.common_close)\n                                } else {\n                                    "‹"\n                                },\n                                color = Color.White,\n                            )'''
    new = f'''                            if (page == PickerPage.Categories) {{\n                                Text(stringResource(TextR.string.common_close), color = Color.White)\n                            }} else {{\n                                {icon_call('ic_back', 'icon_back', 'Color.White')}\n                            }}'''
    if old not in text:
        raise RuntimeError('feature picker back block not found')
    write(path, text.replace(old, new))


def strip_cjk_keywords() -> None:
    pattern = re.compile(r'keywords\s*=\s*setOf\((.*?)\)', re.S)
    for path in ROOT.glob('**/src/main/kotlin/**/*.kt'):
        text = path.read_text(encoding='utf-8')
        def repl(match: re.Match[str]) -> str:
            inner = match.group(1)
            quoted = re.findall(r'"(?:\\.|[^"\\])*"', inner)
            if not quoted or not any(CJK.search(q) for q in quoted):
                return match.group(0)
            kept = [q for q in quoted if not CJK.search(q)]
            return 'keywords = setOf(' + ', '.join(kept) + ')'
        updated = pattern.sub(repl, text)
        if updated != text:
            path.write_text(updated, encoding='utf-8')


def localize_runtime_messages() -> None:
    add_catalog_entries()

    # Capability broker owns wrapper prose; backend/raw error details remain technical arguments.
    path = 'core/capability/src/main/kotlin/com/yagay/yauto/core/capability/Capability.kt'
    ensure_import(path, 'import com.yagay.yauto.core.model.userText')
    text = read(path)
    text = text.replace('''            val message = request.preferredBackendId?.let {\n                "Selected backend '$it' is unavailable or does not support ${request.capability.value}"\n            } ?: "No backend available for ${request.capability.value}"''', '''            val message = request.preferredBackendId?.let {\n                userText("capability.selected_backend_unavailable", "Selected backend '%s' is unavailable or does not support %s", it, request.capability.value)\n            } ?: userText("capability.no_backend_available", "No backend is available for %s", request.capability.value)''')
    text = text.replace('CapabilityResult(false, message = error.message ?: error::class.simpleName)', 'CapabilityResult(false, message = userText("capability.backend_error", "Backend error: %s", error.message ?: error::class.simpleName.orEmpty()))')
    text = text.replace('message = "All backends failed for ${request.operationId}"', 'message = userText("capability.all_backends_failed", "All backends failed for %s", request.operationId)')
    write(path, text)

    path = 'core/engine/src/main/kotlin/com/yagay/yauto/core/engine/AutomationEngine.kt'
    text = read(path)
    text = text.replace('''        trace(executionId, TraceKind.EXECUTION_START, "${automation.name}:$phase", automation)''', '''        val phaseLabel = when (phase) {\n            AutomationPhase.ENTER -> userText("engine.phase_enter", "Enter")\n            AutomationPhase.EVENT -> userText("engine.phase_event", "Event")\n            AutomationPhase.EXIT -> userText("engine.phase_exit", "Exit")\n        }\n        trace(executionId, TraceKind.EXECUTION_START, userText("engine.execution_start", "Execution started: %s · %s", automation.name, phaseLabel), automation)''')
    text = text.replace('trace(executionId, TraceKind.EXECUTION_END, "Execution failed: ${t.message}", automation, success = false)', 'trace(executionId, TraceKind.EXECUTION_END, userText("engine.execution_failed", "Execution failed: %s", t.message ?: t::class.simpleName.orEmpty()), automation, success = false)')
    text = text.replace('EngineResult(false, executionId, variables = variables.snapshot(), error = t.message ?: t::class.simpleName)', 'EngineResult(false, executionId, variables = variables.snapshot(), error = userText("engine.execution_failed", "Execution failed: %s", t.message ?: t::class.simpleName.orEmpty()))')
    text = text.replace('trace(executionId, TraceKind.NODE_START, node::class.simpleName ?: "node", automation, flow, node.id)', 'trace(executionId, TraceKind.NODE_START, userText("engine.node_start", "Node started"), automation, flow, node.id)')
    text = text.replace('trace(executionId, TraceKind.NODE_END, node::class.simpleName ?: "node", automation, flow, node.id, success = signal !is Signal.Failure, durationMs = System.currentTimeMillis() - start)', 'trace(executionId, TraceKind.NODE_END, userText("engine.node_end", "Node completed"), automation, flow, node.id, success = signal !is Signal.Failure, durationMs = System.currentTimeMillis() - start)')
    text = text.replace('trace(executionId, TraceKind.ACTION, "Start ${node.feature.typeId}", automation, flow, node.id, node.feature.typeId)', 'trace(executionId, TraceKind.ACTION, userText("engine.action_start", "Start action: %s", node.feature.typeId), automation, flow, node.id, node.feature.typeId)')
    text = text.replace('Signal.Failure(error.message ?: error.javaClass.simpleName)', 'Signal.Failure(userText("feature.operation_failed", "Operation failed: %s", error.message ?: error.javaClass.simpleName))')
    text = text.replace('trace(executionId, TraceKind.FLOW, "Call ${target.name}", automation, target, node.id)', 'trace(executionId, TraceKind.FLOW, userText("engine.flow_call", "Call flow: %s", target.name), automation, target, node.id)')
    text = text.replace('trace(executionId, TraceKind.CONDITION, "${predicate.feature.typeId} = $result", nodeId = nodeId, featureId = predicate.feature.typeId, success = result)', 'trace(executionId, TraceKind.CONDITION, userText("engine.condition_result", "Condition %s: %s", predicate.feature.typeId, if (result) userText("value.true", "True") else userText("value.false", "False")), nodeId = nodeId, featureId = predicate.feature.typeId, success = result)')
    write(path, text)

    literal_map = {
        'Variable is not a list': ('feature.variable_not_list', '变量不是列表'),
        'Maximum is below minimum': ('feature.maximum_below_minimum', '最大值小于最小值'),
        'Shell command is empty': ('capability.shell_empty', 'Shell 命令为空'),
        'Variable name is empty': ('feature.variable_name_empty', '变量名为空'),
        'Destination variable is empty': ('feature.destination_variable_empty', '目标变量名为空'),
        'List index out of bounds': ('feature.list_index_out_of_bounds', '列表索引超出范围'),
        'Key is empty': ('feature.key_empty', '键为空'),
        'Variable is not an object': ('feature.variable_not_object', '变量不是对象'),
        'YAuto Accessibility Service is not connected': ('diagnostics.accessibility.not_connected', 'YAuto 无障碍服务未连接'),
        'Accessibility operation did not complete successfully': ('accessibility.operation_failed', '无障碍操作未成功完成'),
        'No compatible application found': ('feature.no_compatible_app', '没有找到兼容的应用'),
        'Modify system settings access is not granted': ('feature.modify_settings_denied', '尚未授予修改系统设置权限'),
        'Notification permission is not granted': ('feature.notification_permission_denied', '尚未授予通知权限'),
        'Invalid package name': ('feature.invalid_package_name', '应用包名无效'),
        'Sensor is not available': ('feature.sensor_unavailable', '传感器不可用'),
        'Sensor read timed out': ('feature.sensor_timeout', '读取传感器超时'),
        'Location permission missing or no last known location': ('feature.location_unavailable', '缺少位置权限或没有可用的最后位置'),
        'Token must be at least 16 characters': ('feature.token_too_short', '令牌长度至少需要 16 个字符'),
        'URI is empty': ('feature.uri_empty', 'URI 为空'),
        'URL must use http:// or https://': ('feature.http_url_required', '网址必须使用 http:// 或 https://'),
        'Result variable is empty': ('feature.result_variable_empty', '结果变量名为空'),
        'Camera permission is not granted': ('feature.camera_permission_denied', '尚未授予相机权限'),
        'Notification policy access is not granted': ('feature.notification_policy_denied', '尚未授予勿扰模式访问权限'),
        'Notification listener is not connected': ('feature.notification_listener_disconnected', '通知监听服务未连接'),
        'Shortcut ID and label are required': ('feature.shortcut_id_label_required', '快捷方式 ID 和名称为必填项'),
    }
    roots = [ROOT / 'feature', ROOT / 'platform']
    for root in roots:
        for file in root.glob('**/src/main/kotlin/**/*.kt'):
            text = file.read_text(encoding='utf-8')
            original = text
            for literal, (code, _) in literal_map.items():
                text = text.replace(f'message = "{literal}"', f'message = userText("{code}", "{literal}")')
            text = text.replace('message = "Unsupported accessibility operation: ${request.operationId}"', 'message = userText("accessibility.unsupported_operation", "Unsupported accessibility operation: %s", request.operationId)')
            text = text.replace('message = "No launch intent for $pkg"', 'message = userText("feature.no_launch_intent", "No launch intent is available for %s", pkg)')
            text = text.replace('message = "Clipboard is unavailable: ${it.message ?: it.javaClass.simpleName}"', 'message = userText("feature.clipboard_unavailable", "Clipboard is unavailable: %s", it.message ?: it.javaClass.simpleName)')
            text = text.replace('message = it.message ?: it.javaClass.simpleName', 'message = userText("feature.operation_failed", "Operation failed: %s", it.message ?: it.javaClass.simpleName)')
            text = text.replace('message = it.message)', 'message = userText("feature.operation_failed", "Operation failed: %s", it.message ?: it.javaClass.simpleName))')
            if text != original:
                file.write_text(text, encoding='utf-8')
                rel = str(file)
                ensure_import(rel, 'import com.yagay.yauto.core.model.userText')


def main() -> None:
    add_icons_and_strings()
    replace_symbol_icons()
    strip_cjk_keywords()
    localize_runtime_messages()
    print('Final localization migration applied.')


if __name__ == '__main__':
    main()
