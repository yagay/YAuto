package com.yagay.yauto.core.registry

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef

const val FEATURE_BACKEND_CONFIG_KEY = "__backend"

fun FeatureRef.preferredBackendId(): String? =
    (config[FEATURE_BACKEND_CONFIG_KEY] as? ConfigValue.StringValue)?.value
        ?.takeIf { it.isNotBlank() && it != "auto" }

fun FeatureDescriptor.resolvedAccessRequirements(): Set<AccessRequirement> = buildSet {
    addAll(accessRequirements)
    capabilities.forEach { capability ->
        when (capability) {
            CapabilityIds.PRIVILEGED_SHELL -> {
                add(AccessRequirement.ROOT)
                add(AccessRequirement.SHIZUKU)
            }
            CapabilityIds.SYSTEM_UI -> {
                add(AccessRequirement.LSPOSED)
                add(AccessRequirement.ROOT)
                add(AccessRequirement.SHIZUKU)
            }
            CapabilityIds.ACCESSIBILITY -> add(AccessRequirement.ACCESSIBILITY)
            CapabilityIds.NOTIFICATION_LISTENER -> add(AccessRequirement.NOTIFICATION_LISTENER)
            CapabilityIds.LSPOSED -> add(AccessRequirement.LSPOSED)
        }
    }
}

fun FeatureDescriptor.resolvedImplementationOptions(): List<FeatureImplementationOption> {
    if (implementationOptions.isNotEmpty()) return implementationOptions
    if (capabilities.size != 1) return emptyList()
    return when (capabilities.single()) {
        CapabilityIds.PRIVILEGED_SHELL -> listOf(
            FeatureImplementationOption(
                backendId = "shizuku",
                title = "Shizuku",
                summary = "通过 Shizuku UserService 以 shell 权限执行。",
                requirements = setOf(AccessRequirement.SHIZUKU),
                pros = listOf("不需要把 Root 直接授权给 YAuto", "权限范围通常比 Root 更收敛", "关闭 Shizuku 后能力立即失效，易于控制"),
                cons = listOf("依赖 Shizuku 服务持续可用", "部分命令受 shell UID 权限限制", "重启后可能需要重新启动 Shizuku"),
            ),
            FeatureImplementationOption(
                backendId = "root",
                title = "Root",
                summary = "通过 su 以最高系统权限执行。",
                requirements = setOf(AccessRequirement.ROOT),
                pros = listOf("权限范围最完整", "多数 shell / 包管理操作兼容性最好", "不依赖 Shizuku 服务"),
                cons = listOf("授予权限更高，误操作影响更大", "需要 Root 管理器授权", "部分应用会关注设备 Root 环境"),
            ),
        )
        CapabilityIds.SYSTEM_UI -> listOf(
            FeatureImplementationOption(
                backendId = "lsposed",
                title = "LSPosed",
                summary = "通过 system_server / SystemUI Hook bridge 调用系统能力。",
                requirements = setOf(AccessRequirement.LSPOSED),
                pros = listOf("调用路径更直接，通常比 shell 更快", "可访问 shell 无法提供的内部接口", "适合 SystemUI、窗口、任务等深层功能"),
                cons = listOf("依赖 LSPosed 和正确作用域", "Android / ROM 更新后 Hook 适配成本更高", "新增或修改 Hook 时通常需要重启对应进程或设备"),
                restartRequired = true,
            ),
            FeatureImplementationOption(
                backendId = "root",
                title = "Root",
                summary = "通过系统 shell 命令完成相同语义操作。",
                requirements = setOf(AccessRequirement.ROOT),
                pros = listOf("不依赖 Hook 类名和 ROM 内部实现", "Android 小版本升级时通常更稳定", "失败时日志直观"),
                cons = listOf("不是所有 SystemUI 能力都有 shell 命令", "精细控制能力弱于 LSPosed", "需要 Root 授权"),
            ),
            FeatureImplementationOption(
                backendId = "shizuku",
                title = "Shizuku",
                summary = "通过 shell UID 执行 Android 提供的系统命令。",
                requirements = setOf(AccessRequirement.SHIZUKU),
                pros = listOf("无需给 YAuto Root 权限", "不需要 Hook SystemUI/system_server", "适合已有 cmd/service 接口的功能"),
                cons = listOf("权限低于 Root", "没有公开 shell 入口的功能无法实现", "依赖 Shizuku 服务"),
            ),
        )
        CapabilityIds.ACCESSIBILITY -> listOf(
            FeatureImplementationOption(
                backendId = "accessibility",
                title = "Accessibility",
                summary = "通过 Android 无障碍服务执行 UI 交互。",
                requirements = setOf(AccessRequirement.ACCESSIBILITY),
                pros = listOf("不需要 Root", "跨应用 UI 自动化兼容范围广", "Android 官方服务模型"),
                cons = listOf("依赖界面节点可访问性", "部分应用会屏蔽或自绘 UI", "屏幕关闭时能力有限"),
            )
        )
        CapabilityIds.LSPOSED -> listOf(
            FeatureImplementationOption(
                backendId = "lsposed",
                title = "LSPosed",
                summary = "此功能依赖 LSPosed Hook。",
                requirements = setOf(AccessRequirement.LSPOSED),
                pros = listOf("可实现普通 Android API 无法完成的深层系统能力"),
                cons = listOf("ROM/版本变化可能需要适配", "Hook 变化后可能需要重启目标进程"),
                restartRequired = true,
            )
        )
        else -> emptyList()
    }
}

fun FeatureDescriptor.accessSummary(): String {
    val requirements = resolvedAccessRequirements()
    if (requirements.isEmpty()) return "普通权限"
    val alternatives = resolvedImplementationOptions()
    return if (alternatives.size > 1) {
        "可选：" + alternatives.joinToString(" / ") { option -> option.requirements.joinToString("+") { it.label } }
    } else {
        "需要：" + requirements.joinToString(" / ") { it.label }
    }
}
