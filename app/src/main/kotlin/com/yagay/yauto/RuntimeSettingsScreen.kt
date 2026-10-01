package com.yagay.yauto

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yagay.yauto.platform.accessibility.YAutoAccessibilityService
import com.yagay.yauto.ui.design.MacroItemRow
import com.yagay.yauto.ui.design.MacroPalette
import kotlinx.coroutines.launch

private enum class RuntimeSettingsPage { OVERVIEW, PERMISSIONS, BACKENDS, ENGINE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeSettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    var backendMessage by remember { mutableStateOf("未检查") }
    var page by remember { mutableStateOf(RuntimeSettingsPage.OVERVIEW) }
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
    val camera = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val dndPolicy = remember(refresh) {
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    }
    val writeSettings = remember(refresh) { Settings.System.canWrite(context) }
    val accessibility = remember(refresh) {
        val component = ComponentName(context, YAutoAccessibilityService::class.java).flattenToString()
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.any { it.equals(component, ignoreCase = true) } == true
    }
    val grantedCount = listOf(accessibility, writeSettings, notificationAccess, notifications, camera, dndPolicy).count { it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (page) {
                            RuntimeSettingsPage.OVERVIEW -> "运行权限与后端"
                            RuntimeSettingsPage.PERMISSIONS -> "Android 权限"
                            RuntimeSettingsPage.BACKENDS -> "特权后端"
                            RuntimeSettingsPage.ENGINE -> "运行引擎"
                        }
                    )
                },
                navigationIcon = {
                    TextButton(onClick = {
                        if (page == RuntimeSettingsPage.OVERVIEW) onBack() else page = RuntimeSettingsPage.OVERVIEW
                    }) { Text("‹") }
                },
            )
        },
    ) { padding ->
        when (page) {
            RuntimeSettingsPage.OVERVIEW -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MacroPalette.State.copy(alpha = .10f))) {
                        Column(Modifier.padding(14.dp)) {
                            Text("能力中心", fontWeight = FontWeight.SemiBold)
                            Text("YAuto 根据每个功能声明的 Capability 自动选择普通 Android、Accessibility、Shizuku、Root 或 LSPosed 后端。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    MacroItemRow(
                        "Android 权限",
                        "$grantedCount / 6 个基础权限就绪",
                        MacroPalette.Constraint,
                        onClick = { page = RuntimeSettingsPage.PERMISSIONS },
                    )
                }
                item {
                    MacroItemRow(
                        "特权后端",
                        "Root · Shizuku · LSPosed / system_server",
                        MacroPalette.Flow,
                        onClick = { page = RuntimeSettingsPage.BACKENDS },
                    )
                }
                item {
                    MacroItemRow(
                        "运行引擎",
                        "Capability Broker · fallback · timeout · tracing",
                        MacroPalette.Action,
                        onClick = { page = RuntimeSettingsPage.ENGINE },
                    )
                }
            }

            RuntimeSettingsPage.PERMISSIONS -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { PermissionCard("UI 自动化 / Accessibility", accessibility, "点击、长按、View ID、输入文字、滚动、手势和屏幕内容条件需要此服务。", "设置 Accessibility") { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
                item { PermissionCard("修改系统设置", writeSettings, "手动亮度等受保护系统设置需要此特殊权限。", "允许修改系统设置") { context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))) } }
                item { PermissionCard("通知监听", notificationAccess, "通知发布、移除和通知内容触发器需要通知监听访问。", "设置通知监听") { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
                item {
                    PermissionCard("运行通知", notifications, "Android 13+ 前台运行服务需要通知权限。", if (notifications) "已允许" else "允许运行通知") {
                        if (!notifications && Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                item {
                    PermissionCard("相机 / 手电筒", camera, "手电筒动作通过 CameraManager 控制闪光灯，需要相机运行时权限。", if (camera) "已允许" else "允许相机权限") {
                        if (!camera) permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
                item {
                    PermissionCard("勿扰模式", dndPolicy, "读取和修改 Android 的打扰过滤器需要通知策略访问。", "设置勿扰权限") {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                }
            }

            RuntimeSettingsPage.BACKENDS -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    BackendCard("Root", "KernelSU / Magisk 等 su 后端。用于系统 shell、包管理和普通 API 无法完成的操作。", backendMessage) {
                        backendMessage = "检查中"
                        scope.launch { backendMessage = if (graph.rootShell.isAvailable()) "Root 可用" else "Root 不可用或未授权" }
                    }
                }
                item {
                    BackendCard(
                        "Shizuku",
                        "通过独立 UserService 执行受保护操作，适合不希望所有操作都走 root 的情况。",
                        if (graph.shizuku.hasPermission()) "已连接并授权" else "未运行或未授权",
                    ) {
                        backendMessage = runCatching {
                            graph.shizuku.requestPermission()
                            "请在 Shizuku 授权后返回此页"
                        }.getOrElse { it.message.orEmpty() }
                        refresh++
                    }
                }
                item {
                    BackendCard("LSPosed / system_server", "系统服务桥接只暴露明确白名单操作，避免单个功能把 Hook 逻辑散落到各模块。", backendMessage) {
                        scope.launch {
                            backendMessage = graph.xposed.status().message.orEmpty()
                            refresh++
                        }
                    }
                }
            }

            RuntimeSettingsPage.ENGINE -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { EngineCard("Capability Broker", "同一个语义功能只定义一次；运行时按 Android → Accessibility / Shizuku / Root / LSPosed 可用性选择实现。") }
                item { EngineCard("失败与回退", "后端不可用、权限缺失或执行失败时记录原因；允许的功能可尝试下一个后端，不需要用户创建多个版本。") }
                item { EngineCard("执行安全", "流程有总运行时间、递归深度、循环次数、命令超时和输出大小限制，取消会继续传递到底层。") }
                item { EngineCard("执行追踪", "每个 Automation / Flow / Node / Feature 都有 trace，上报实际 backend、耗时、结果和异常，便于 AI 读取诊断。") }
            }
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
            Row(Modifier.fillMaxWidth()) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(if (available) "已授权" else "未授权", color = if (available) MacroPalette.Constraint else MacroPalette.Trigger)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(button) }
        }
    }
}

@Composable
private fun BackendCard(title: String, detail: String, status: String, onCheck: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            Text("状态：$status", style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text(if (title == "Shizuku") "授权 / 刷新" else "检查状态") }
        }
    }
}

@Composable
private fun EngineCard(title: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
