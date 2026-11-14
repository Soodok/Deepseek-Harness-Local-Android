# 顶栏与执行过程修正验证

工作范围：E:/tmp/wt-flutter-native-chat，feat/flutter-native-chat；基线3f605591846e089a331b2eb300d8e876cd6de885。仅修正Flutter UI与对应测试，没有扩展协议、依赖或Android排障。

## 实际变更

- 运行模式按自身内容宽度贴悬浮栏最右侧，子代理紧邻左侧；子代理图标由account_tree_outlined改为forum_outlined。原600宽场景右侧留白106.3px，修正后按钮右侧5至14px，箭头右侧12至28px；360/390/432/600均通过几何断言。
- 同一消息所属工具默认汇总为一行可展开摘要；展开后为细竖线下的紧凑思考、命令、文件改动等记录，取消重复的大标题和完成时间行。
- 展开状态按消息身份保留，数据更新不强制收起；失败/取消在摘要可见，完整命令输出、差异、复制和历史计划入口保留。
- 过程记录继续作为主ListView.builder的独立行按需构建，收起时详情不进入树；箭头180ms过渡遵循系统减少动效设置。

## 检查与回归

初始有效红测：600宽右边距实际106.3px；默认过程摘要入口缺失。修复后定向9项通过。既有生命周期、停止状态和历史计划测试改为通过真实摘要展开路径验证，保留原有内容断言。

最终执行：

    flutter test --concurrency=1 --reporter expanded
    flutter analyze

217项测试全部通过（84秒）；analyze No issues found（65秒），job bash-d4980edca74e4380退出0。最终Web构建之后没有再次修改Dart代码。日志：[完整检查](assets/2026-11-14-execution-disclosure/execution-final-check-v2.log)。

## 实际页面

beta.22/code397；flutter build web --release退出0（103.3秒）。主bundle SHA256：bc03b760f4e496753de6ceec904d62f91f9bd65d9ceb4a4b8419b300b72d3187。

[实际页面画廊](assets/2026-11-14-execution-disclosure/index.html)包括收起态、展开态、命令详情和360/390/600宽主界面；432宽过程状态亦已验证。6张PNG均绑定同一bundle，DPR1，bodyWidth与声明视口相同；[清单](assets/2026-11-14-execution-disclosure/manifest.json)含尺寸、哈希和采集参数。

实际CDP点击摘要展开、命令详情；画廊与全部6张PNG本地HTTP返回200。截图内容为fixture演示，不是在线任务执行证据。Android编译、APK与真实在线引擎的既有未验收边界见上一轮[报告](2026-11-14-composer-dock-validation.md)。
