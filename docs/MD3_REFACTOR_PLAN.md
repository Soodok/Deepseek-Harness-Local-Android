# MD3 设置页全量重构任务清单（持续任务）

**用户要求：** 所有设置界面（主设置页与各级子页面）全部使用 Google 原生 Material 3 / M3E 组件，动态取色与 MD3 色彩系统完整结合，严格遵循官方文档；颜色设置只保留系统动态取色开关，无需预设或自定义颜色。不要自绘拼装，不再询问。最终自行验证、更新现有 PR #102；不直接合并，不替用户决定发布。

**权威文档：** `docs/md3/README.md` 及该目录中的 Android Developers / Material Components 官方文档。发生冲突时检查官方原文，不凭记忆臆造。

## 进度

- [x] PR #102 当前 build failure 已定位：`Accent.kt` 仍引用已删除的旧 API `ThemeAccent.Mode / mode / setMode / customColor`。
- [ ] 统一主题状态实现，移除残留的预设/自定义颜色系统，让系统动态色开关直接由 `DshApp` 的 `DynamicColors.applyToActivitiesIfAvailable` 驱动。
- [ ] 选定官方支持的 Material Components / AGP / Gradle 兼容版本，解除重复编译失败。
- [ ] 盘点 Manifest 中全部设置 Activity 与布局；逐页核验子页面，不只核验主设置页。
- [ ] 将所有设置子页面落实为官方 Material 组件与主题属性（Toolbar、Cards、Lists/Dividers、Switch、Radio、TextField、Buttons、Dialogs 等）。
- [ ] 清理自绘卡片/按钮/分段背景、硬编码颜色、错误 tint、应用层自制 MD3 颜色方案；保留必要的业务图示 drawable，不把图标资源误判为组件。
- [ ] 验证动态色、系统深浅色、API 26–30 回退、Android 12+ Monet、应用内主题模式、状态栏与 Dialog。
- [ ] 更新探针以检查实际全量设置页面与资源/主题，不以宽泛排除掩盖未迁移页面。
- [ ] 本地可运行的 XML / i18n / probe / lint 验证全过；通过 CI 编译 APK。
- [ ] 按仓库贡献规则检查并创建 PR；向用户报告明确未迁移项与 CI 状态。不得自行合并。

## 重要实现约束

- 用户要求“不要问”，缺失信息自行依据官方文档与仓库规则做合理决定，不发确认问题。
- 不再创建/删除 ThemeAccent API 造成编译断裂；改造时先全仓检索并保持调用契约同步。
- `DynamicColors.applyToActivitiesIfAvailable` 在 Application 注册，Activity 不再 setTheme 覆盖动态色。
- 避免把手写 content-based 色板称作系统 Monet 动态色。
- 不能只完成主设置页就声称“全量 MD3”；必须逐页盘点所有设置入口、Activity、布局和弹窗。
- PR 建立后不得擅自合并；遵循原有约定等待用户审阅。
