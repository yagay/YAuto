# 统一功能：点击直接切换实现方式

仅合并有 MacroDroid、ShortX 或源码证明属于同一语义操作的功能。

- 选择器主列表保留一个功能入口（包括跨原注册分类的实现）
- 进入后直接展示全部实现方式，无需先打开下拉菜单
- 两种方式：紧凑的可点击选项（选中态可见）
- 三种及以上：完整宽度的单选行，一次点击切换
- 点击后立即使用对应 FeatureDescriptor 的参数 schema；原有 Feature ID、权限和执行器保持不变
- 编辑旧任务时优先选中保存的具体 Feature ID；切换其他方式后，最终保存的是新选中的 ID
- 旧配置不会跨不同实现意外传入

已有的普通单选字段（FieldSchema.Choice）、后端 Root/无障碍方式（BackendChoiceEditor）以及开关（FieldSchema.Toggle）本来就是直接点击或滑动操作，保留。

CI 执行 `python3 -m unittest discover -s tools -p 'test_unified_click_selector.py'`，确保不会退回下拉菜单并持续检查功能分组数量。
