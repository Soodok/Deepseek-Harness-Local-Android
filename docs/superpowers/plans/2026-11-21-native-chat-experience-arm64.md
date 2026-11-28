# 原生聊天体验与 ARM64 真机验收计划

> 执行人使用 subagent-driven-development 或 executing-plans；每项按测试、实现、复核的顺序推进。此文是计划，不是本轮实施或验收报告。

**目标：** 在保留已认可的原生聊天布局、文件交付和会话行为的前提下，改善常用入口、展开动效和发送/编辑触感。先通过本地与专用模拟器门槛，再构建并在 OnePlus PLZ110 验收同签名 ARM64 包。

**架构：** 文件与选择器的动效只改表现层，发送/编辑反馈只接已受理的 UI 事件。工作树、验证设备和产物各自固定；不更改传输协议、会话持久化或历史投影。

**技术栈：** Flutter/Dart、Android/Kotlin、Gradle 9.3.1、ADB、Android 签名工具。

## 1. 复盘与基线

- [x] 隔离树为 E:/tmp/wt-flutter-native-chat，分支 feat/flutter-native-chat，HEAD 7d9b26dfd42391fbed9c776a21c3f97f97a67b05（beta.78）；beta.88 及其后工作在未提交变更里。执行前固定逐文件 diff 与相关新增文件哈希，不把 HEAD 当成装机版本。不写主仓库，不回退其余工作。
- [x] beta.88 / code463 是已安装的 x86_64 模拟器基线。旧快照 Flutter 全量 470、随后二进制 RPC 专项 11、Kotlin 26、analyze 和构建均有证据；均不替代当前动效改动后的回归。
- [x] beta.88 的真实文件服务通过临时测试入口完成 PNG/Dart 预览；独立接收器以 ACTION_SEND 读到 822 B PNG 和 96 B Dart 文件，SHA-256 与各自原件一致且写入被拒。ACTION_VIEW 系统选项已出现，但尚未取得独立接收的 ACTION_VIEW 哈希/拒写记录。现有历史没有可点的助手文件卡；临时入口不证明 Markdown 投影到真实聊天的装机端到端结果。PDF/ZIP/APK 入盘哈希一致不代表外部交付通过。
- [x] Terra 可用性评审认为文件主链路清楚、视觉层次协调，现有文件卡的动作提示仍可更明确。评审中的「卡片 → 菜单 → 预览」是两个用户动作，菜单显示不另计；外部打开/分享至选择接收器，通常为三次点击，Android 某些 resolver 还要点一次「仅此一次」。旧 raw JSON、重复分叉提示、旧 blur 严格 A/B、转场及 rewind 不纳入本轮。
- [x] Terra 已把逐项入场接到文件、模型、权限三处，代理报告专项 30 项通过；其自查指出宽度、零透明项焦点、模型/权限分时覆盖和负对照未收口。当前可见实现中的 Column 默认居中、动效开启时屏蔽零进度触点/焦点；离屏条目可被标准键盘导航滚入视图并非天然缺陷，须用行为区分正常滚入和焦点跳错。
- [x] general 已给触感接线设计；当前源码未见 HapticFeedback。主、子会话共用输入区视觉控件，但分别发送，不能在共用按钮的点击层统一震动。第三路 explorer 的独立报告未见落盘，未据此采纳任何建议。

