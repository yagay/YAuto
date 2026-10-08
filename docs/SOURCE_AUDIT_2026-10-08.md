# YAuto 源码审计 — 2026-10-08

## 范围与方法

本次以 `feature/mass-data-flow-expansion-20261003` 为工作分支，先枚举 GitHub 递归源码树（当时 718 个受版本控制的文件），然后重点检查应用启动、自动化编辑器、选择器、功能注册、执行引擎、工作区存储、导入器、Android 事件源、Root/LSPosed 入口及 CI 配置。依据可定位的具体代码和自动化回归测试修复问题。

**边界**：这不是声称 718 个文件都完成逐行人工审核，更不能用 JVM 单元测试证明所有厂商设备上的 Hook、权限、耗电或实际界面操作正常。

## 本轮已修复并有回归测试

| 等级 | 模块 | 源码缺陷与后果 | 处理 |
|---|---|---|---|
| 高 | `core/storage/WorkspaceRepository.kt`、`app/YAutoAppScreen.kt` | 备份恢复调用 `merge` 时仅传旧的 `globalVariables`，新式 `persistentVariables` 被遗漏，导致恢复后类型化变量丢失 | 合并函数支持类型化变量，恢复入口同步传入；保留未被导入覆盖的本地变量 |
| 高 | `core/storage/WorkspaceBackup.kt` | `PredicateNode.Xor` 没被 `featureIds()` 扫描，备份的依赖清单漏掉嵌套条件功能 ID | 增加 XOR 子节点递归，并覆盖备份编码/解码测试 |
| 中 | `ui/editor/GenericFeatureConfigEditor.kt` | 切换模式后，不可见或禁用的旧配置字段仍随保存写回，可能造成隐藏选项遗留 | 从当前可见且启用的字段重建已知 schema 配置；保留不认识的历史字段，防止损坏导入数据 |
| 中 | `core/storage/WorkspaceEventSubscriptions.kt` | 总开关关闭或单独关闭触发器后，订阅索引仍把对应事件列为需要监听，导致无效事件源继续工作 | 订阅索引检查全局运行开关、禁用触发器键，并保留 `WaitEvent` 所需事件 |
| 中 | `.github/workflows/android.yml` | 常规 Debug CI 未执行现成的核心运行时、存储、日志、诊断、插件 API、ShortX/Tasker 以及无障碍回归测试 | 扩大 CI 的模块测试覆盖范围，继续保留 Debug APK 构建 |

关键回归测试：

- `core/storage/src/test/.../WorkspaceBackupTest.kt`：XOR 依赖、类型化变量合并
- `core/storage/src/test/.../WorkspaceEventSubscriptionsTest.kt`：暂停、单独禁用触发器、导入后触发器标识、`WaitEvent` 监听
- `ui/editor/src/test/.../FeatureEditorConfigTest.kt`：隐藏/禁用配置清理、历史未知字段保留

## 审计中识别、但尚未直接修改的风险

这些问题仍需按优先级继续验证，避免用推测替代复现证据：

1. **P1 — 备份“恢复”语义**：当前界面保留的是“与现有工作区合并”的流程，并不是完整替换。类型化变量现在会合并，但 `runtimeEnabled`、`disabledCategories`、`disabledTriggerKeys` 等运行管理元数据尚没有明确的导入/覆盖策略。如果用户预期的是整个工作区原样还原，需要设计并明确提供“合并”和“覆盖恢复”两种模式。
2. **P2 — 功能健康检查权限粒度**：`FeatureHealthScanner` 将短信权限的读取、接收、发送视作一个 `SMS` 需求，当前任意一项授权即可满足这个粗粒度需求；个别读写操作的 READY 提示可能过于乐观，应细化需求或额外验证。
3. **P2 — 启动耗时**：`AppGraph` 首次初始化需要安装许多 FeaturePack；是否造成主线程卡顿要以真机启动 Trace/Perfetto 数据评估，不能只看编译是否通过。
4. **P2 — 后台事件采样**：部分时间/CPU 触发器采用约 1 秒轮询，长时间运行、跨午夜窗口和 Doze 下的功耗与触发精度需做设备验证。
5. **P2 — 异常与数据恢复提示**：`JsonWorkspaceRepository` 对完全无法解码的工作区会隔离损坏文件并返回空工作区；应验证用户界面能否清楚提示，而不是让用户误以为数据自然消失。
6. **P2 — 厂商特性**：Android OEM 实体键码、LSPosed/SystemUI Hook 以及 Root/Shizuku 权限存在系统差异，需配合设备日志与真实操作测试。
7. **P2 — 分类对照**：当前规则按源码与现有 MacroDroid 导入映射测试校验；尚不能据此断言与 MacroDroid 5.67.8 APK 中所有触发器、动作、约束的实际分类完全相同。

## 后续验收建议

在 Debug APK 安装后，重点测试：连续编辑/保存和备份恢复、条件字段切换、暂停/重新启用自动化、原先关闭的触发器是否停止监听、ShortX/Tasker 导入后保存/重开，以及多次进入/返回选择器。对异常收集 YAuto 日志和复现步骤，优先修复可实测的问题，不引入大规模盲目重写。

**审计状态**：上表“已修复”表示相应源码与测试已提交，不表示整款应用已通过所有设备级验收；GitHub Actions 构建结果应以对应提交的实际运行记录为准。
