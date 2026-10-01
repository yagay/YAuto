package com.yagay.yauto

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yagay.yauto.platform.accessibility.YAutoAccessibilityService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RuntimeSettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    var backendMessage by remember { mutableStateOf("未检查") }
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val notificationAccess = remember(refresh) { context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context) }
    val notifications = remember(refresh) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val writeSettings = remember(refresh) { Settings.System.canWrite(context) }
    val accessibility = remember(refresh) {
        val component = ComponentName(context, YAutoAccessibilityService::class.java).flattenToString()
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.any { it.equals(component, ignoreCase = true) } == true
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("运行权限与后端") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PermissionCard(
                title = "UI 自动化 / Accessibility",
                available = accessibility,
                detail = "点击文本、View ID、输入文字、返回/主页和手势需要此服务。",
                button = "设置 Accessibility",
            ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

            PermissionCard(
                title = "修改系统设置",
                available = writeSettings,
                detail = "手动亮度等受保护系统设置需要此特殊权限。",
                button = "允许修改系统设置",
            ) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            }

            PermissionCard(
                title = "通知监听",
                available = notificationAccess,
                detail = "通知发布/移除触发器需要通知监听访问。",
                button = "设置通知监听",
            ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

            Text("运行通知：${if (notifications) "已允许" else "未允许"}")
            Button(
                enabled = !notifications && Build.VERSION.SDK_INT >= 33,
                onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            ) { Text("允许运行通知") }

            HorizontalDivider()
            Text("Root：$backendMessage")
            Button(onClick = {
                backendMessage = "检查中"
                scope.launch { backendMessage = if (graph.rootShell.isAvailable()) "Root 可用" else "Root 不可用或未授权" }
            }) { Text("检查 Root") }

            Text("Shizuku：${if (graph.shizuku.hasPermission()) "已连接并授权" else "未运行或未授权"}")
            Button(onClick = {
                backendMessage = runCatching {
                    graph.shizuku.requestPermission()
                    "请在 Shizuku 授权后返回此页"
                }.getOrElse { it.message.orEmpty() }
                refresh++
            }) { Text("授权 Shizuku") }

            Button(onClick = {
                scope.launch {
                    backendMessage = graph.xposed.status().message.orEmpty()
                    refresh++
                }
            }) { Text("检查 LSPosed 桥接") }

            HorizontalDivider()
            Text(
                "YAuto 会根据 Feature 的 Capability 自动选择 Android、Accessibility、Root、Shizuku 或 LSPosed 后端。" +
                    " 每次后端尝试、失败原因和耗时都会写入执行日志。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    available: Boolean,
    detail: String,
    button: String,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$title：${if (available) "已授权" else "未授权"}", style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onClick) { Text(button) }
        }
    }
}
