# Flutter 独立 DSH 聊天 UI 设计规格

## 目标

在现有 dsh-android 中加入一个可独立于 WebView 的 Flutter 聊天界面首版。它使用自己的 DSH 视觉系统，借鉴 Claude 手机端的阅读节奏与留白，但不复制 dsh go 的代码、素材、文案或独特页面组合。

## 边界

首版覆盖完整聊天体验：启动连接状态、会话抽屉、历史消息、助手增量文本、工具执行过程、审批卡、提问卡、停止、排队、插话、错误、重连和 WebView 回退入口。设置、权限中心、扩展中心、无障碍和 Linux 工作台继续由 Kotlin 现有页面负责。

真实引擎接入采用可替换 transport：Flutter 通过 Kotlin HostBridge 获取 EngineSupervisor 的一次性 webUrl；Dart 使用 HTTP/WebSocket 客户端访问本机引擎。真实 endpoint 尚未在当前无引擎实例的主机上验证，因此开发与测试同时提供确定性 fixture transport，所有 UI 状态都能离线复现。

## 视觉身份

- 背景使用温和的纸白/墨黑主题，主色使用 DSH 自己的蓝紫强调色；不复用 dsh go 的主题色、图标和布局。
- 消息区采用单列长文阅读，用户消息用轻量容器，助手消息保持开放排版；代码、引用和工具日志使用独立层级。
- 顶部显示当前工作区、引擎连接状态和 Web fallback；底部 composer 固定，发送中支持停止、排队和插话。
- 工具卡默认折叠为一行摘要，展开后显示动作、参数摘要、耗时、结果和错误；审批/提问卡始终清晰显示可操作按钮。
- 动效只服务状态变化：连接、流式输出、卡片展开和发送确认，不加入竞品特有的装饰动画。

## 数据与状态

Dart 层拆成 ChatMessage、ToolRun、ApprovalRequest、QuestionRequest、SessionSummary 与不可变 ChatState。事件归并器按 session id 和 monotonic sequence 应用 snapshot/delta；generation 改变时丢弃旧事件并重新请求 baseline。transport 断开时进入 reconnecting，禁止重复发送，重连成功后恢复可发送状态。

Fixture transport 复现：空会话、历史对话、助手增量、工具运行、审批、提问、停止、queued、错误和重连。真实 HTTP/WebSocket transport 只负责协议搬运，不能把 UI 状态逻辑塞进网络层。

## Android 边界

新增 NativeChatActivity 作为 Flutter 入口，保留现有 MainActivity WebView。Kotlin HostBridge 只暴露 webUrl、连接状态和打开 Web fallback 的 intent；不直接解析 session.v4 文件，不复制 AgentBridge 的手机能力路由。真实引擎不可用时，Flutter 明确显示连接状态和 fixture/demo 入口，避免把假数据误报成真实会话。

## 验收

必须通过 Dart 格式化、静态分析、单元测试、widget 测试、Flutter web 构建和 Android debug 构建；widget 测试覆盖从连接中到完成、工具卡展开、审批确认、提问回答、停止、排队、断线重连和 fallback。至少做一次变异验证：破坏事件 sequence/generation 门禁时测试必须失败，恢复后重新通过。构建结束后清理 Flutter/Gradle 中间目录，只保留源代码、测试、设计文档和可复核日志；不清理主仓或其他 worktree。