证据： [工程交接](<E:/tmp/wt-flutter-native-chat/AI_CONTEXT.md#L3>)、[Terra 首轮评审](<D:/dsh-native-chat-validation/2026-11-20-terra-flow-review.md>)、[Dart 接收结果](<D:/dsh-native-chat-validation/beta88-code-share-result.json>)、[PNG 接收结果](<D:/dsh-native-chat-validation/beta88-image-share-result.json>)、[ACTION_VIEW 选择器](<D:/dsh-native-chat-validation/beta88-image-open-resolver.png>)、[触感设计](<D:/dsh-native-chat-validation/2026-11-20-haptic-plan.md>)、[ARM64 准备报告](<D:/dsh-native-chat-validation/2026-11-20-arm64-readiness.md>)。

## 2. 三路并行，写集分离

### A. Terra：菜单动效与文件入口

**写集：** [staggered_reveal.dart](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/staggered_reveal.dart>)、[composer_sheets.dart](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/composer_sheets.dart>)、[file_delivery_card.dart](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/file_delivery_card.dart>)；测试为 [staggered_reveal_test.dart](<E:/tmp/wt-flutter-native-chat/flutter_ui/test/staggered_reveal_test.dart>)、[file_delivery_card_test.dart](<E:/tmp/wt-flutter-native-chat/flutter_ui/test/file_delivery_card_test.dart>)和独立的新菜单分时测试。

- [ ] 先从实际入口打开三个菜单，断言相邻行在同一中间帧有可见进度差、最终都稳定可点；对照把选项改为同步出现，测试应在进度断言变红，而非靠找不到某类 Widget 报错。分别测试减少动态与无障碍导航：都无逐项等待。
- [ ] 测量动作区宽度与上版一致，覆盖窄屏、紧凑档、深色和 150% 字体。保留原来的触控宽度、文件名与类型层级。检查快速关闭重开、内容重建、更改选中项、滚动、键盘与无障碍焦点；零透明项不能占触点或焦点，已显示项不必等整组动画结束。若标准键盘能稳定滚动到下一项则保留，不人为封死离屏焦点。
- [ ] 保留轻量节奏：现实现为 7dp、单项 170ms、间隔 34ms、组上限 320ms；用测时确认是否够快，不加弹簧、二次遮罩或轮番延迟。保留系统返回和取消，验证无业务回调。
- [ ] 优先优化文件动作标签。只有存在与 [真实文件服务](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/transport/native_file_delivery_service.dart>)一致、能覆盖未知格式的判据时，才收敛菜单项；不得凭后缀宣称可预览。保留点击卡片打开菜单，验证图片/代码、PDF/ZIP/APK及未知类型的行为；若不能在本写集可靠判断，则保留原菜单并记录原因。

### B. general：发送与进入编辑的轻触反馈

**写集：** [composer.dart](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/composer.dart#L92>)、[subagent_panel.dart](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/subagent_panel.dart#L407>)、[chat_shell.dart](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/chat_shell.dart#L220>)，必要时新增轻量 UI 层 helper；测试新增 flutter_ui/test/composer_haptics_test.dart，或在既有主、子聊天 widget 测试中补入口断言。

- [ ] 先从 UI 测试捕获平台通道：主/子会话受理成功各一次；空输入、失败、重复点击和重建为零。主会话入队沿用现有受理判据；按钮与键盘走同一提交入口。编辑要测试直接进入、确认切换、取消确认、同一条重复点、输入字符和取消编辑。
- [ ] 成功发送接布尔受理回执，一次轻触；进入或确认切换编辑且状态实际变化后，一次选择反馈。保留已有草稿、排队和异常语义。不在 build、键入、自动重发或停止上新增反馈，不引入振动权限、插件或独立长震动。平台调用失败不得影响业务动作。
- [ ] 以 Flutter 原生触感接口接 Android 的默认反馈路径；动效开关与系统触感开关分别测。平台通道测试证明发出请求，不证明物理震感；真机另记 Android 是否执行反馈与实际手感是否有人确认。

### C. 独立只读：次要展开点与流程复审

- [ ] 检查执行组、任务列表、子代理面板已有动画和展开行为。文件、模型和权限菜单由 A 路负责；不重复编辑。已有打开/关闭正常的控件不加装饰动效。仅在确认有明显硬切、且不会破坏滚动锚点/焦点时，推荐一个独立候选；不把额外建议拖成首个测试包的门槛。
- [ ] Terra 复看合并后的实际入口与录像，按点击数、拇指可达、错误与返回、视觉层次记录结果；GLM 做独立只读代码审查，重点看动画生命周期、焦点、触感重复与异常路径、文件类型判据。Lead 逐条核实，严重问题修复后重跑受影响门槛。

## 3. 集成与模拟器放行

- [ ] 各路先各自记录写集、测试和未覆盖项，再由 Lead 合并核对。使用串行 Flutter 令牌；先格式检查与专项红/绿，再在 flutter_ui 目录执行 E:/tools/flutter/bin/flutter.bat analyze --no-pub 和 E:/tools/flutter/bin/flutter.bat test --no-pub --concurrency=1。预期 exit 0、No issues found、全量零失败；记录该快照真实用例数，不沿用 470/30。
- [ ] 每次新 APK 独立递增 code/name 并记录时间。先以新版本构建 x86_64 到 D:，在专用 emulator-5570 同签名无清数据覆盖。核对实际版本，再走正常原生入口；用独立本地测试会话或 fixture 验证真实助手 Markdown 链接到文件卡。必须区分临时路由和产品入口，不捏造或写入用户会话，不发新模型请求。
- [ ] 录制文件/模型/权限列表逐项入场，验证大字体、减少动态、快速关闭及返回；测 PNG/代码预览，复测 SEND 和 VIEW 并以独立 UID 的 hash + 拒写为准。对 PDF/ZIP/APK 只验证实际可用的文件交接；不安装样本 APK。无法进入产品入口或遇 ANR，记录实际停点和日志，不拿 fixture 或截图充当通过。
- [ ] 清理前核对仍运行的自有 attach job bash-06ca4b7a1a0c43c3、tcp:50103 转发和临时路由；仅移除本轮确认归属的样本、接收器及对应快照/缓存，保留现有数据和证据。不要停其他模拟器或触碰其他会话。删除目录前验证路径和预期成员。

## 4. ARM64 真机放行

- [ ] 通过前一阶段后再递增一次构建版本。复核 [构建配置](<E:/tmp/wt-flutter-native-chat/app/build.gradle.kts>)和 [D 盘输出脚本](<D:/dsh-native-chat-validation/arm64-output.init.gradle>)；使用 JDK 21、Flutter 3.50.0、SDK 36.1.0、Gradle 9.3.1，低内存参数和独立的 D: 输出/项目缓存，避免并发构建。用 arm64-v8a/full、:app:assembleRelease、-PrequireReleaseSigning=true，指定 E:/tmp/dsh-arm64-runtime-20261119/runtime-arm64-v8a-dsh-0.2.2-rc.2-20261119.zip；其 SHA-256 为 61bc738970a435dea61e8e5f445281a4ffad16cfe3012cba2682aefa4367a2c1。签名材料只供本地签名进程，不把口令写进命令日志、报告或工作树。
- [ ] 重新通过 192.168.101.21:9000 核对 PLZ110、设备序列、arm64-v8a、当前前台及已装包版本/证书。旧记录是 1.4.76/code327、release 证书 d28ca4f5b7467ea19564983f492af25fd21b26b2577fbfa32c46ed59042892c8。验证新包 package、版本、单一 ABI、内嵌 runtime hash、证书和非 debuggable；以安装当时实测为准，证书或设备身份不符则停止。
- [ ] 现有已 pull 的旧 APK 只备份应用二进制，不能恢复私有数据，也不保证能降级。核实升级路径对现有数据/runtime 的影响；如发现可能破坏数据的迁移且未建立可恢复备份，不尝试覆盖。设备不在使用中时，只对已确认目标执行 adb -s 192.168.101.21:9000 install -r；不使用 -d，不卸载、不清数据、不更改系统设置或默认应用。
- [ ] 安装后用包信息与正常入口核对版本、引擎就绪和原生页。确认已有会话入口/草稿不被测试覆盖，再在独立测试范围复测文件与动画；发送触感用离线或免费且已授权的夹具，不用真实凭据探测。如需人为确认震感，明确「已装/已见系统执行/实际震感未确认」三层。
- [ ] 记录源码快照、版本/ABI/证书/APK 哈希、测试与录像、设备上的实际结果及未验证项；更新 [AI_CONTEXT](<E:/tmp/wt-flutter-native-chat/AI_CONTEXT.md>)。不推送、不发布；真机失败保留证据和数据。
