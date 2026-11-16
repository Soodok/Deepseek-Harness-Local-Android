# Composer 细化与真实模型验收计划（2026-11-16）

基线 `831bd414`（beta.34 / code409），仅写 `E:/tmp/wt-flutter-native-chat`（分支 `feat/flutter-native-chat`）。
主仓库 `E:/Deepeek harness/dsh-android` 不写；只用专用 `emulator-5570`（AVD dsh_flutter_20261116）。

## 0. 约束

- 本会话只用 DeepSeek/GLM 路线开发（gf/deepseek-v4.1-flash 具名配置）；唯一例外是用户新授权的「gpt-6-luna 真实应用测试」，仅用于模拟器内一次短回复 + 一次只读工具任务 + 一次取消。
- 密钥不得出现在任何输出、回复、截图、文档、git、证据中；仅在内存中 parse，脱敏写出。
- Flutter format/test/analyze 与 Gradle 串行执行（禁止并发构建/测试）。

## 1. 主会话入口 vs 子会话返回（规格 1）

- [x] `ChatShell` 不再把「返回会话列表」当主会话左上角：主会话显示菜单（抽屉）图标并打开 `SessionDrawer`；会话列表与新建会话路径保留在抽屉里（`连接本机引擎` → 改名为「会话列表」，保留 `engine-sessions` key）。
- [x] 移除 `ChatShell.onBack` 的误用（EngineHome 不再传）；真正子会话沿用 `SubagentChatPage._bar` 的返回箭头。
- [x] 子会话不提供模型选择：`SubagentChatPage` 从不渲染 `composer-model`，也不调用 `selectModel`（用测试命中真实入口：子会话读真实继承模型值/只读，不入选择面板）。
- [x] 主/子测试分别命中真实入口：主会话点菜单→抽屉；子会话无 composer-model 且无 selectModel 调用。

## 2. 模型胶囊几何（规格 2）

- [x] 视觉胶囊表面 34dp（区间 32–36），发送按钮 48dp；外层保留 48dp 触控区。
- [x] 文字垂直居中、左右内边距对称（12dp），长模型名 ellipsis 不溢出。
- [x] 小字/大字号测试 + 360/432 实机截图；断言胶囊视觉高度与 48dp 触控区同时成立。

## 3. 悬浮玻璃（规格 3）

- [x] 复核 DSH Go 固定提交 `9f31a46`：`app/pubspec.yaml` 无任何玻璃依赖；`app/lib/ui/theme/glass.dart` 为自绘 `GlassSurface`，`surfaces.dart` 定义 GlassTier/GlassStyle；项目 AGPL-3.0。结论写进报告（精确文件/commit/URL），不复制其源码。
- [x] 用 Flutter 官方公开 API 重写 `GlassPanel`：`BackdropFilter(ImageFilter.blur)` + 裁剪 + 发丝描边 + 顶边高光 + 只在形状外侧的阴影；减少动效/高对比走实色兜底。
- [x] 页面加轻微背景渐变 + 柔光（自身实现），使模糊有内容可透出（现在纯色背景导致模糊不可见）。
- [x] 主子共享同一 `GlassPanel`；State/焦点/草稿保持；真实屏 360/432 对比证据。

## 4. 子智能体图标（规格 4）

- [x] `_TopBar` 依据 `collectSubagents(controller)` 是否为空决定渲染；空目录不占位（不渲染 IconButton）。
- [x] 测试覆盖：初始空隐藏 → emitShowcase/agents 后出现 → 清空/重连后再次隐藏。

## 5. 发送/停止同位互换（规格 5）

- [x] 单个按钮槽位：输出中且草稿为空、无附件 → 停止（`composer-stop`，调用 `controller.stop`）；有草稿/附件 → 发送（走现有 queue/steering：`sendPrompt` 在 streaming 时排队）。
- [x] 清空草稿恢复停止；idle 空草稿发送禁用；idle 有草稿可发送。
- [x] 停止 pending 防双击（`_stopInFlight` + 按钮禁用）；失败保留错误与重试；切换不丢稿不误发。
- [x] 子会话共享同一 `ComposerActionButton`；continuable 保留发送/排队；one-shot/unknown 仍只读；子会话 transport 无 `session/cancel`，不渲染停止（记录为能力边界，不伪造）。
- [x] 保留计划/问题/审批卡与排队托盘。

## 6. 真实模型授权测试（规格 6）

- [x] 只读定位桌面 DSH 的 gpt-6-luna 路由：provider `liang-da-guan-bao`、api `openai-completions`、baseURL（脱敏记录）、credential ref `FFF_API_KEY`（值不输出）。
- [x] 备份设备 `dsh-home/profiles/web/cordis.patch.yml` 与 `.credentials.yaml`，仅写该设备独立 profile；桌面主配置不动。
- [x] 重启引擎/应用 → 模型面板出现该模型 → 一条短真实回复（marker）→ 一条只读工具任务 → 一次停止；读 receipt/journal 确认。
- [x] 失败如实记录（endpoint 不可达/限额/不支持工具），不退化成 fixture/固定回复。

## 7. 交付

- [x] 版本递增到 code410 / 1.6.0-beta.35（记录时间），构建 x86_64 full APK，复制到新路径不覆盖 beta34，校验 ABI/versionCode/size/sha/runtime zip sha。
- [x] `adb install -r`（-s emulator-5570）并实测。
- [x] 报告 `docs/reports/2026-11-16-composer-refinement-validation.md` + `docs/reports/assets/2026-11-16-composer-refinement`（截图/xml/manifest/脱敏日志）。
- [x] `git diff --check`、复核 diff、本地提交（不含 secret/APK/runtime/.superpowers）、AI_CONTEXT 更新。
## 8. 结果

- 门禁：353 项测试通过、analyze 无问题、beta.35(code410) 构建成功并安装到 emulator-5570。
- 规格 1/2/3/4/5 全部落地并有对应测试与设备截图。
- 规格 6：真实 gpt-6-luna 短回复与一次取消在设备上跑通并读到 journal；**只读工具任务失败**——上游 luna 路由不返回 tool_calls（带 48 个真实工具 schema 直连复现，59.4s 返回 0 个 tool_calls 且自述无法访问 bash），属中转站能力限制，已在报告 §9 如实记录。
- 证据：docs/reports/assets/2026-11-16-composer-refinement（24 张 PNG + 24 份同帧 XML + 5 份 journal JSON + 产物 JSON）。
