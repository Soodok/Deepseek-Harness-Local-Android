# 原生工作区选择与会话折叠实现计划

> **面向 AI 代理的工作者：** 在隔离工作树执行，保留既有 WIP。

**目标：** 让原生会话抽屉按 Host 工作区折叠显示，并在新对话欢迎页把工作区选择放到能力选择旁，未选具体工作区时使用 Host Home。

**架构：** Host 的 workspace/follow 是唯一工作区来源。抽屉维护本地折叠状态，默认展开当前会话所在工作区。EngineHome 维护“下一次新建”的 workspace 选择，默认为 null，session/create 不携带 workspaceId，由 Host 解析 Home。欢迎页只改变下一次创建目标，不移动当前会话。

**技术栈：** Flutter/Dart、现有 EngineWorkspaceCatalog、ChatShell/MessageList、Widget tests、专用 emulator-5570。

## 文件职责

- 修改 flutter_ui/lib/src/ui/session_drawer.dart，增加工作区折叠状态和 Home/具体工作区行。
- 修改 flutter_ui/lib/src/ui/message_list.dart，在现有能力胶囊旁增加工作区选择胶囊，默认显示 Home。
- 修改 flutter_ui/lib/src/ui/chat_shell.dart，转发工作区目录、当前新建目标和选择回调。
- 修改 flutter_ui/lib/src/bridge/engine_sessions_native.dart，维护下一次新建的 workspace 目标，创建请求继续使用 Host 契约。
- 修改 flutter_ui/test/session_drawer_test.dart、flutter_ui/test/welcome_layout_test.dart、flutter_ui/test/engine_home_test.dart，覆盖折叠、Home 默认和具体 workspaceId 请求。
- 更新 AI_CONTEXT.md 与反馈台账，记录 beta.114 证据和未验证项。

## 验收顺序

1. 先写折叠、Home 默认、具体 workspaceId 的红测并确认失败。
2. 实现最小 UI/状态转发并跑定向测试。
3. 跑 EngineHome、session drawer、welcome 相关回归。
4. 更新版本号，串行跑 Flutter 全量、analyze、Gradle。
5. 安装专用 AVD，回读版本和 SHA，实点抽屉折叠、欢迎页工作区选择、新建归组。

**约束：** 不新增 Host 移动会话接口，不伪造 workspaceId，不改主仓库，不操作其他设备。
