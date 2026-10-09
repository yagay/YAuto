# YAuto 选择器合并规则：MacroDroid 优先，ShortX 补充

## 发现的问题

原来的 `UnifiedFeatureSpecs.kt` 中有 83 个概念组、391 个成员引用。
许多组仅仅按大类把不相干功能塞进一个选择框，例如：

- 音量设置 / 音量加减 / 铃声模式
- 麦克风静音 / 免提通话
- 文件读取 / 文件写入
- 应用启停 / 清理后台进程 / 应用待机策略
- 电池温度 / 电量 / 充电 / 电池健康
- 通知回复 / 通知清空 / 通知查询
- Wi-Fi 连接信息 / 扫描 / 连接 / 断开
- 硬件按键 / 组合键 / 按键手势

**这些是同一分类或相近名称，不代表同一个 MacroDroid/ShortX 菜单入口。**

## 新规则

1. 已有 MacroDroid 的功能：必须找到 **同一个已审核的 MacroDroid 字符串资源键** 才能合并，不能按相同分类、前缀、近义词、UI 标题自动合并。
2. MacroDroid 不覆盖的功能：改用 **同一个 ShortX 功能资源键**，并审核其语义。
3. **只有相同类型与相同 YAuto 分类内** 才在选择器折叠。现有 `UnifiedFeatureGroups.kt` 按 kind/category 分区，保留。
4. 任何没有对应核实来源的功能 **默认独立展示**，不再隐藏到不相关的联合设置中。
5. 这些是显示层变更。所有原有 Feature ID、配置字段、存储规则、执行器、导入器、权限和 Root/LSPosed 操作均保留；编辑旧规则时仍以其原始 ID 打开。

## 已核实的同一操作多种实现

| 选择器合并项 | 来源 | 同源资源键 | 具体功能 |
| --- | --- | --- | --- |
| 写入剪贴板 | MacroDroid | `action_clipboard` | `android.clipboard.set` / `android.clipboard.write` |
| 读取剪贴板 | ShortX | `ui.action.read.clipboard` | `android.clipboard.get` / `android.clipboard.read` |
| 截取屏幕 | MacroDroid | `action_take_screenshot` | `android.screen.screenshot` / `android.screenshot.capture` |

不再合并其余 80 个未经逐项证实的分类组。若以后找到 APK 内真实“单功能→多个设置选项”的证据，
可将对应两个或多个 ID 加入 `tools/verified_picker_merges.csv` 和
`UNIFIED_FEATURE_SPECS`，CI 会双向校验是否与 APK 术语映射一致。

## 质量检查

```sh
python3 -m unittest discover -s tools -p 'test_verified_picker_merges.py'
python3 tools/audit_verified_picker_merges.py --fail-on-unsafe
```

报告写入 `build/reports/verified_picker_merges.json`，CI 自动验证。

这套规则刻意采用保守默认策略：**缺少来源证据时不合并**。因此操作列表比以前多，但每个选项含义明确，
不会将 MacroDroid 本应分开的独立动作藏进同一个大杂烩菜单。
