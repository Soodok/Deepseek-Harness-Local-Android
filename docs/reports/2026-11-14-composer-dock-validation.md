# Composer Dock、子会话与工具过程验证

工作范围：E:/tmp/wt-flutter-native-chat；分支 feat/flutter-native-chat；起点 68057f08b03ce3b53a0f7ac834de9c2b12913cbf。主工作树未修改。演示截图不代表在线引擎或设备验收。

## 实际行为

- Claude Code协作提供Ink、Ember、Indigo、Paper四套同布局配色概念。实际Flutter采用Ink阅读底色、分色过程摘要和蓝色发送。
- 活跃计划、提问、审批移至输入框上方。计划首次展示2秒，再用260ms高度收起；同计划更新不重启计时，手动切换取消自动收起。问题与审批不自动收起。减少动效时跳过过渡，保留阅读时间。
- 输入区按顶栏、错误横幅和键盘扣除后的剩余高度约束，内容内部滚动；历史计划仍可展开。
- 权限对应read-only、workspace-write、danger-full-access。演示提供三档；原生连接仅启用引擎公布的预设。
- 顶栏子代理入口展示任务、状态、详情；可进入直接父子地址的子会话并被动订阅，continuable可继续发送，one-shot/unknown只读。发送回执仅表示进入收件箱。
- 思考摘要、命令、代码改动默认折叠，完整日志与差异按需查看/复制。状态和退出码独立，不从正文推断成功。

## 真实协议接线

协议依据为自有DSH 0.2.2-rc.2 TypeScript契约。测试使用实际本机HttpServer/WebSocket，不访问桌面DSH凭据或向其发送任务；未引用竞品源代码或素材。

- connect换取内存Cookie，保留挂载前缀并拒绝跨域重定向；unary和单socket mux严格核对真实包络。
- Android显式入口从HostBridge获取引擎地址并选择已有主会话，接入session/follow、assistant stream、todos、子代理catalog。HTTP受理不制造回合完成。
- 普通审批和单个普通问题经owner $events与$events/result接线，Host取消按requestId撤卡；选项与custom分别传输。
- 原生单选的选项与其他答案互斥，多选可附加文字。失败保留输入。同一父会话事件与完整问题payload重放时恢复文字/选择，提交用新代requestId，不自动重发；当前控件内草稿缓存最多8项。
- 多问题、计时claim、question.intent专属决定及歧义标签明确转交Web；真实原生附件未接线并禁用按钮。默认入口仍为fixture演示，真实引擎必须显式选择。完整DSH原生等价、APK、真机与真实在线引擎未验收。

## 最终Flutter验证

环境：Flutter3.50.0、Dart3.14.0。竞争构建由主代理串行执行。

    dart format lib test
    flutter test --concurrency=1 --reporter expanded
    flutter analyze

最终214项测试全部通过（64秒）；analyzer No issues found（29.6秒）；整条命令退出0。日志：assets/2026-11-14-composer-dock/dock-final-validation-v2.log。

## 独立审查与有效回归

两轮只读审查确认7项，主代理均先复现再修复并纳入最终214项：子会话读订阅重试卡住发送锁、旧计划不可达、错误横幅/键盘/大字体溢出、过程卡忽略减少动效、无关toolFinished解除审批锁、重连草稿丢失、单选混合答复违反Host契约。具名checker调用超时无报告，另由只读代理完成独立审查，不将其记作checker成功。

## Android构建记录

- beta.17/code392：file_selector_android脚本compileSdkVersion unresolved，生成flutter accessor为Any。根脚本声明Flutter Gradle plugin apply false后，help退出0（36秒），未修改PubCache。
- beta.18/code393：Kotlin原命令退出1（1分钟），依赖只搜索Flutter仓库。自有settings启用PREFER_SETTINGS，并保留Google、Maven Central、JitPack及Flutter仓库。
- beta.19/code394：已越过仓库解析，2分3秒后在插件Kotlin增量缓存失败；明确错误为C盘PubCache与E盘工作树different roots。
- beta.20/code395：仅本次命令关闭Kotlin增量缓存并在Gradle进程内编译；file_selector_android Kotlin/Java已通过，后续Flutter Java任务等待远程artifact下载。运行11分35秒后有界停止本会话构建，状态为killed，未取得Kotlin整体通过结论。线程证据为SSL读取→DownloadAction→resolveArtifact；未认定编译器死锁。已确认本会话CLI25012/daemon39832退出，其他会话JVM未停止。日志及线程转储已归档。

环境：JDK21、Gradle9.3.1、AGP9.1.1，max-workers=2、堆1024MiB、HTTP连接和读取15秒。不修改全局环境，不以绕过验证或第三方包补丁制造通过。

## 最终实际页面证据

beta.21/code396：在 E:/tmp/native-chat-web-preview 执行 flutter build web --release，退出0（74.3秒）；对应最终214项通过后的Dart快照，期间未再更改Dart源代码。构建日志：[Web release](assets/2026-11-14-composer-dock/dock-web-beta21.log)。

主bundle SHA-256：161ce360e3b4212bb8659b573f4cbd72d4844a7a5887c1c575f241008062d7a7。最终11张PNG均核对同一bundle哈希；360/390/432×960、DPR1、bodyWidth等于声明宽度。全部PNG尺寸及哈希见[清单](assets/2026-11-14-composer-dock/manifest.json)。

- [实际页面画廊](assets/2026-11-14-composer-dock/index.html)：问题/计划展开与收起、三档权限、子代理目录、进入并发送、思考、终端、代码差异和三档宽度。
- [四套配色概念](assets/2026-11-14-composer-dock/dock-palette-options.html)：Claude Code协作产出，明确为概念；Flutter实际采用Ink。
- CDP实际点击子代理入口、进入对话、插入文字并发送；截图包含该用户消息及fixture演示回执。过程详情通过语义节点实际点击展开。示例执行结果不是在线任务执行证据。
- 截图脚本、每张PNG的CDP视口/bundle旁证与验证日志已同目录保存（归档文本只规范换行及行尾空白，原始日志保留在预览工作目录）；画廊、配色和全部11张图片的本地HTTP访问均为200。

完整214项测试与静态检查通过，Web实际页面可审阅；Android编译、APK、设备安装、真实在线引擎和性能帧率未验收。多问题/计时/专属决定及附件仍按上述受支持路径处理。
