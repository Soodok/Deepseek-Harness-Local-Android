# 自然配色、玻璃材质与共享会话验证

本轮基线为 9ba99ffec2ce002c84e6e7417ede8e3eb07af1f1，工作目录为 E:/tmp/wt-flutter-native-chat，分支 feat/flutter-native-chat。仅隔离树内修改；未修改 E:/Deepeek harness/dsh-android。本轮未继续 Android、APK 或在线引擎排障。

## 最终行为

- 雾灰、暖砂、鼠尾草、暮紫各提供深浅两套；抽屉中的“配色”“外观”分别选择。DshColorTheme 通过 ThemeExtension 传色，切换保留 ChatController、ChatShell 与草稿。选择仅保留于当前运行，默认深色雾灰。
- liquid_glass_widgets 固定 1.17.3。GlassPanel 使用标准质量的 GlassContainer，独立层、blur 8、thickness 12、折射率 1.08、色散 0、饱和度 1.05、无额外重阴影；高对比或减少动效使用实色。材质背景与内容保持固定槽位，切换不重建内部 State。
- 主子会话真正共用 ConversationFlow、ConversationMessage、ComposerViewport、ComposerSurface、ComposerSendButton。执行记录仍由同一个 ListView.builder 按需构建，保留折叠、完整输出、复制与历史计划入口。子会话未生成不受当前 transport 支持的模型、附件、权限和停止空入口；continuable 运行中仍可排队发送，一次性/未知会话以只读说明替换输入区。
- OverflowTitle 按 TextPainter 的真实宽度决定是否滚动；仅溢出时以 28 px/s 往返，两端停留 1500 ms。支持宽度、字号、方向和文本变化；减少动效静止，TickerMode/后台暂停并恢复，完整标题只朗读一次。
- 子会话工具、思考与代码增量进入相同消息流；保留真实 callId、参数、结果、失败、归属与原始未知块。流式 settlement 绑定实际 turn/step 和事件类型，异常重复 start 与空历史游标可见报错；不把错误回执当成完成或静默隐藏正文。父子 ID 必须不同。

## 参考来源与边界

读取本地 DSH 0.2.2-rc.2 的子会话实现，确认其复用 conversation.content/session。模型选择与文件/目录在子会话受限；权限/计划并非 DSH 全局禁止，本客户端只呈现当前 transport 实际实现的能力。

DSH Go 参考固定于 [9f31a4620b1faeba2a5540f1c56be4419f64473d](https://github.com/ccch1mneyyy/dsh-Go/tree/9f31a4620b1faeba2a5540f1c56be4419f64473d)。仅研究 pubspec、LICENSE、glass.dart 和 adaptive_scaffold.dart 四个文件的布局及玻璃取舍。该项目为 AGPL-3.0，使用自绘 GlassSurface；本轮未复制其源码或资产。新增材质来自公开依赖 liquid_glass_widgets。

## 检查与复核

- 最终完整 flutter test --concurrency=1 --reporter expanded：257 项通过（50 秒）。同次命令的 analyze 曾因两条测试 Map 字面量风格提示退出 1；仅修正测试的 null-aware 写法后，7 项 child guard 回归与 flutter analyze 全部退出 0，静态检查 No issues found（1.2 秒）。之后未更改 Dart 源码。
- 八套静态调色板：正文对纸面、surface、raised、userWash 至少 7:1，小字至少 4.5:1；共享禁用发送图标至少 3:1。已消除初次浅色实测 1.54:1 的硬编码图标颜色。
- 真实菜单切换保留控制器/草稿；减少动效/高对比切换保留内部 State、文本、焦点和滚动；15 项滚动标题测试覆盖大小、方向、无障碍、后台、TickerMode 与销毁。
- 两轮独立只读复核分别覆盖主题/玻璃/标题和共享子会话。实际复现并修复玻璃重建、发送图标对比、错误 settlement、代码流缺失、重复 start、self-address 与空历史游标七类问题。
- 未采纳“必须从 seq 0 开始的历史前缀”建议：真实 API 的有界窗口允许后段历史，主投影同样允许；保留后段读取并增加其回归。实际增加的是 records 为空而 cursor 非 -1 的缺口校验。
- 旧断言通过 RichText 和明确外层 SliverList 的 Scrollable 访问新组件；持续滚动的标题改用有界帧等待。未删除历史内容、停止状态、键盘/两倍字号、真实发送与失败草稿等断言。

## 实际页面证据

beta.27/code402 最终 Web release 退出 0（68.2 秒），bundle SHA-256 为 abc4cebff0de820cf284bb112ee0934fc88c4b0e1f44bbdfa431690dfe666bc9。14 张原始 PNG、八套配色对照、CDP 视口与 shader 网络旁证归档于[画廊](assets/2026-11-15-natural-glass/index.html)；身份见[清单](assets/2026-11-15-natural-glass/manifest.json)。13 次独立页面采集均核对同一 bundle、DPR 1、bodyWidth 与声明宽度一致，主画面 432×960，子会话另有 360×960；shader 请求均 HTTP 200，未捕获运行异常。

实际点击进入子会话并发送后，PNG 可见用户消息与独立排队回执；语义快照确认排队状态，SelectableText 的正文未进入该 DOM 文本投影，故不将 DOM 字符串缺失解释为未发送。长标题同页等待 3200 ms 后再次截图，抽屉区域有 2695 个变化幅度大于 8 的像素，配合 15 项组件测试限定滚动结论。画廊、清单和全部 15 张图像的 HTTP 检查均为 200（17 个资源）。

截图为实际 Flutter Web 构建和点击后的页面，使用明确标注的 fixture 演示内容。它不证明 Android/真机 shader 效果、帧率、峰值内存或真实在线引擎任务已验收。依赖中的 shader 在 Windows 编译器有已知静态警告，本轮以成功的 Web 构建和浏览器实际画面限定结论，未修改第三方包。
