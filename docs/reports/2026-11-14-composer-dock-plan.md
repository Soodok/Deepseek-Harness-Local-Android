# DSH Composer Dock 与工具过程实现计划

目标：保留极简对话阅读，将活跃任务与问题移至输入框上方；三档权限与 DSH 名称和 machine value 一致；子代理沿真实父子会话地址打开并可继续发消息；思考、命令、代码按结构化内容展示。

工作范围：E:/tmp/wt-flutter-native-chat，起点 68057f08。主工作树不修改。视觉方案由 Claude Code 协作在独立文档/HTML 范围产出。

- [x] 权限：先测试 read-only / workspace-write / danger-full-access 三值及排队冻结，再移除 ask 伪档位。默认 workspace-write；fixture 不执行真实命令。
- [x] Dock：从最新含 steps 的 ToolRun 选择计划，id/generation 固定显示2秒；260ms easeInOutCubic 收为48dp条；用户手动展开后不再自动收起。问题/审批固定于 Dock，有限高度内部滚动，文本草稿不随消息列表销毁；系统减少动效时跳过动画但保留内容。
- [x] 子代理：仅使用已观察到的 direct-parent address；continuable 可发，one-shot/unknown 只读。打开后加载、错误重试、收件回执、重复提交互斥、对话更新；关闭后注销订阅；错误不清草稿。
- [x] 工具：Typed reasoning / command / code change payload，摘要默认可见，详情懒构建；显示真实 exit code、stderr、before/after/diff，不推断工具结果。超长输出完整查看/复制保留，图片仍按需加载。
- [x] 验证：主代理串行 Flutter tests/analyze、Web release、多视口360/390/432真实点击与动画时点截图；独立审查并复核问题；交接/证据归档/精确提交。APK与真实引擎的结论单独记录。

DSH参考：本地提取的官方 TypeScript 源码 dsh-client-ui-permission-presets/src/client/presentation.ts、dsh-client-ui-subagent/src/client/sidebar-chat/index.tsx、dsh-api-session-controller/src/client/sessions/session.ts。只读取其协议和产品行为，不引用竞品素材。
