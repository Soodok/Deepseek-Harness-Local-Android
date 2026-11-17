# 模型标签、错误呈现与显示大小实现计划

目标：按用户本轮要求调整模型胶囊左对齐与缩写、DSH式独立错误卡、更紧凑的设置页及个性化整体显示大小。基线59f59401，分支feat/flutter-native-chat，仅修改E:/tmp/wt-flutter-native-chat。用户已持续授权实现与模拟器验收，不重复设计确认。

方案：

- 胶囊保持34dp表面和48dp触控区，改为内容宽度靠左排列，移除外层Center造成的横向居中。缩写仅作用于胶囊显示：DeepSeek大小写不敏感替换为ds，保留版本/后缀和完整语义标签/tooltip；模型目录、配置、RPC的id和provider原样保留。
- 按DSH 0.2.2-rc.2的turn-error节点规则，以结构化失败元数据驱动独立错误卡（警告图标、错误色淡底/描边、可读原因、代码/详细信息与复制）；不把错误当普通模型或系统消息，不通过正文前缀猜类型。用户主动停止不显示红色错误，真实未知错误保留原因/代码。原始journal、surfaceOp替换和重连/子会话语义不削弱；避免实时和历史重复报错。
- 设置页默认收紧标题、行高、图标及留白；保留可触达面积和大字号换行，不能隐藏已有功能。
- 个性化提供紧凑90%、标准100%、宽松110%，默认标准（其设置页基础排版已经更紧凑）。这是应用内整体显示大小，影响文字及控件几何；不修改Android全局density。保留系统TextScaler与键盘/安全区正确坐标，覆盖Navigator弹层与子会话。换档立即预览，不重建聊天控制器、不丢草稿；沿用native_chat_ui SharedPreferences持久化，旧数据默认标准，未知值回退，保存失败给原因，迟到初始读取不覆盖新选择。

执行：

1. 读取composer_surface.dart/composer.dart，chat_model.dart/dsh_session_projection.dart/conversation_flow相关组件，main.dart/dsh_theme.dart，settings_page.dart/settings_detail_pages.dart，HostBridge及NativeChatActivity.kt；根据职责增加小文件，避免无关重构。DSH参考为E:/tmp/rc2_asar/dsh/node_modules/@deepseek-ai/dsh-client-ui-chat/src/client/conversation-nodes/turn-error.ts及dsh-client-ui-conversation的实际renderer。
2. 为模型显示与wire不变、结构化终端错误/取消/重连/子会话、大字号设置、整体缩放几何与IME/弹层、存储初始读取竞态写有效红测，再实现与转绿；纯低风险排版以几何检查及截图验证。
3. 串行运行针对性测试、完整flutter test --concurrency=1 --reporter expanded、flutter analyze，日志保存到E:/tmp/native-chat-web-preview/android-beta36-*.log，不并发Flutter/Gradle。
4. GLM独立复核后逐条核验与修复；构建前递增到beta36/code411，使用既有x86_64 full runtime，不触及运行时/供应商缓存。
5. 主会话负责构建安装到本任务专用emulator-5570，复用已保存会话错误进行真实历史渲染，不新增付费模型请求；真实操作个性化、三个大小、模型选择和重启恢复，检查键盘、弹层与错误详情。保存PNG/XML、产物hash/version/ABI、日志与验收边界后本地提交。

约束：不用GPT委派，DeepSeek实现、GLM复核；不读写桌面凭据，不追加在线Luna调用；主仓库E:/Deepeek harness/dsh-android及其他模拟器/真机保持不动；不复制DSH Go源码或资产。旧有在线工具/审批/子会话缺口继续明确保留。
