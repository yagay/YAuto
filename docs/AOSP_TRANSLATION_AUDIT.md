# YAuto × AOSP 中英文术语审计

## 命名规则

本审计只处理可见文案与术语报告，不修改稳定功能 ID、Trigger/Action/Constraint 功能类型、MacroDroid / ShortX / Tasker 导入映射或运行时执行逻辑。

- 功能标题：保留 YAuto 已核实的 MacroDroid 名称优先、ShortX 次之、YAuto 本地资源兜底。
- Android 设置、权限、无障碍、蓝牙、通知、快捷设置等系统名词：同名资源 ID 下，参考 AOSP 英文与简体中文。
- Root、LSPosed、Shizuku 及协议和机器字段：不把源码术语直接翻译或替换。
- 同一个英文词有多个含义时：只报告，不批量覆盖；中英文 XML 中的字段、值、功能说明仍需按上下文人工确认。

## 官方资源

tools/aosp_verified_terms.csv 包含经源文件逐项核对的 English / zh-CN 对照（source, source_key, en, zh_cn）。来源为 Apache-2.0 的 AOSP：

- [Settings (Android 开源设置)](https://github.com/aosp-mirror/platform_packages_apps_settings)：res/values/strings.xml 对应 res/values-zh-rCN/strings.xml。
- [SystemUI (Android 系统界面)](https://github.com/aosp-mirror/platform_frameworks_base)：packages/SystemUI/res/values/tiles_states_strings.xml 对应 packages/SystemUI/res/values-zh-rCN/tiles_states_strings.xml。

注意：原生 Android 设置把部分 Wi-Fi 条目标为 WLAN；YAuto 不会因此自动覆盖当前 Wi-Fi、MacroDroid 和 ShortX 的术语。实际厂商本地化也可能与 AOSP 不同。

## 全量扫描与审核报告

    python3 -m unittest discover -s tools -p 'test_aosp_terms.py'
    python3 tools/audit_aosp_terms.py --output build/reports/aosp_terminology_audit.json

输出 JSON 会列出：
- 简体中文资源完全复制英文的可疑项（技术缩写/品牌名除外）
- YAuto 当前中文与 AOSP 官方配对的差异
- 被保护的 MacroDroid、ShortX 与功能标题条目
- 被扫描的英文、简体中文资源总量

报告不会自动将候选差异写回 XML，因此不会凭英文相同修改不同功能含义。CI 运行测试和审计，并上传 JSON 以便集中审核。

若需要复核 AOSP 源文件是否发生变化，可自行下载两套英文和中文 XML，使用：

    python3 tools/audit_aosp_terms.py \
      --aosp-settings-en /tmp/aosp/settings-en.xml \
      --aosp-settings-zh /tmp/aosp/settings-zh.xml \
      --aosp-systemui-en /tmp/aosp/systemui-en.xml \
      --aosp-systemui-zh /tmp/aosp/systemui-zh.xml

如原资源 ID 下英文或中文有变化，脚本将返回错误。此复核是本地显式输入，CI 不需要联网读取 AOSP。

## 安全边界及本次修正

本次把快捷设置磁贴的 active/inactive 对应中文改成 SystemUI 通常使用的“已开启/已关闭”；unavailable 保持“不可用”。YAuto 的功能名称/说明/字段/选项在没有资源时，不再直接输出 Kotlin 描述符中的英文，改为现有的通用本地化文本（搜索索引仍允许英文搜索）。

检查脚本继续核对 XML 的键、格式化参数与用户可见文本。新增功能应先在两个语言目录补齐文本，再核对 AOSP 的官方语义，最后评估是否和 MacroDroid 功能名称冲突。不能把审计报告中每一条差异都视为翻译错误。
