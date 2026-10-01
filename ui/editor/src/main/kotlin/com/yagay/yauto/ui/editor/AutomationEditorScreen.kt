package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yagay.yauto.ui.design.YSectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationEditorScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("新建自动化") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            YSectionCard("⚡ 触发 / 状态", "Event + State；状态天然支持进入/退出") { TextButton(onClick = {}) { Text("＋ 添加触发器或状态") } }
            YSectionCard("▶ 进入 / 事件动作", "内部为结构化 Action Tree，不使用 End If 哨兵") { TextButton(onClick = {}) { Text("＋ 添加动作") } }
            YSectionCard("◀ 退出动作", "State 离开时执行") { TextButton(onClick = {}) { Text("＋ 添加退出动作") } }
            YSectionCard("✓ 条件", "支持 ALL / ANY / NONE / 表达式") { TextButton(onClick = {}) { Text("＋ 添加条件") } }
            YSectionCard("{} 局部变量") { TextButton(onClick = {}) { Text("＋ 添加变量") } }
        }
    }
}
