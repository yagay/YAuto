package com.yagay.yauto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RuntimeSettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    var root by remember { mutableStateOf("未检查") }
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val notificationAccess = remember(refresh) { context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context) }
    val notifications = remember(refresh) { Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    Scaffold(topBar = { TopAppBar(title = { Text("运行权限与后端") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("通知监听：${if (notificationAccess) "已授权" else "未授权"}")
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("设置通知监听") }
            Text("运行通知：${if (notifications) "已允许" else "未允许"}")
            Button(enabled = !notifications && Build.VERSION.SDK_INT >= 33, onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许运行通知") }
            Text("Root：$root")
            Button(onClick = { root = "检查中"; scope.launch { root = if (graph.rootShell.isAvailable()) "可用" else "不可用或未授权" } }) { Text("检查 Root") }
            Text("屏幕、网络、电量和 App 状态使用 Android API。通知触发器需要通知监听权限；系统动作需要相应后端授权。")
        }
    }
}
