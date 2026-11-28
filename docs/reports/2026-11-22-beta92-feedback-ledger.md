# beta92 真机反馈与验收台账

## beta.140 三项反馈设备验收（2026-11-27 20:56 +08:00）

- 环境：专用 AVD `dsh_flutter_20261116` / `emulator-5570`（`emu avd name` 已核对）。已装包 versionCode 515 / 1.6.0-beta.140 / x86_64，APK 118,916,397 B，SHA-256 `40f1cb1e86d7920939ab8c715079632e226c75aeaa6752b67556700e04628caf`。定向 Flutter 回归 98 项通过（engine_home、welcome_layout、tool_process_card、conversation_tool_stack、native_workspace_directories、native_workspace_directory_ui）。证据目录 `D:/dsh-native-chat-validation/beta140-verify/`。
- 私有工作区（通过）：`beta140_private` 组位于 `/data/user/0/app.dsh.mobile/files/native-workspaces/beta140_private`（workspaceId `c8b0929d-0a5b-401e-a740-74f6c4982e75`）。点组头 `+`（781,670）新建成功，registry 会话数 1→2→3；新会话 `session-2beee5c5-4ab1-49aa-8880-20db575897cf` 的 `session.v4.jsonl.zstd` 首行 cwd = 该私有目录，agentPreset=standard。
- 私有目录真实工具回合（通过）：Host 的 `cordis.patch.yml` 把 llm-pi-ai 指向 `qa-native-round-4ec9cefc6d`、baseURL `http://127.0.0.1:56224/v1`，该端口默认无人监听，真实请求 5 次重试全部 `Connection error. (TRANSPORT)`。为主机起最小 OpenAI 兼容桩（`beta140-verify/stub.py`，SSE chunked）并 `adb reverse tcp:56224 tcp:56224` 后，应用内真实工具回合输出 `PROBE-RESULT: pwd=/data/user/0/app.dsh.mobile/files/native-workspaces/beta140_private`，写入并读回 `dsh-cwd-probe.txt`（18 B）。探针文件已删除。截图 `14-probe-evidence.png`、`22-toolstack.png`。
- 共享工作区，缺权限时的拒绝路径（通过）：无「所有文件访问」时（appops `MANAGE_EXTERNAL_STORAGE=default`）点「选择手机共享目录」能正常拉起系统 ACTION_OPEN_DOCUMENT_TREE，选 Documents → ALLOW 后应用给出可读提示 `工作区目录不可用：PlatformException(workspace/unreachable, 引擎没有共享存储的直接文件访问…)`，未注册 Host 工作区、未持久化 URI 授权、原目标保留，未静默切 Home（`26-snack-2.png`）。
- 共享工作区，正面路径（通过）：`appops set app.dsh.mobile MANAGE_EXTERNAL_STORAGE allow` 授予全文件访问后重走同一流程，Host 注册 `Documents /storage/emulated/0/Documents`（createdAt 2026-11-27T12:33:04.047Z），`shared_prefs/native_workspace_directories.xml` 持久化 `content://com.android.externalstorage.documents/tree/primary%3ADocuments`，欢迎页胶囊显示「工作区：Documents」，菜单出现 Documents 项。点组头 `+` 新建后 registry 记录 Documents 会话 1 条，会话 projection 的 `identity.cwd = /storage/emulated/0/Documents`。
- 共享目录真实工具回合（通过）：同一桩回合在共享会话内执行 `pwd` 得 `/storage/emulated/0/Documents`，写出 `dsh-cwd-probe.txt`（18 B，`u0_a188 media_rw`），主机侧 `adb shell cat /sdcard/Documents/dsh-cwd-probe.txt` 读回 `beta140-cwd-probe`；探针文件已删除。截图 `41-docs-probe.png`。共享目录正面路径由此由「未验证」转为通过，仍限本 AVD 与本次系统授权。
- 欢迎页（通过）：四卡 2×2、16sp、普通屏 132dp、圆角 18、GlassPanel；点「编写代码」只把预置文案填进草稿（「帮我编写或修改代码。先确认目标和已有代码，再提出最小可验证的改动。」），registry 会话数不变，工作区保持原目标（`21-card-tap.png`）。
- 工具栈（通过）：折叠标题「已调用工具，已运行命令」h=132 px，展开后命令行 h=138 px，相邻行边界 642→774→912 约 42 dp 节距；代码字号 12sp 落实（`tool_process_card.dart:112/382/397/480`、`execution_group.dart:76`、`tool_run_card.dart:92/137`），48 dp 触控区与完整输出/反馈按钮保留，普通对话正文未缩（`23-tool-expanded.png`、`tool.xml`）。
- 环境事故与恢复（必须记录）：12:38:20 有其它会话向同一 AVD 覆盖安装了 **arm64** 的 `1.6.0-beta.36 / code412`（`primaryCpuAbi=arm64-v8a`，`ActivityManager: stop app.dsh.mobile due to installPackageLI`），进程被杀、画面回到 launcher。用户数据未丢（会话与 registry 完好），随后用本树 `app/build/outputs/apk/debug/app-debug.apk`（code515/beta.140/x86_64，SHA 同上）`install -r -t` 恢复，回读 `primaryCpuAbi=x86_64`、`firstInstallTime` 仍为 2026-11-22 01:09:10；恢复后两工作区会话与 Documents 授权均保持。结论：该 AVD 与共享 emulator-5554 存在并发占用风险，后续装机前应先回读 `versionCode/versionName/primaryCpuAbi`。
- 未验证：`emulator-5554`、PLZ110 与真机均未涉及；共享目录只验 Documents 一个目录；未验证撤销授权后已登记工作区的降级行为；未验证共享目录下大文件与并发写入。

## beta.120 冷入口淡入计时修复（2026-11-26）

- beta.119 已就绪正常入口样本：录屏中 Web/原生参与混合的首个明显编码帧 PTS 为 82.403278 秒，原生占比达到约 99% 的帧为 84.380389 秒，间隔 1.977 秒；用上一张纯 Web 帧作为外边界则为 2.170 秒。该结果是编码帧量测，不把视频 PTS 当 Flutter tick，也不证明未编码间隔。原实现对所有帧间隔限步 83.334 ms。 [原始录屏](<D:/dsh-native-chat-validation/beta119-device-recovery.nKTdZW/transition/display.mp4>) · [帧时间](<D:/dsh-native-chat-validation/beta119-device-recovery.nKTdZW/transition/entry-frames.log#L108>)。
- beta.120：连续前台帧按实耗时间推进约 1 秒；间隔至少 500 ms 或离开前台后的首帧仍限一步，丢弃停顿期间的超额时间，保留三重握手、减弱动效和失败超时。新增两条红测分别得到 0.916666（预期 0.86、0.8），修后淡入专项 14 项通过；全量 Flutter 737 通过、1 跳过，analyze 无问题。 [红测](<D:/dsh-native-chat-validation/beta120-fade.dSUZbt/flutter-red.log#L9>) · [专项](<D:/dsh-native-chat-validation/beta120-fade.dSUZbt/flutter-green.log>) · [全量](<D:/dsh-native-chat-validation/beta120-fade.dSUZbt/flutter-full.log#L822>) · [分析](<D:/dsh-native-chat-validation/beta120-fade.dSUZbt/flutter-analyze.log#L2>)。
- x86_64 Full Debug `1.6.0-beta.120 / 495`，252,767,541 字节，SHA-256 `c486c8d6fac315f2eaf3492d862a700caa1dd30dcba51963a8f2dfecaad34e1e`；v2 验签与 beta.119 同签名。已有 Android XML 198 项零失败（本轮相同输入的 Gradle 单测任务为 UP-TO-DATE）；离线打包成功。逐项核对 151 个非 Flutter 资源没有变化，运行时清单仅外层构建时间不同。淡入时长与桌宠入口仍需以 beta.120 实装补证。 [APK](<D:/dsh-native-chat-validation/beta120-fade.dSUZbt/dsh-native-chat-beta120-x86_64.apk>) · [签包收据](<D:/dsh-native-chat-validation/beta120-fade.dSUZbt/artifact.json>) · [构建日志](<D:/dsh-native-chat-validation/beta120-fade.dSUZbt/gradle.log#L178>)。
- Ask：beta.119 在 Web 实际发送一次、测试模型返回一次工具调用。未取得 Host 同轮问答终态、桥接待答或实际桌面悬浮 UI；六域设置恢复另有回读，不能替代 `turn/end`。既有真实 Host 日志已反证夹具对 `user/message` 来源计数及 `turn/start` 顺序的假设。下一步固定原 QA SID 和 callId，只读归约 Host 记录，不重发、不调用取消与消费接口。 [发送收据](<D:/dsh-native-chat-validation/beta119-ask-live-workspace.zFteme/ask-device-receipt.json#L59>) · [真实历史顺序对照](<D:/dsh-native-chat-validation/beta115-ask-phone-route.tjIm4JwH/native-ask-72dbbb392d-device.json#L439>)。
- 2026-11-27 夹具契约已修正并通过离线自测：`adapter.host_evidence` 只把 `source.kind=user` 计为真人、其余 `user/message` 计为上下文（新增 `contextCount`）；`hooks.cancel_own` 改为只要一条真人 prompt、真人必须在 `turn/start` 之后，取消仅按 `session/cancel` 与真实 `turn/end` 判定，不把空闲读成完成并保留目标前驱注入日志。`adapter.py --self-test` 20 项、`hooks.py --self-test` 22 项均退出 0。该修正只覆盖离线契约，真实同轮 Ask 仍未重跑。 [夹具修正](<D:/dsh-native-chat-validation/beta119-ask-live-workspace.zFteme/adapter.py#L514>) · [取消自证](<D:/dsh-native-chat-validation/beta119-ask-live-workspace.zFteme/hooks.py#L522>)


## beta.120 专用 AVD 实装验收结果（2026-11-26）

- 包层与保数据覆盖通过。专用 dsh_flutter_20261116 / emulator-5570 从正常 launcher 启动，PackageManager 回读 1.6.0-beta.120 / code495 / x86_64、APK SHA-256 c486c8d6fac315f2eaf3492d862a700caa1dd30dcba51963a8f2dfecaad34e1e、user 0 数据 inode 139581。实测使用一次 install -r -t 覆盖，未改变 ADB 映射。 [设备收据](<D:/dsh-native-chat-validation/beta120-device-simple.eDyfMy/receipt.json>) · [签包收据](<D:/dsh-native-chat-validation/beta120-fade.dSUZbt/artifact.json>)
- 冷入口录屏作业已建立并安全回收，screenrecord 退出 0，视频 SHA-256 为 5f4bb521aeac033f13535f001681aff60dd6406101a693ddb5ab72d33a598e67，应用 ANR 事件为 0，ADB 映射回读一致，临时文件清理和模拟器停机均通过。 [录屏](<D:/dsh-native-chat-validation/beta120-device-simple.eDyfMy/transition/display.mp4>) · [录制收据](<D:/dsh-native-chat-validation/beta120-device-simple.eDyfMy/transition/recording.json>)
- Web 工具栏可达性未通过。夹具读取 26 次界面树并有 7 次读取失败，末次 WebView 数量、工具栏数量和已知标签均为 0。焦点最终回到 app.dsh.mobile/app.dsh.mobile.MainActivity，但未观察到可点击工具栏，因此正常入口点击次数为 0，原生抽屉和桌宠页均未验证。 [入口事件](<D:/dsh-native-chat-validation/beta120-device-simple.eDyfMy/entry-events.log>) · [过滤启动日志](<D:/dsh-native-chat-validation/beta120-device-simple.eDyfMy/startup-filtered.json>)
- 录屏首帧仍是白底鲸鱼启动画面，末帧显示 QA Native Tools 页面和“引擎可用，但部分能力缺失”弹窗，缺失 directoryPickerController、workspaceRegistry、workspaceController 三项能力。该环境状态使 beta.120 的淡入时长、正常 Web → 原生入口和桌宠原生设置保持待验证。 [首帧](<D:/dsh-native-chat-validation/beta120-device-simple.eDyfMy/transition/startup-frame.png>) · [末帧](<D:/dsh-native-chat-validation/beta120-device-simple.eDyfMy/transition/final-frame.png>)
- 临时验收夹具已删除旧 Ask 发送分支并通过离线 AST 检查，产品代码和隔离工作树生产文件未因本轮设备夹具修改。Ask 仍按既有边界等待同 Host SID/callId 的脱敏只读回读。
- Ask 历史归约器的 29 项离线自测退出 0，未知 CLI 参数退出 2。输入为同一 QA SID 的 Host 事件、状态和原试验的精确标识。结果区分目标回合结束、结束事件缺失及结束失败。真实 Host 回读仍待补证，跨端卡片、答案回执和撤卡均保留待验证。 [归约器](<D:/dsh-native-chat-validation/beta120-fade.dSUZbt/ask-history-reducer.py#L99>)。

## beta.120 正常入口与桌宠复验（2026-11-26）

- 专用模拟器两次冷启动到达已就绪的 Web 工具栏。实点后宿主为 Healthy，Flutter 撤下遮罩并打开原生抽屉，两个观测窗内应用 ANR 均为 0。复验后数据 inode 139581，ADB 映射一致，模拟器已停。严格未就绪时的入口待验。 [首次收据](<D:/dsh-native-chat-validation/beta120-startup-diagnostic.FpBbD2/receipt.json>) · [复验收据](<D:/dsh-native-chat-validation/beta120-device-resumed.x1xlqp/receipt.json>)。
- 首轮健康样本的末个纯 Web 帧、首个混合帧和原生画面占比首次连续四帧达到 99% 的起点，PTS 分别为 20.293556、20.431111、21.665033 秒。后两点相隔 1.233922 秒，从末个纯 Web 帧起算为 1.371477 秒。区间最大编码帧间隔为 0.251445 秒。量测使用录屏帧和静态像素亮度。 [原始录屏](<D:/dsh-native-chat-validation/beta120-startup-diagnostic.FpBbD2/transition/display.mp4>) · [帧时间索引](<D:/dsh-native-chat-validation/beta120-startup-diagnostic.FpBbD2/transition/frame-pts.csv>)。
- 从原生抽屉的账号菜单进入设置，在「显示与个性化」实点「悬浮桌宠」，打开 Android 桌宠设置。未授权时点击开启会弹出权限说明，操作前后桌宠服务均关闭。系统返回后截图显示 Flutter 设置及桌宠行。夹具的返回节点检查为 false，作业退出 1。实际悬浮显示、桥插件联动和资源包开启门禁待验。 [权限说明](<D:/dsh-native-chat-validation/beta120-device-resumed.x1xlqp/native-pet-permission-gate.png>) · [返回设置](<D:/dsh-native-chat-validation/beta120-device-resumed.x1xlqp/native-settings-return.png>)。
- （已被下方修正后的复核取代）早期版本归约器因来源白名单过窄返回 input-shape-invalid；仅保留两次事件页一致、67 条连续、hasMore=false 的事实。

- 同一 beta.120 实装包的 Ask 历史只读复核完成。修正归约器后，固定 QA 会话在索引 0–66 的 67 条连续事件中定位到 turn 2：start=52、唯一精确真人 prompt=56、唯一 ask_user_question call=62、durable end=66，但 end.reason.kind 不是 completed，因此返回 target-end-failed。读取前后事件页一致、hasMore=false、会话 idle，说明目标回合已结束但失败。原生卡片、答题回传与撤卡仍未验证，不能据此判 Ask 通过。[只读复核](<D:/dsh-native-chat-validation/beta120-ask-final3.jxhCOV/receipt.json>)。
- 归约器按生产语义修正来源判定：仅 source.kind=user 是真人消息，其余（含未列出的上下文注入）都属于上下文节点。已补 user-approval 及 compact-checkpoint/plugin/model 用例，当前 31 项离线自测退出 0。[归约器](<D:/dsh-native-chat-validation/beta120-fade.dSUZbt/ask-history-reducer.py#L149>)。
- 首轮健康样本录屏的编码帧量测已存档，量测文件记录末个纯 Web 帧 20.293556 秒、首个混合帧 20.431111 秒、首次连续四帧达到 99% 在 21.665033 秒，区间最大帧间隔 0.251445 秒。它是 216×468 分析副本上的稳定像素混合估计，不是 Flutter tick。[量测文件](<D:/dsh-native-chat-validation/beta120-startup-diagnostic.FpBbD2/transition/fade-measurement.json>)。
## beta.119 原生桌宠入口与 beta.118 续验（2026-11-26）

- beta.119 专用 AVD 包扫描恢复。关机备份逐文件校验通过，共 36 个文件，7,327,674,593 字节。首次重启保留 code494 和 user 0 数据 inode 139581，活动 APK 路径缺失。确认当前启动的包扫描告警后，以同签名 APK 执行一次 `install -r -t`，回读 code494、x86_64、APK SHA-256 和原 inode。随后独立重启再次回读同一活动 APK。 [备份清单](<E:/tmp/dsh-flutter-avd-beta119-backup.VJ1cYs/backup-manifest.json>) · [恢复收据](<D:/dsh-native-chat-validation/beta119-device-recovery.nKTdZW/receipt.json>) · [重启回读](<D:/dsh-native-chat-validation/beta119-pet-ask-device.7PYTUv/receipt.json>)

- beta.119 冷进程从正常 Web 工具栏进入原生。首次真实点击接到 Healthy、可加载的入口决定，启动取证窗口内应用 ANR 为 0。日志各见一次背景图读取与 Flutter 撤罩，录屏可见 Web 背景与原生正文交叠，随后实开原生会话抽屉。首次未就绪拒绝样本、淡入精确时长与逐帧位移仍待核。 [入口日志](<D:/dsh-native-chat-validation/beta119-device-recovery.nKTdZW/entry-events.log>) · [录屏](<D:/dsh-native-chat-validation/beta119-device-recovery.nKTdZW/transition/display.mp4>) · [转场帧索引](<D:/dsh-native-chat-validation/beta119-device-recovery.nKTdZW/transition/entry-overview.png>)

- beta.119 桌宠设备探测点击了原生抽屉账号卡，设置菜单没有得到可用的界面树证据。桌宠页与三项开启门禁仍待实测。该轮模拟器已停，映射回读一致。 [设备收据](<D:/dsh-native-chat-validation/beta119-pet-ask-device.7PYTUv/receipt.json>) · [账号卡截图](<D:/dsh-native-chat-validation/beta119-pet-ask-device.7PYTUv/native-drawer.png>)

- 原生设置「显示与个性化」增加「悬浮桌宠」，沿用 Android 的桌宠设置页。开启桌宠仍需悬浮权限、已启用且版本至少 0.3.1 的桥插件及已安装资源包。离线源码契约 7/7、12 个变异反例被拦截。Flutter 设置定向 42 项通过。beta.119/code494 全量 Flutter 735 项通过、1 项跳过，静态分析通过。Android 单测 198 项通过。x86_64 Full Debug 包已构建并通过 v2 签名校验。专用 AVD 实点待验。 [Android 构建日志](<D:/dsh-native-chat-validation/beta119-pet-build.lJ8AOU/gradle-nonincremental.log>) · [签包收据](<D:/dsh-native-chat-validation/beta119-pet-build.lJ8AOU/artifact.json>) [全量回归](<D:/dsh-native-chat-validation/beta119-pet-build.lJ8AOU/flutter-full.log>) · [静态分析](<D:/dsh-native-chat-validation/beta119-pet-build.lJ8AOU/flutter-analyze.log>) [入口测试](<D:/dsh-native-chat-validation/beta119-pet-build.lJ8AOU/flutter-targeted.log>) · [源码契约](<app/src/test/native-pet-settings-evidence/receipt.json>)。

- beta.118 冷入口录到一次已就绪正常触摸与 Native Activity 创建。首次宿主读数为 Healthy 且可加载。保活阶段重复读取同一宿主导致旧夹具判重并提前结束，未覆盖 Flutter 撤罩与完整淡入。视频双端 SHA 复核通过，临时录屏已回收。后续按唯一的 `entry=accepted/rejected` 决策行取证。 [设备收据](<D:/dsh-native-chat-validation/beta118-cold-entry-recording.ySBAq5/receipt.json>) · [录屏](<D:/dsh-native-chat-validation/beta118-cold-entry-recording.ySBAq5/transition/display.mp4>)。

- beta.118 Ask 临时设置复验已恢复。真实试验在设置阶段停止，发送次数为 0。随包 schema 为模型和服务商补齐字段，原夹具的严格字段集合误判，导致当轮清理拒绝处理测试配置。恢复时核对唯一测试配置的 loopback 地址、凭据引用、模型 ID，以及其他服务商设置的基线哈希，再移除本轮配置。Host 回读确认服务商、凭据、原模型选择、默认模型与原 Web 会话恢复，映射已还原，模拟器已停。 [中止收据](<D:/dsh-native-chat-validation/beta118-ask-void-ack.2qTOi4/ask-device-receipt.json>) · [恢复收据](<D:/dsh-native-chat-validation/beta118-ask-settings-recovery.xVQHce/receipt.json>)。

来源：2026-11-22 00:49 用户反馈；02:02 补充要求逐条修复、持久记录、团队负责全流程验证、不频繁压缩。此文件是本轮需求索引，不以会话摘要替代。状态：待验证、通过、失败分别记录；通过必须注明版本/命令/产物，安装不是界面验收。目标树 feat/flutter-native-chat（7d9b26df，含既有 WIP）；仅专用模拟器，勿操作 PLZ110、主仓库和其它设备。不复制 dsh-Go UI、源码或资产，仅核实其 API 使用并对照实际运行时契约。

| ID | 用户反馈与验收标准 | 状态 |
|---|---|---|
| B01 | Web → 原生切换：Web 不得先整体上移再回落，不闪黑/空白；通过正常入口逐帧录屏验证约 1 秒淡入 | beta.115/code490 在宿主已就绪后，通过正常 Web 工具栏进入原生并完成工作区交互；此样本不替代快速冷入口验收。 B01 Android 接线已完成定向 36 项、未过滤 173 项单测（均退出 0），尚未在新包实测未就绪拒绝、重试或转场，不结案。[Android 回归收据](<D:/dsh-native-chat-validation/android-b01-nonincremental.ugDyFV/receipt.json>)。beta.114 冷切换曾触发系统 ANR（失焦事件 5,032 ms 未获处理），转场逐帧验收和快速冷入口仍未结案。[beta.115 设备收据](<D:/dsh-native-chat-validation/beta115-device.LExK3EPV/workspace-device.json>) · [冷入口收据](<D:/dsh-native-chat-validation/code489-verified-entry-119f02495d-receipt.json>) 补充：code490 JIT 与 code491 AOT 各一次进程冷启动均未复现 ANR；未记录点击时宿主是否未就绪，快速冷入口仍未结案。[实机对照收据](<D:/dsh-native-chat-validation/cold-entry-ab.YGhP0ARF/comparison.json>) · [耗时取证](<D:/dsh-native-chat-validation/cold-entry-ab.YGhP0ARF/comparison-timing.json>)。 恢复后重启回读 code490 实装 SHA 与数据 inode 一致；另外两次进程冷启动在工具栏首次被脚本观测时均已显示「引擎已就绪」，均进入原生、采样窗口未见 ANR。引擎未就绪的合格入口样本仍为 0，故障未结案。[重启与两次采样收据](<D:/dsh-native-chat-validation/beta115-recovery-reboot.UKlcZh42/reboot-receipt.json>)。 原 ANR 点击前界面明确显示「正在启动引擎…」；宿主重启对照：未在同一无障碍树读到明确启动状态与可点入口，未点击，不替代全应用冷入口。[宿主重启对照收据](<D:/dsh-native-chat-validation/beta115-engine-restart-entry.Uh7nJlDQ/restart-entry.json>)。 **beta.117 曾受 AVD 包扫描阻塞（历史）。** code492 覆盖安装及当场指纹回读通过；AVD 重启后 4 次均未找到活动 APK 或正常启动入口，并记录 `missing scanned package`，未打开应用。新版冷入口与转场仍未验证。 [分段收据](<D:/dsh-native-chat-validation/beta117-package-readback-lead.MZuV3A/receipt.json>)。 **beta.117 已就绪入口到达 Native Activity，Flutter 首屏和严格未就绪路径待验。** 同包保数据恢复后，下一次启动 code492 原数据标识及 APK SHA 回读一致。一次实际工具栏点击时 Host 为 Healthy 且可加载，随后 Native Activity 获得焦点，观测窗内应用 ANR 为零。延后取证仍见转场遮罩，严格未就绪拒绝、实际 SID、Flutter 首屏和逐帧转场待验。 [首屏取证](<D:/dsh-native-chat-validation/beta117-native-render-lead.ECWk5j/receipt.json>)。 [跨重启收据](<D:/dsh-native-chat-validation/beta117-reboot-focus-lead.vMYJWs/receipt.json>)。 **beta.118/code493 已就绪冷入口实装通过。** 同一轮正常点击后 Flutter 请求和释放遮罩各一次，原生抽屉出现，提取 kernel 哈希与验签包一致，应用 ANR 和 kernel 错误为零。严格未就绪重试、SID 和逐帧转场仍待验。 [首屏收据](<D:/dsh-native-chat-validation/beta118-native-startup-device.PhoctD/receipt.json>)。 beta.118 两次已就绪正常入口均直接呈现 Web 来源的同一 QA 会话正文。[会话接续收据](<D:/dsh-native-chat-validation/beta118-handoff-ready-predicate.sH2Pz8/receipt.json>)。 beta.138 冷入口未就绪路径复核：ad hoc 三次设备尝试（冷启轮询即点、放宽采样、重启后 1 秒内点）在 code513 上均未抓到未就绪窗口——点击时 labels 已含「引擎已就绪」，重启也瞬时回到 Healthy（日志 `entry=accepted ... phase=Healthy`）。拒绝路径目前只有组件级正证（`app/src/test/b01-evidence/final-green/ColdEntryBehaviorTest.kt:109-138`：NotReady + `notices==[NotReady]` + 无事件；`MainActivity.kt:449-458` 打 `entry=rejected` 并弹提示），缺设备手势级正证，故仍不结案 |
| B02 | SSH 原生功能，不以打开 Web 当实现；专用模拟器连自有隔离 sshd，核主机密钥，验证实际认证、远程命令及结果、拒绝错误密钥/认证、断开 | 隔离 SSH 流程通过；Windows 实机未验。beta.138 补充精确缺口：原生「连接与远程」页有「Windows 主机 / Linux 主机」两个添加入口（均标注「验证主机指纹 · 密码连接」）与「未连接任何 SSH 主机」，本轮无任何已授权 Windows SSH 主机凭据，因此无法建立真实连接——属环境缺口，不是缺陷 |
| B03 | 长标题循环：整个标题走完，两个空白字符后接标题开头；同方向无可见回弹，每圈停约 1.5 秒；短标题不动；验证实际裁切窗口与相位 | beta101 长标题实测左移无反向跳，停顿约 1.55/1.45 秒；两个空格已有组件覆盖，短标题同机对照静止 |
| B04 | 设置所有可见选项逐一点击，子页不得刚出现就滑回；返回仍可操作 | beta100 专用 AVD 逐一实测 11 个子页进入及返回，独立 Web 入口打开；仅验入口。beta.138 补充：设置页滚动后四组十二个入口全部可达，点「权限管理」进入真实二级页（对话工具／当前会话访问范围=完全访问／Android 权限与能力／应用权限中心），系统返回回到原滚动位置；「连接与远程」「关于」两页同样实测打开 |
| B05 | 引擎重试阶段立即提示而非无响应到最终报错；报错展开触摸灰色效果裁在卡内；正常完成/最终失败各一条 | beta100 宿主重试后成功与最终失败均已在原生历史回读；beta101 展开卡左、上样区实测按压未溢出。beta102 [重试中截图](</d/dsh-native-chat-validation/beta102-c7678f2663-retry-in-flight.png>)证实下一请求未返回时提示与停止键可见；`llm/retry` 发出到 `retry-started` 之前的首次出现时延未测。 beta.138 复核：`dsh_session_projection_test.dart:744-800` 断言 `llm/retry` 到达即产生可读的「模型请求失败，正在重试 1/5」系统行并保持 streaming、`llm/retry-started` 后仍 streaming、`turn/end` 才 ready 且唯一 turnComplete；`:803-838` 是否证：超过 delayMs 窗口后 idle 读数必须被信任。定向跑三个测试文件 = 32 passed。仍未测的是**引擎侧 `llm/retry` 发出到 `retry-started` 的首次出现时延**（需可复现的故障注入，本地确定性服务不提供该事件） |
| B06 | 思考/工具分段不结束整轮，不重复显示完成/复制等，最终用户轮结束只展示一次完整操作 | beta102/code477 专用模拟器通过：两次只读工具返回后停止键保留、无助手回复动作；宿主记录唯一一次成功 `turn/end` 后，停止键消失，只有最终答复出现一套五个动作。[beta101 复现轨迹](</d/dsh-native-chat-validation/beta101-7e52091455-live-turn.json>)、[beta102 实时轨迹](</d/dsh-native-chat-validation/beta102-c7678f2663-live-turn.json>)、[终态截图](</d/dsh-native-chat-validation/beta102-c7678f2663-finished-turn.png>)。仅验证显示时机，未逐一执行五个动作。 |
| B07 | 耗电：测相同条件的前台静置、退后台与滚动，记录 CPU/活动帧/周期任务；模拟器仅做代理指标，不冒充电池续航 | beta106 同一 30 轮 QA 会话的原生可滚动，前台静置约占单核 103%，滑动约 123%，后台 27%。beta108/code483 同 APK 重新测既有 30 轮 Host 会话，将采样值记于续验段；Flutter 单帧时间和真机耗电仍待验。 |
| B08a | 助手表格与可视化：比较表、柱/条图及 Markdown 在真实入口验证，不只验证测试专用语法，保留可读降级 | beta100 修复对话流误折叠图表与原文降级，四项红绿回归；APK 已装，真实 Android 宿主＋本地确定性服务已生成并由原生会话显示表格和条形图；beta106 已实测原生流式过程中表格与条形图可见。外部模型自然提示未验。 |
| B08b | CLI 从所属会话触发本次权限请求；Web 弹窗、原生对话流卡片共用一项请求；同意/拒绝各输出结果，20 秒无同意默认拒绝，不允许调用者自行提升权限；一次结案、不可跨会话代批，拒绝不停止整轮 | beta108/code483 Web→原生实点拒绝，Host 11.024 秒同 ID 唯一结案，非超时，工具获拒绝且双端终态。另在同包复测原生→Web 主动允许 (13.295 s)、Web A→B→A (10.700 s；B 为 0 条 Host 审批事件)、原生无人处理 (20.005 s 后 Host cancelled)，均收到唯一工具回执；同包 Web 直接允许 11.392 s。完整收据见续验段。 |
| B09 | 按 DSH 权威工作区与顺序展示会话分组；新建进入所选组，不把所有新会话置入未分组 | beta.115/code490 专用 AVD 通过。原生 Bravo 组折叠后特征成员消失，恢复后重现；Host 回读默认新会话落在 Home 默认目录。选 Alpha 未新增或移动会话，下一次新建落在 Alpha，成功后胶囊回到 Home。导航竞态由组件红绿验证，未作设备竞态复现。[beta.115 设备收据](<D:/dsh-native-chat-validation/beta115-device.LExK3EPV/workspace-device.json>) **持久默认工作区：全量 Flutter 726 项通过、1 项跳过，完整静态检查无问题；Android 落盘、冷启与真实 Host 归组待验。** beta.115 仅证明一次性选组；持久默认须以重启后连续两次普通新建的 Host cwd 和实际归组为准，另验显式 Home 覆盖与宿主切换隔离。 **beta.117 曾受 AVD 包扫描阻塞（历史）。** code492 覆盖安装及当场指纹回读通过；AVD 重启后 4 次均未找到活动 APK 或正常启动入口，并记录 `missing scanned package`，未打开应用。新版持久默认实装、冷启与 Host 归组仍未验证。 [分段收据](<D:/dsh-native-chat-validation/beta117-package-readback-lead.MZuV3A/receipt.json>)。 **beta.117 包扫描恢复已跨重启验证；持久默认实装仍待验。** 同包保数据恢复后，下一次启动 code492 和实装 SHA 回读一致，正常工具栏可到达 Native Activity，初始持久偏好已独立回读为缺键。原生默认菜单、冷启后偏好落盘、原值还原及 Host 实际归组仍待验。 [跨重启收据](<D:/dsh-native-chat-validation/beta117-reboot-focus-lead.vMYJWs/receipt.json>)。 **beta.118/code493 持久默认实装通过。** 在原生菜单将 Alpha 设为默认，重启前创建一条，重启应用及 Host 后连续创建两条。Host 列表回读三个不同 SID 的 Alpha cwd，工作区完整帧确认均为 Alpha 成员。正常菜单将默认恢复为原有缺键，设备回读确认。显式 Home 覆盖和目录域切换仍待实装。 [设备收据](<D:/dsh-native-chat-validation/beta118-workspace-selection.tVkO5e/receipt.json>) · [恢复回读](<D:/dsh-native-chat-validation/beta118-workspace-selection.tVkO5e/restoration.json>)。beta.137 复核：专用 AVD 上两组 `dsh-qa-beta98-bravo` / `dsh-qa-beta98-alpha` 顺序与 Host 一致，点 bravo 组头后成员行 `QA Approval c778dbefe1` 与表格行「Item / Value」消失、再点恢复，alpha 成员在折叠期间仍在；欢迎页「工作区」与「对话模式」同排（中心纵向差 <40px）；「设为默认」写入 `defaultWorkspace.v1.<domain sha256>` = 真实 workspaceId 与目录，「清除默认」后该条目消失且胶囊回落 Home。package SHA-256 eabd4482...（code512/beta.137） |
| L01 | 工具调用和展开思考字号、行距、边距收紧，长内容仍可阅读 | beta106 标准屏长工具卡实际展开，截断预览和完整页均可用，滚动到宿主输出的末行标记。独立长思考与窄屏完整输出待验。。beta.138 复核（真实 QA 会话）：折叠标题「已调用工具，已运行命令」，展开插入命令行（dsh-request-permission，含「已完成」与成功标记），再折叠收回；工具行与正文连续、无重复完成/复制块（`beta151-transcript3.fc7l9d/`） |
| L02 | 会话抽屉新建按钮下方减少留白，快速进入对话选择 | beta108/code483 标准与 360 dp／1.6× 新建触控高度 132／159 px；beta99 标准会话行间距 11 px。 |
| L03 | 删除“会话来自本机引擎”，账户资料更靠近底部，保留安全区 | beta108/code483 标准与 360 dp／1.6× 旧说明缺席，账号菜单均可实际打开；其他系统安全区待验。 beta.138 系统安全区复核：判据纠偏——导航条走 `MediaQuery.padding.bottom`（SafeArea 消费），不是 viewInsets；新增回归「drawer footer stays above the system navigation inset」在 bottom=66（手势）/132（三键）两种高度下均断言 `AccountFooter.bottom ≤ 2340-navHeight` 通过（`session_drawer.dart:89` 的 SafeArea 生效）。设备侧仅取到两种模式的导航区几何（`frame=[0,2274][1080,2340]` 与 `[0,2208][1080,2340]`，模式切换与复位成功），但抽屉在设备上未打开（Flutter 语义滞后），故设备级 footer 几何仍未验，不据此结案 |
| L04 | 设置中退出登录放账号右侧，Token 卡片上移 | beta106 窄屏未登录态实测 Token 紧跟账号卡。已登录态退出按钮量测待有授权登录态。 |
| L05 | 运行环境与“打开 Web 版”减少无效留白 | beta108/code483 标准与 360 dp／1.6× Linux 主机行距 Web 入口均 11 px，两控件可触达。 |
| L06 | 输入框加号贴左且固定，模型紧跟加号并可伸缩；权限靠近位置固定的发送按钮，换长模型也不移锚点 | beta106 标准屏从短名切到长名，加号、权限、发送边界均不变，模型项可伸缩，长名省略。 |
| L07 | 改正不准确或含混的选项说明及发送框文案，并检查窄屏和大字 | beta106 标准屏输入提示实测可见，360 dp／1.6× 原生设置主页面抽到账号、Token 和 8 条入口文案。窄屏输入提示及各子页逐项审校待补。 beta.138 大字号复核：三次设备尝试（冷启/动态改字号/窗口缩放）均因 `am force-stop` 清空无障碍或配置变更期间 a11y 节点暂时消失而拿不到 1.6× 设备截图（`beta157`/`beta158`/`beta159` 收据）。组件级正证齐全：`composer_action_matrix_test.dart:325-345`（360×800+2x：胶囊 32–36dp 夹紧、发送可点）、`chat_screen_test.dart:100-132`（320×700+1.6x：四卡正方 ≥48dp）。故仍记部分覆盖，缺口属取证手段限制 |
| F01 | 设置完整原生路由与界面，不跳回旧设置；优先复用现有页文件；选项有真实存储/调用，刷新后核读 | beta108/code483 德语读写、返回 Flutter 即时跟随、重进保留和恢复通过；当前 QA 会话权限即时 Host 回读通过且未发送。模型设置完整读写见 beta103，三项外观回读见 beta98；其余逐项确认。。beta.138 补充 Ask 跨端判据：应用内 Terminal 回读 `/overlay/available` = {"available":true,"mounted":true,"shown":true}、`/ask` pending 带两选项；点选后 `/ask/result` = {"state":"ANSWERED","answer":"Overlay-alpha","fromOption":true} 且抽屉消失（`beta152-overlayapi.vxZ03L/receipt.json`）。设置完整路由：抽屉→账号 footer→「设置」→ 设置页与「权限管理」「连接与远程」「关于」二级页均实测打开 |
| F02 | 新对话顶部按小时轮换欢迎语（24 小时）、中间 6 个入口（三排两列，兼顾普通聊天、无障碍、编程/构建等）；点击填草稿但不发送 | 后续要求改为四张方卡。beta.115/code490 标准屏实测长工作区名与对话模式同排，均完整位于输入区上方，菜单保留全名；整点切换、24 条文案和点卡只填草稿继续以原有对应证据为准。[beta.115 设备收据](<D:/dsh-native-chat-validation/beta115-device.LExK3EPV/workspace-device.json>) · [同排截图](<D:/dsh-native-chat-validation/beta115-device.LExK3EPV/alpha-selected-same-row.png>)。beta.138 复核（code513）：四张方卡 2×2（编写代码/轻松聊天/整理计划/无障碍操作）+ 鲸鱼品牌 + 欢迎语在设备上达标；**修复**键盘弹出时卡片被压缩导致「无障碍操作」末字被裁，改为空间不足时图标让位，复验四卡文案完整两行（`beta144-keyboardlabels.m5q8jE`）。整点轮换与 24 条文案沿用 beta.115 结论 |
| F03 | 新对话底部真实模式目录，含宿主提供的 PTC/创造/插件模式；使用宿主默认与锁定语义，不编造不可用模式 | beta106 同一空会话设备真实选 `ptc` 并发送首轮，首轮后可编辑入口退出，Host 对改模式返回 `agent-preset/locked`。 |

实施路线：先核宿主契约与基线；各分线写失败回归、最小修复、再验证；共享 EngineHome/ChatShell 顺序接入。最后执行 Flutter analyze/全量测试、Android 构建，从专用模拟器实际入口验收，记录未验项。分组采用 workspace/follow；对话模式采用 agentPresets/list/select（仅首轮前），新建使用 session/create request.workspaceId/agentPreset；普通工具审批复用 $events/result。CLI 审批待核宿主入口、请求身份与拒绝超时，不把客户端弹窗当权限边界。SSH 仅连接已授权隔离测试目标，不涉及利用漏洞或绕过认证。

- F02/F03（欢迎页四卡）：beta.138/code513 设备实测通过——鲸鱼品牌、欢迎语、四张方形卡（编写代码/轻松聊天/整理计划/无障碍操作）2×2、下方「对话模式」与「工作区」并排。**修复**：键盘弹出时卡片被压缩，`无障碍操作` 末字被裁（beta143 证据）；改为空间不足时让图标让位，复验文案完整两行（`beta144-keyboardlabels.m5q8jE`）。
- 未判定：设置二级路由本轮未观测到入口节点，不计入通过。


- 设置路由（B05/B06 相关）：beta.138/code513 设备实测——原生抽屉账号 footer →「设置」菜单项 → 设置页真实打开，含「搜索设置」「登录 DeepSeek」「Token 记录」12 周热力图与「模型配置」分组；截图 `beta146-settingspage.a2anna/beta118-f08...-settings-page.png`。**未判定**：外观/权限/语言/扩展等二级页未在首屏可达，二级页样式与路由不据此判通过。
- B09 默认工作区：清除路径本轮补齐——点「清除默认」后 `defaultWorkspace.v1.<domain sha256>` 从 `shared_prefs/native_chat_ui.xml` 移除，胶囊回落「工作区：Home」。

- 设置二级页（B05/B06）：beta.138/code513 设备实测通过——设置页滚动后可见「运行与能力/显示与个性化/系统与数据/更多」四组共 12 个入口；点「权限管理」进入真实二级页（对话工具、当前会话访问范围=完全访问、Android 权限与能力、应用权限中心），系统返回回到原滚动位置。**更正**：上一版「二级页未验证」结论源于只看了未滚动首屏，已作废。
- Windows SSH（B05 相关）：`连接与远程` 页确认有「Windows 主机 / Linux 主机」两个添加入口与「未连接任何 SSH 主机」；缺口是无已授权 Windows SSH 凭据，属环境缺口而非缺陷。

- L01/B06（工具栈与思考分段）：beta.138/code513 真实会话 `QA Approval c778dbefe1` 实测——折叠标题「已调用工具，已运行命令」，展开插入命令行（`dsh-request-permission`，含「已完成」与成功标记），再折叠收回；工具行与正文连续、无重复的完成/复制块。证据 `beta151-transcript3.fc7l9d/`。
- F01/B04（Ask 跨端判据）：应用内 Terminal 回读 `GET /overlay/available` = `{"available":true,"mounted":true,"shown":true}`、`GET /ask` = `pending:true` + 两个选项；点选后 `POST /ask/result` = `{"state":"ANSWERED","answer":"Overlay-alpha","fromOption":true}` 且抽屉消失。证据 `beta152-overlayapi.vxZ03L/receipt.json`。
## beta.137 工作区与提问链路复验（2026-11-27）

- B09（会话按工作区分组）：专用 AVD `emulator-5570`、code512/beta.137、保数据安装（ceDataInode 139581）。抽屉内两组 `dsh-qa-beta98-bravo`、`dsh-qa-beta98-alpha` 的组名、顺序与 Host 一致；点 bravo 组头后其成员行 `QA Approval c778dbefe1` 与 `| Item | Value |` 消失，再点恢复；alpha 组在折叠期间成员仍在（截图 `beta142-collapsedefault.oqAYCW/beta118-988219d41eb9-collapsed-0.png`）。证据 `beta141-wsdrawer.z5UVyO/receipt.json`、`beta142-collapsedefault.oqAYCW/receipt.json`。
- B09（新对话旁的工作区选择 + 可持久化默认）：欢迎页「工作区：dsh-qa-beta98-alpha」与「对话模式：standard」同排（中心纵向差 <40px），工作区胶囊在右侧；菜单含 Home/两个真实工作区/设为默认/清除默认/重试；点「设为默认」后 `shared_prefs/native_chat_ui.xml` 写入 `defaultWorkspace.v1.<domain 的 SHA-256>` = `{"path":"/data/user/0/app.dsh.mobile/files/dsh-home/dsh-qa-beta98-alpha","workspaceId":"c42844a6-ada8-45b6-838c-03ebff9b3e98"}`。
- **未判定**：保存后「清除默认」项的可见性本轮未被单独观测（`clearVisibleAfterSave=false`），清除路径不能算已验证。
- Ask（F01 相关）：原生聊天内真实 `POST /ask` → 无障碍悬浮抽屉显示问题与两个选项 → 点选 `Overlay-alpha` → `POST /ask/result` 回读 `{"state":"ANSWERED","answer":"Overlay-alpha","fromOption":true}` → 抽屉消失；无障碍设置恢复 `null`/`0`、模拟器停。证据 `beta138-confirm.6NZfvE/`。包 SHA-256 `eabd4482c49c97eeb65082f6eba07998247d29f1666b32799cdc0ff454a897d1`。

## beta.115 工作区专项续验

- beta.115 设备续验：保数据装机且 code490/x86_64、实装 SHA-256 与产物一致。引擎就绪后经正常工具栏进入原生；Bravo 折叠和恢复、Home 默认目录、长名 Alpha 同排且位于输入框上方、Alpha 下一次新建归组及恢复 Home 均通过。竞态仍为组件级红绿证据，不冒充设备复现。 [收据](<D:/dsh-native-chat-validation/beta115-device.LExK3EPV/workspace-device.json>)。 [Alpha 实装布局](<D:/dsh-native-chat-validation/beta115-device.LExK3EPV/alpha-selected-same-row.png>)。 已确认专用 AVD 序列号消失、5570/5571 无监听。 快速冷入口 ANR、结构化 Ask、桌宠和真机不在本次通过范围。

- beta.118 系统返回键的会话接续通过。正常 Web 工具栏打开 A，原生抽屉切换到 B，按系统返回后原 WebView 显示 B 的准确 SID 与正文，A 正文已消失。CDP frame、loader 与页面时间原点一致。原 Web 选中项和 ADB 映射已恢复。[设备收据](<D:/dsh-native-chat-validation/beta118-handoff-drawer.soCXVh/receipt.json>) · [返回 Web 后的会话](<D:/dsh-native-chat-validation/beta118-handoff-drawer.soCXVh/back-web-b.png>)。

- beta.118 显式「打开 Web 版」的会话接续通过。经正常工具栏打开 A，原生抽屉切换 B，实点运行面板中的「打开 Web 版」，原 WebView 显示相同的 B SID 和独有正文，A 正文消失。CDP frame、loader 与页面时间原点一致。原 Web 选择和 ADB 映射恢复，专用模拟器已停。 [设备收据](<D:/dsh-native-chat-validation/beta118-handoff-tap-visible-web.eHK0VK/receipt.json>) · [实点前的运行面板](<D:/dsh-native-chat-validation/beta118-handoff-tap-visible-web.eHK0VK/runtime-before-explicit-tap.png>) · [返回后显示 B](<D:/dsh-native-chat-validation/beta118-handoff-tap-visible-web.eHK0VK/web-web-b.png>)。

- `1.6.0-beta.115 / code490 / x86_64 Full Debug`；APK 252716261 bytes，SHA-256 `b5f6d214a62d9cd0a1130212718afe9c37474c8b5e024dc29e59bb28184a06d9`，v2 验签通过；Gradle Android 单测和打包均退出 0。[构建日志](<D:/dsh-native-chat-validation/beta115-build.bzZqalif/gradle.log>) · [产物指纹](<D:/dsh-native-chat-validation/beta115-build.bzZqalif/sha256.txt>)。
- 创建竞态：同 Host 成功回执先消费本次选择，再检查过期导航，失败不消费。长工作区名称：标准宽度两选择器同排、省略显示，菜单保留全名；窄屏和大字号允许分行。红测旧布局两按钮中心相差 65 dp；修后控件可见、可点、回选 Home 等于 null。Flutter 定向 26 项通过；全量 691 通过、1 跳过；analyze 无问题。[定向](</d/dsh-native-chat-validation/beta114-workspace-layout.HxOeTsgz/targeted.log>) · [全量](</d/dsh-native-chat-validation/beta114-workspace-layout.HxOeTsgz/full.log>) · [静态分析](</d/dsh-native-chat-validation/beta114-workspace-layout.HxOeTsgz/analyze.log>)。
- 专用模拟器验证未全通过；安装哈希未获得回读。最后确认阶段：无；Command '['E:/Android/Sdk/platform-tools/adb.exe', '-s', 'emulator-5570', 'install', '-r', '-t', 'D:\\dsh-native-chat-validation\\beta115-build.bzZqalif\\dsh-native-chat-beta115-x86_64.apk']' timed out after 12 seconds。不将旧包实测或本次源码回归冒充新包设备通过。[设备收据](<D:/dsh-native-chat-validation/beta115-build.bzZqalif/workspace-device.json>)。

## 结构化 Ask 待决证据（beta.113）

- beta.118 Ask 只读观察预检确认了 code493 实装身份。正常打开终端后，窗口在提交之前回到 Web，观察器没有启动，临时文件已回读清除。待答路由仍缺同轮 Bridge 数据。[复验收据](<D:/dsh-native-chat-validation/beta118-ask-passive-preflight.ohSw2l/receipt.json>) · [终端返回日志](<D:/dsh-native-chat-validation/beta118-ask-passive-preflight.ohSw2l/startup-filtered.json#L93>)。

- beta.118 Ask 观察器复验通过。从正常终端入口点击 PTY 并确认控件获得输入焦点。返回 Web 后，既有认证通道连续 3 次完整读到 `GET /ask` 的 `pending=false` 和 `GET /task/allow` 的 `allowed=true`。本轮属于发送前观察器校验。观测进程已退出，临时文件和 ADB 映射已清理，专用 AVD 已停。 [设备收据](<D:/dsh-native-chat-validation/beta118-session-selected-baseline.bosljE/receipt.json>)。

- beta.118 Ask 模型恢复预检。设备 Host 的 `session/modelCatalog` 回读可用服务商数为 0。已知空会话的 `modelSelection.next` 为 null，两个既有 QA 会话有明确模型选择。已找到满足恢复门槛的 QA 会话，预检期间模型设置无写入、消息发送次数为 0。已恢复 ADB 映射，专用模拟器已停。 [设备收据](<D:/dsh-native-chat-validation/beta118-ask-qa-model-preflight.p74Qmn/receipt.json>)。

- beta.115/code490 Ask 临时配置清理复验：按上次只读审计仅接受同一测试模型的 `id` 及宿主补齐的 `compat`、`input`，并核对专属凭据引用、loopback `/v1`、唯一 QA 会话与原始提问/工具调用序号。回读确认测试模型已移除、专属凭据未配置、其他模型不变、QA 会话仍在且空闲、ADB 映射恢复。未清数据、删历史或改 Web 选中项（原值未留证）。专用 AVD 已停，端口已释放。本次不验证 Ask 跨端问答。[回读收据](<D:/dsh-native-chat-validation/beta115-ask-scoped-cleanup.roXieLUh/cleanup.json>) · [所有权反例](<D:/dsh-native-chat-validation/beta115-ask-scoped-cleanup.roXieLUh/ownership-controls.json>)。

- beta.115/code490 Ask 专用夹具 72dbbb392d 清理续验：尚未验证全部清理：Matching provider name has different ownership; no cleanup。原 Web 选中项未记入失败收据，未猜测或覆盖；未删会话、未清数据。专用 AVD 已停，5570/5571 无监听。本次不验证 Ask 分流或原生选答。[清理收据](<D:/dsh-native-chat-validation/beta115-ask-cleanup-recovery.oEgliMMa/cleanup.json>)。

- beta.115/code490 Ask 被动取证：实装 SHA 已核对。未取得可用的 Web 下行采样。夹具未取得同询问待决观测。 AssertionError('Closing the keyboard did not preserve the exact QA draft')。临时模型、会话选择和 ADB 映射已回读恢复。专用模拟器已停，保留端口已释放。未捕获匹配帧不等于未派发（既有连接可能未获 URL）；不据此认定悬浮窗接管。未验证原生选答完成。[设备收据](<D:/dsh-native-chat-validation/beta115-ask-passive.Szfymi7z/select-summary.json>) · [离线反例](<D:/dsh-native-chat-validation/beta115-ask-passive.Szfymi7z/observer-offline.json>)。

- beta.115/code490 专用 AVD：Web 实际发出提问，宿主记录同调用 `tool/call` 第 19 条序号；30 次同轮采样均确认 Web 会话正确、Gateway 会话待决为空、Web 可见卡 0、同调用结果 0。未进入原生选答，Ask 跨端未通过。失败时的无障碍树未检出本轮题干或选项；它不是悬浮窗分流证据。临时设置、选中会话和 ADB 映射均回读恢复。包内离线对照证明 `allowed:true` 足以让普通问询走悬浮窗，但本次没有证据证明实际命中；下一步先并列取证桥的只读待决与同询问 Gateway 交付，不改开关、不消费答复。[beta.115 设备收据](<D:/dsh-native-chat-validation/beta115-ask-select-retry.bOFIkxrw/select-summary.json>)。

- beta.113/code488 的 Web 输入框已发送，宿主事件中出现唯一同调用 ID 的 `tool/call`。采样窗口内宿主 `pendingInput` 为空、未看到 Web 待决卡；续测未拿到新鲜的原生无障碍树，未验证原生选答或答复回传。临时设置、选中会话、ADB 转发均已回读恢复。不能据此断言插件未激活或原生卡渲染失败；下一次回环先分别保存 `tool/call`、Host `pendingInput.question` 和 `tool/result` 时间线，再验两端显示与同一调用只结案一次。不使用外部计费模型。[Ask 分阶段收据](<D:/dsh-native-chat-validation/native-ask-da7431e8c3-device.json>)

## beta.114 工作区专项续验（2026-11-26）

- 验收对象为专用 AVD `dsh_flutter_20261116 / emulator-5570` 上的 `1.6.0-beta.114 / code489 / x86_64`，APK SHA-256 为 `89e57c11d09108912699ab9850c9f34e4a8a4e0e3d24e8a4ffc323444b09c105`，构建产物与设备 base.apk 一致。Flutter 全量 689 通过、1 跳过，analyze 无问题，Gradle full 构建成功。
- B09 专项：真实折叠 bravo 组后成员行消失，恢复后成员行重新出现。欢迎页工作区胶囊默认 Home，菜单列出 Host 的三个真实选项；选择 Alpha 后新建会话，Host 返回 cwd 为 Alpha 目录，原生抽屉将新会话放在 Alpha 分组。Home/Alpha 的选择与创建仅作用下一次新会话，不移动当前会话。
- B01 专项：warm Web→Native 入口已观察到 NativeChatActivity 可操作；冷进程入口仍出现 FocusEvent 等待 5032 ms 的系统 ANR，不能把 warm 证据扩展为冷入口通过。Ask 跨端选答和桌宠未在 beta.114 重新实测，继续沿用未验证状态。
- 证据：[构建 APK](<D:/dsh-native-chat-validation/beta114-build/dsh-native-chat-beta114-x86_64.apk>) · [Alpha 归组收据](<D:/dsh-native-chat-validation/beta114-workspace-alpha-device.json>) · [折叠](<D:/dsh-native-chat-validation/beta114-workspace-bravo-collapsed.png>) · [恢复](<D:/dsh-native-chat-validation/beta114-workspace-bravo-restored.png>) · [欢迎页](<D:/dsh-native-chat-validation/beta114-native-welcome-retry.png>) · [工作区菜单](<D:/dsh-native-chat-validation/beta114-native-workspace-menu.png>) · [冷入口收据](<D:/dsh-native-chat-validation/code489-verified-entry-119f02495d-receipt.json>)

## beta.112 实装续验（2026-11-25）

专用模拟器 `dsh_flutter_20261116 / emulator-5570` 运行 `1.6.0-beta.112 / 487 / x86_64 Full Debug`。实装与构建 SHA-256 一致，为 `5d209f64d83251cac1ad7e7800d77740d5a80bd819732b4c01af917f44a7c54c`。Flutter 全量 680 项通过、1 项跳过，静态分析无问题。[构建日志](<D:/dsh-native-chat-validation/beta112-build/gradle.log>)

- B04 / F01。经 Web 工具栏进入 `NativeChatActivity`，实点模型配置、个性化主题、权限管理并返回。两次权限回读均为「完全访问」。[路由和权限收据](<D:/dsh-native-chat-validation/beta112-device-settings-qa.json#L639>)
- B05 / B06 / L01。同一轮经过一次回环重试、两次真实 Bionic Node `read`、一次成功 `turn/end`。重试时显示提示和停止键，工具返回后直到结案仍只有停止操作。一个摘要展开思考→read→思考→read，组内间距 2.18／0／2.18 dp，实点「查看完整内容」可见长结果尾标记。[展开截图](<D:/dsh-native-chat-validation/beta112-06163ce362-tool-stack-expanded.png>) · [输出回读](<D:/dsh-native-chat-validation/beta112-06163ce362-existing-output.json#L148>)
- F02 / F03。后续反馈把六卡改为四卡。标准模拟器上鲸鱼标志、问候、四张 148 dp 方卡、模式胶囊与输入区同屏；实点胶囊显示 Host 的 standard、ptc、minimal、cordis。点击卡片只填草稿由组件测试覆盖。[欢迎页](<D:/dsh-native-chat-validation/beta112-4ec9cefc6d-welcome-one-screen.png>) · [模式菜单](<D:/dsh-native-chat-validation/beta112-4ec9cefc6d-welcome-mode-menu.png>)

## beta108 续验（2026-11-24）

验收对象为专用 AVD dsh_flutter_20261116 / emulator-5570 上的 1.6.0-beta.108/code483/x86_64 Full Debug。出厂及实装 SHA-256 一致：`5436bb0af0e13354cc720e9678ebebe85c7906a792239c3fb9e954895bf1f151`。Flutter analyze 无问题，603 项通过、1 项跳过；Android 单测和 full 构建退出 0。本节只覆盖所列路径。

- B01：冷暖均经实际 Web 工具栏。Activity 首显 2.411／1.247 秒，点按到 Flutter 撤罩 8.300／6.709 秒。两轮均通过返回关闭系统通知授权窗，采样未见 ANR，历史 ANR 未归因。[入口收据](</d/dsh-native-chat-validation/code483-verified-entry-af3b9635e5-receipt.json>)
- B08b Web→原生：Web 输入框触发 `4ebdfbcf-b27a-464f-97e2-30975bd38354`，置于隔离组 `session-6bf1c254-18e9-4875-b7e1-32e67785a35a`。切换后 9.177 秒原生可交互，9.472 秒实点拒绝。自请求起 11.024 秒同 ID 唯一主动结案，工具收到非超时拒绝，回合正常完成。两端显示终态并撤卡，测试配置与转发恢复。[审批收据](</d/dsh-native-chat-validation/b08b-f35414a488-device.json>) · [原生终态](</d/dsh-native-chat-validation/b08b-f35414a488-web-handoff-native-terminal.png>) · [Web 终态](</d/dsh-native-chat-validation/b08b-f35414a488-web-handoff-terminal.png>)
- F01 语言：从 zh 切 de，回到同一原生会话后连接状态和输入提示变德语，重开语言设置仍为 de，最后经产品界面恢复 zh。此项仅覆盖已接入的核心文案。[语言收据](</d/dsh-native-chat-validation/code483-native-346856dc5f-language.json>)
- F01 权限／B09：原生 QA 组新建 blank 会话，Web 刷新前后同组均看见该 SID，Host 仍为 blank。只读选择即时写入 Host，无 turn/start，重进仍只读。变更仅限 QA 会话。[权限与分组收据](</d/dsh-native-chat-validation/code483-group-permission-3c32ffbee8.json>)
- L02／L03／L05：标准与 360 dp／1.6× 均实测账号可点开，旧说明缺席，目标高度 132／159 px，Linux 主机至 Web 行间距 11 px，尺寸和字号回读恢复。[布局收据](</d/dsh-native-chat-validation/code483-native-adafdebdea-layout.json>)

## beta106 接班检查（2026-11-23）

- 专用 AVD beta106/code481。跨端尝试在 NativeChatActivity 就绪前中止，设备记录 MainActivity 失焦等待 5002 ms、原生获焦重复超时 5014 ms，Activity 首次显示 +15s875ms。Flutter 冷初始化和遮罩 PNG 同步解码是待取证的路径。见[原生切换收据](</d/dsh-native-chat-validation/b08b-dcb7e19967-device.json>)和[运行日志](</d/dsh-native-chat-validation/beta106-native-handoff-foreground.log>)。
- Web 会话隔离在本轮 QA 草稿保护处中止。两次均未发出新请求，收据均报告 settingsRestored=true。见[Web 隔离收据](</d/dsh-native-chat-validation/b08b-03d3f1f7e8-device.json>)和[完整交接](2026-11-23-beta106-handoff.md)。

### B01 正常入口续验（2026-11-23 23:37–23:38）

- beta106/code481/x86_64，专用 AVD，PID 3553 内两次经 MainActivity 工具栏进入原生页。两轮 MainActivity 失焦后都出现系统通知授权页，通过返回键关闭。Android Activity 首次显示 +565／+765 ms，`performCreate` 392／448 ms。点按至原生获焦 4.403／4.783 s，点按至 Flutter 确认撤遮罩 4.903／5.658 s。两轮各观察 26 s，原生抽屉控件进入无障碍树，采样日志无焦点超时或 ANR。首轮 atrace 包含 Flutter 引擎附着和 Activity 生命周期，当前 APK 缺单独的 `super.onCreate`、`decodeFile` 计时及 Flutter 首帧标记。历史 +15.875 s ANR 仍待在真正冷进程复现并定位。 [启动收据](</d/dsh-native-chat-validation/beta106-entry-518e896367-receipt.json>) · [系统事件](</d/dsh-native-chat-validation/beta106-entry-518e896367-events.log>) · [首轮 atrace](</d/dsh-native-chat-validation/beta106-entry-518e896367-first-native-atrace.txt>)

### B08b Web A→B→A（2026-11-23 23:42）

- beta106/code481，专用 AVD。通过 Web 输入框在隔离测试会话 A 触发请求 `ccdc4112-6751-474e-adeb-c97c167bce7b`，用侧栏切 B。B 显示自己的消息，审批控件和 A 请求标记均未出现，B 宿主审批请求及决定共 0 条，且 A 仍待决。切回 A 后原卡再次可见。点「允许一次」后，宿主用同一 ID 唯一结案，耗时 4.858 秒，回环夹具收到 `allowed-once:`，唯一 `turn/end` 正常结束。A 显示最终答复且审批控件撤除。测试配置、凭据和转发已回读清理，保留测试会话。 [收据](</d/dsh-native-chat-validation/b08b-f6441bd69d-device.json>) · [会话 B](</d/dsh-native-chat-validation/b08b-f6441bd69d-web-isolation-foreign.png>) · [返回 A](</d/dsh-native-chat-validation/b08b-f6441bd69d-web-isolation-returned-pending.png>)

### B08b 同一待决请求接续（2026-11-23 23:44–23:47）

- beta106/code481，专用 AVD。原生输入框触发请求 `d7b5f6f1-38b0-4f47-b1e9-2392cabb72d4`，看到原生待决卡后按返回键，456 ms 回到同一 Web 会话。Web 显示同一标记并允许一次。宿主仅结案一次，ID 一致，耗时 10.877 秒。回环夹具收到 `allowed-once:`，宿主正常结束，Web 展示最终答复并撤销审批控件。反向从 Web 点原生入口用时 15.617 秒到可访问层，原生读到了同一会话及超时终态。请求在 20.006 秒被宿主取消，夹具收到 `rejected:`，活请求接续待复验。两轮测试设置回读已恢复。 [原生转 Web 收据](</d/dsh-native-chat-validation/b08b-9d468f69c9-device.json>) · [两端待决画面之原生](</d/dsh-native-chat-validation/b08b-9d468f69c9-native-handoff-pending.png>) · [两端待决画面之 Web](</d/dsh-native-chat-validation/b08b-9d468f69c9-native-handoff-web-pending.png>) · [Web 转原生超时收据](</d/dsh-native-chat-validation/b08b-004b787bcd-device.json>)

### F01 语言读写（2026-11-23 23:57）

- beta106/code481，专用 AVD。由原生抽屉进入「DeepSeek 账号 → 设置 → 语言」，点 Deutsch 后 `app_locale.locale` 从 `zh` 写为 `de`，Android 设置标题改为 Einstellungen。返回 Flutter 聊天，输入提示与连接状态仍显示中文，语言跟随判据失败。再从同一入口进入，Deutsch 显示选中。经产品控件切回中文并回读 `zh`。 [语言收据](</d/dsh-native-chat-validation/beta106-native-080648472f-language.json>) · [Android 德语设置页](</d/dsh-native-chat-validation/beta106-native-080648472f-language-android-after.png>) · [Flutter 返回画面](</d/dsh-native-chat-validation/beta106-native-080648472f-language-native-after.png>) · [再次打开语言对话框](</d/dsh-native-chat-validation/beta106-native-080648472f-language-dialog-reopened.png>)

### L02／L03／L05 窄屏大字（2026-11-23 23:59）

- beta106/code481，专用 AVD。原生抽屉和运行面板采用 1080×2340、440 dpi、字体 1.0× 与 990×2145、440 dpi（宽 360 dp）、字体 1.6× 对照。两组新建会话触控高度为 132／159 px，控件可见且有可点击语义。旧账号提示缺席，账号卡底边到屏幕底部均为 102 px，两组均实际打开账号菜单。Linux 主机行到「打开 Web 版」间距均为 11 px，两控件均有可点击语义。窄屏抽屉截图可见底层 Web 工具栏和内容，待同条件复测背景混合。设备显示尺寸、字号均已按初始值恢复并回读。 [布局收据](</d/dsh-native-chat-validation/beta106-native-6ef5836183-layout.json>) · [窄屏抽屉](</d/dsh-native-chat-validation/beta106-native-6ef5836183-narrow-large-drawer.png>) · [窄屏运行面板](</d/dsh-native-chat-validation/beta106-native-6ef5836183-narrow-large-runtime.png>)

### B07 长会话滚动和负载（2026-11-24 00:09）

- beta106/code481，专用 AVD，使用实际宿主和回环模型生成 30 轮 QA 会话。由 Web 侧栏选择该会话，点击正常入口进入原生，首屏的最新可见测试标记为第 07 轮。八次固定滑动后可见到第 13 轮。四个约 10 秒窗口在同一 PID 下采集，前台静置、滑动、滑动后静置、退后台的单核等效 CPU 分别为 102.79%、122.67%、103.46%、27.10%，Android `gfxinfo` 累计帧增量为 20、11、11、0。历史 beta99 空欢迎页为不同场景。当前数据用于定位长会话负载，Flutter 光栅帧时及真机电耗待验。测试服务商、测试 Key、ADB 链路均回读恢复，保留测试会话。 [宿主 30 轮收据](</d/dsh-native-chat-validation/beta106-long-b2d19c3850.json>) · [负载原始数据](</d/dsh-native-chat-validation/beta106-long-787a2f6327.json>) · [滚动前](</d/dsh-native-chat-validation/beta106-long-787a2f6327-before.png>) · [滚动后](</d/dsh-native-chat-validation/beta106-long-787a2f6327-scrolled.png>)

- B01 11-24 冷进程补采。启动器首次显示 MainActivity 用时 1.534 秒。关闭系统通知授权页后，脚本在 Web 仍载入历史时按工具栏原生入口。26 秒观察窗口始终为 MainActivity，系统日志无 NativeChatActivity 启动、PixelCopy 回执或 ANR。保留原 +15.875 秒样本的已知边界，冷启动转场仍未完成。 [冷进程收据](</d/dsh-native-chat-validation/beta106-entry-2201315367-receipt.json>) · [系统事件](</d/dsh-native-chat-validation/beta106-entry-2201315367-events.log>)
- B08b 11-24 Web→原生补采。请求 `d48c614d-7c6e-4e3a-8246-fa4106c1a54e` 到原生可访问层用时 9.460 秒，宿主仍待决，原生实际显示同一请求卡。脚本额外重复读取一次层级，最终宿主于 20.010 秒按超时取消，工具收到拒绝回执并继续。卡片接续可见性通过，期限内主动决策待验。 [补采收据](</d/dsh-native-chat-validation/b08b-bea593f9fc-device.json>) · [原生待决截图](</d/dsh-native-chat-validation/b08b-bea593f9fc-web-handoff-native-pending.png>)

- L06 beta106/code481，专用 AVD。在独立新会话打开原生模型菜单，实际从短名切到长名。模型胶囊右边界由 x=698 扩至 745 px。加号、权限、发送的三个控件边界全部一致，分别为 x=77–209、756–888、888–1020 px。长模型名在页面省略，模型仍可用完整语义辨认。测试配置已恢复。 [坐标收据](</d/dsh-native-chat-validation/beta106-composer-cae236ddf1.json>) · [短模型](</d/dsh-native-chat-validation/beta106-composer-cae236ddf1-short-model.png>) · [长模型](</d/dsh-native-chat-validation/beta106-composer-cae236ddf1-long-model.png>)

### F03／B08a 首轮模式锁定与流式显示（2026-11-24 00:35）

- beta106/code481，专用 AVD。从原生抽屉创建隔离空白会话，实际打开菜单看到 `standard`、`ptc`、`minimal`、`cordis` 并选择 `ptc`。切到长模型后由正常输入框发送首轮，回环服务收到完整模型 ID。在服务暂停发送后续内容、宿主尚无 `turn/end` 时，原生已显示 Item/Value 表格、两条 Amber/Jade 横向柱与停止键。继续流后正常完成，欢迎页的可编辑模式入口退出，宿主拒绝同会话再切换并回 `agent-preset/locked`。测试模型、凭据和转发均已恢复。 [原生首轮收据](</d/dsh-native-chat-validation/beta106-composer-bb57e1363b.json>) · [首轮前选择](</d/dsh-native-chat-validation/beta106-composer-bb57e1363b-before-first-mode.png>) · [首轮后](</d/dsh-native-chat-validation/beta106-composer-bb57e1363b-after-first-mode.png>) · [流式表格与图表](</d/dsh-native-chat-validation/beta106-composer-bb57e1363b-chart-streaming.png>)

- B05／B06／L01。beta106/code481，专用 AVD。从原生输入框发送，回环服务先返回 503，重试请求到达后阻塞响应。原生此时显示重试提示和停止键。两个真实只读工具依次返回，期间停按钮持续存在，赞、踩、分享未出现。唯一成功 `turn/end` 之后最终答复显示一套操作。重试观察点已出现 `llm/retry-started`，首次提示时延未量出。长文件的宿主工具输出包含末行标记。原生展开工具，预览显示截断提示，点「查看完整内容」并滚至末行标记，再返回会话。此轮验证标准屏工具卡，窄屏完整输出与独立长思考正文待验。夹具临时文件、服务商、凭据和转发已清理，保留测试会话。 [宿主与画面收据](</d/dsh-native-chat-validation/beta106-2125b3fe14-live-turn.json>) · [完整输出收据](</d/dsh-native-chat-validation/beta106-2125b3fe14-long-output.json>) · [工具输出末行](</d/dsh-native-chat-validation/beta106-2125b3fe14-long-output-tail.png>)

- B09／F01。beta106/code481，专用 AVD。原生在既有测试组 dsh-qa-beta98-alpha 内新建，会话 `session-6a198fe8-2735-4f03-addf-4e829fb6152a` 的宿主 cwd 落入该组，且保持 `blank=true`。Web 重载前后测试组都为展开状态，两次都没有该会话 ID 对应的树项。原生与宿主归组通过，Web 空会话同步未满足。该 QA 会话的权限从 Flutter 设置页选择「只读」，立即显示且重进设置页仍显示。发送前宿主权限仍为 `danger-full-access`，说明此次仅验证 UI 待应用选择，宿主立即落库判据未满足。已用另一个原生只读工具实测回合确认，发送前会为该回合设置只读。保留测试会话，ADB 转发已恢复。 [分组与权限收据](</d/dsh-native-chat-validation/beta106-group-permission-d4c1039954.json>) · [权限页重进](</d/dsh-native-chat-validation/beta106-group-permission-d4c1039954-permission-reopened.png>) · [Web 重载后](</d/dsh-native-chat-validation/beta106-group-permission-d4c1039954-web-group-after-reload.png>)

- L04／L07。beta106/code481，专用 AVD，360 dp／1.6×。原生设置中实际滚过账户、Token、模型、插件、扩展、容器、连接、语言、主题、权限和 Web 入口。当前为未登录状态，Token 紧接账号卡，登录态退出按钮位置待有登录态的专用环境验收。标准屏原生输入提示已有设备截图。尺寸切换后采到的一张输入框画面仍为 Web 底图，因此仅记窄屏设置抽验。显示参数均已恢复。 [窄屏抽验收据](</d/dsh-native-chat-validation/beta106-copy-a3995493dd.json>) · [账号与 Token](</d/dsh-native-chat-validation/beta106-copy-a3995493dd-settings-1.png>) · [设置文案](</d/dsh-native-chat-validation/beta106-copy-a3995493dd-settings-3.png>)

### beta106 续验收尾（2026-11-24）

- 11-24，专用 AVD 停机前回读 beta106/code481/x86_64、原 APK SHA-256、中文、1080×2340、font_scale=1.0 和 ADB 正反向转发各 0 条。确认专用实例停止、5570／5571 端口关闭。保留既有 WIP、草稿和会话历史。[设备收尾收据](</d/dsh-native-chat-validation/beta106-cleanup-dcc7630c03.json>)
- 设备操作结束后串行执行 Flutter analyze --no-pub 和 flutter test --no-pub --concurrency=1 --reporter expanded。两者退出码均为 0。分析无问题，全量 585 项通过、1 项跳过。设备实测沿用原 beta106 已安装包。[analyze 日志](</d/dsh-native-chat-validation/beta106-checks.VBL5X7Nf/analyze.log>) · [全量测试日志](</d/dsh-native-chat-validation/beta106-checks.VBL5X7Nf/test.log>)

## 2026-11-22 反馈对照复核（beta99 阶段）

- 范围：逐条比对用户 10:51 重贴的原文。表中 20 个不同 ID 覆盖 9 条问题（第 8 条拆为表格/图表和 CLI 审批）、7 条布局/文案要求、3 条功能分项（新对话拆为欢迎页和宿主模式）。有编号不等于有验收证据；早期小节保留当时结论，以表中现况为准。
- 指定模拟器范围内已有验收证据：B01、F02（24 个小时映射由组件覆盖，设备见一次跨整点和六卡操作）。部分设备证据：B02、B04、B07、B09、F03。只有组件/模拟输入证据：B03、B05、B06、B08a。L01–L07 已定位对应改动，但仍缺完整设备布局/文案验收。B08b 未实现所要求的链路，F01 未达到全原生完整配置。不同版本上的证据不写成 beta99 逐项复跑通过。
- 布局证据索引：[展开工具与思考](../../flutter_ui/lib/src/ui/conversation_flow.dart#L158)、[抽屉布局回归](../../flutter_ui/test/session_drawer_test.dart#L80)、[Token 与账号区顺序](../../flutter_ui/lib/src/ui/settings/settings_page.dart#L144)、[退出登录同行](../../flutter_ui/lib/src/ui/settings/settings_page.dart#L256)、[运行环境布局](../../flutter_ui/lib/src/ui/runtime_panel.dart#L77)、[输入栏触点断言](../../flutter_ui/test/composer_controls_test.dart#L120)、[输入栏新文案](../../flutter_ui/lib/src/ui/composer.dart#L227)、[模型配置 Web 回退](../../flutter_ui/lib/src/ui/settings/settings_detail_pages.dart#L217)。
- 本轮 dsh-Go 原生聊天 API 对照未找到可审计的“固定提交 / 端点 / 请求与响应 / 本项目落点”记录；现有宿主实测只证明本项目调用，不能替代对竞品接口用法的核对。
- 审计回读 beta99 APK、欢迎页截图和 F03 回读截图的 SHA-256 与交接一致；beta94/96 转场录屏、SSH 夹具日志、欢迎卡前后截图仍在。本次只核对已有证据与代码，未补跑设备链路。历史构建条目的通用输出路径是可复用目录，现存文件为 beta99，不是 beta92 历史 APK。

## beta100 图表回归、设备入口和 dsh-Go 接口对照

- B08a：生产 ConversationFlow 以前先把 chart 围栏折成代码，因此解析器测试绿不能证明聊天里图表可见。新增对话流回归在旧接线下找不到图表，修后同时看到 Markdown 表格、显式柱条图、不支持图表的原文和仍可折叠的 Dart 代码块。另三项红测分别覆盖转义竖线留在原单元格、不支持图表保留原文、chartjs 不误识别。分析无问题；串行全量 580 通过、1 跳过；Gradle debug 成功（48 秒）。日志在 D:/dsh-native-chat-validation/ 的 beta100-analyze.log、beta100-test.log 和 beta100-closure-build.log。
- beta100/code475 x86_64 SHA-256 61c3d0b40288472ddd41de3b5e7556b5f0eb59dd402e78aa32cb5ee470685637；专用 AVD install -r -t 成功，回读版本和哈希一致，正常入口显示引擎已连接（beta100-native-entry.png，SHA-256 052dc76ec899821bacc102fff4d34e2eb3f143a536b2c58d8d7ae65bcbaf9c08）。此证据仅证明装机与入口，不证明真实回复渲染。
- L02/L03：beta99 专用 AVD 已实测标准字号抽屉。新建按钮底到会话行首个可点击控件顶部 11 px（会话文本顶部 50 px）；旧提示不在新鲜层级，账号区域底边 2238 px，屏幕高 2340 px；未做大字及其他安全区对照。证据 beta99-resume-drawer.png（80e5a70a179316e46400cf0a9283f3d6f737818ac2035798b7347fe4b55c1b12）。
- dsh-Go 固定版本 a57078e842850793f1733ebd23f730a01d714e86：[会话及归组](https://github.com/ccch1mneyyy/dsh-Go/blob/a57078e842850793f1733ebd23f730a01d714e86/packages/dsh_client/lib/src/session_api.dart#L335) 调用 session/create，request 内用 workspaceId 或 cwd、可带 agentPreset，读取 sessionId/agentPreset；[工作区订阅](https://github.com/ccch1mneyyy/dsh-Go/blob/a57078e842850793f1733ebd23f730a01d714e86/packages/dsh_client/lib/src/session_api.dart#L898) 为 workspace/follow {}。与本项目 [EngineSessionService](../../flutter_ui/lib/src/bridge/engine_sessions_native.dart#L42) 对应。
- [上游普通审批](https://github.com/ccch1mneyyy/dsh-Go/blob/a57078e842850793f1733ebd23f730a01d714e86/packages/dsh_client/lib/src/session_api.dart#L676) 用当前连接的 clientId、eventId、outcome 回 $events/result，与本项目 [NativeInteractions](../../flutter_ui/lib/src/transport/native_interactions.dart#L637) 对应；不证明 B08b CLI 请求或宿主限时存在。[上游模型设置](https://github.com/ccch1mneyyy/dsh-Go/blob/a57078e842850793f1733ebd23f730a01d714e86/packages/dsh_client/lib/src/providers_api.dart#L1) 使用 settings/mutate、credentials/set（记录引用而非密钥）和 llm/discoverModels；其验证版本为 DSH 0.2.1-rc.2，当前 Android 宿主尚需确认契约，不复制代码/UI。

- L06/L01/L02/L03/L05 大字：beta100/code475，专用 AVD 把系统 font_scale 从 1.0 临时设为 1.6，随后在 finally 恢复 1.0 并读回。1080×2340、440 dpi：发送/权限/模型/加号仍在同一输入栏可点击范围；长模型选项菜单当时为空，所以未能执行换模型锚点对照。抽屉新建仍可点、DeepSeek 账号仍可见；运行面板 Linux SSH 行和 Web 入口都可点且未越界。字体放大使行高按内容增长，界面可能比标准字号更拥挤；未测 360 dp 窄屏和嵌入 Android 页面。截图与哈希：beta100-large-font-home.png 02cc7c97fc1e46698ab17f36e484918c66fa7031e0f8d4d6441339f69eaba832；drawer 5264fda584650fcc434effeabe21f73feaee9479f000227d5d6c9ae70e36fa15；panel 61a7ff69e8cbd8630e95b0db79ab7e25c3f39ecb920134d79afd17c2490fbdc7。UIAutomator 在 Flutter 页面间歇返回空树，回读均用重新 dump 的 XML；stderr 有一次 transient null-root。
- B04：beta100/code475 专用 AVD 从抽屉 → 账号 → 设置实际点按。连接与远程、个性化主题、权限管理及模型配置停留于各自原生子页并返回；诊断与日志、关于、备份与恢复、扩展编译环境、容器环境选择、插件管理、语言进入对应 Android 页面并返回。诊断页曾被通知权限弹窗覆盖，关闭弹窗后确认 LogViewerActivity 在前台；未修改通知权限。第 12 项「切换 Web UI」进入 MainActivity，再从正常入口返回 NativeChatActivity。完整模型配置仍标示 Web，未把打开路由计为 F01 读写通过。
- L05：beta100 标准字号、440 dpi，Linux 主机行底边 2137 px，「打开 Web 版」顶部 2148 px。截图 beta100-runtime-panel.png，SHA-256 293320fffef0a65cbb4cbc6e4f8f5b325eaecb09f3203630fe29461e173ce2f7。
- B08a 真实宿主回读：专用 AVD 临时注册仅接本地回环的确定性模型，以正式 session/prompt RPC 向隔离会话提交，宿主记录包含表格和 chart 围栏的 assistant/message 与单一 completed turn/end。随后从原生抽屉刷新并打开 QA Native Chart，实际显示 Item/Value 表头及两根条形图。截图 beta100-real-host-chart.png SHA-256 50cc1135d0bf40a5ad74f80de95c65a8a54884ec03947ec521e3adce92cc5aa5。未证明原生输入框提交、生成过程中的显示或外部模型。临时路由、随机测试凭据和转发已撤销，测试会话保留。
- F01 契约实测：Android Host settings/describe 返回 22 个命名空间；llm-pi-ai 的带 revision 的 settings/mutate 和测试凭据写入与撤销已通过回读。这是正式 RPC 可用的证据，不代表原生设置 UI 已实现。

- B05：beta100，真实 Android Host 接本地确定性服务。两条隔离会话各只产生一次 llm/retry、一次 llm/retry-started 和一次 turn/end：首条先 HTTP 503 后成功，次条两次 503 后以 error 结束。原生历史中成功会话只有一条重试提示和一套回复动作；失败会话只有一条重试提示和一张失败卡，展开有 SERVER 详情和一个复制详情按钮，无成功动作。截图 beta100-retry-recovered.png（ebf2a6ae6c6d7b55b104db632772a1752cc83cc0bbefd317b533aebd37ad8592）、beta100-retry-terminal-expanded.png（7db470129140f9b69c8bfd0082d000011d358fa5fbf18ecb264041d32fd91638）。未测重试等待期的实时画面及波纹裁切。测试 provider、凭据及转发均撤销。

## beta103 原生模型服务商管理（2026-11-23）

- 三条新增 widget 回归先失败，补齐编辑、确认移除和发现模型后通过；设置相关测试共 16 项通过，`flutter analyze --no-pub` 无问题，串行全量 586 项通过。[全量日志](</d/dsh-native-chat-validation/beta103-f01-test.log>)。
- Gradle `:app:assembleDebug` 成功（53 秒）。beta103/code478 x86_64 [测试 APK](</d/dsh-native-chat-validation/dsh-mobile-1.6.0-beta.103-x86_64-debug.apk>) SHA-256 `ce985bac85ade6d48d79e37e8927b387d90a1e861adfd073e6750f7c6a4f0c34`，仅专用 AVD 保数据安装并回读同一哈希。
- 普通原生设置入口完成发现、保存、编辑、重进回读和确认移除。回环服务收到一次经认证的 `GET /v1/models`。Host 回读确认高级选项、模型元数据和凭据引用保留。测试配置恢复，独立测试凭据及 ADB 转发撤销。[收据](</d/dsh-native-chat-validation/beta103-f01-59f3c1e0.json>)、[编辑回读画面](</d/dsh-native-chat-validation/beta103-f01-59f3c1e0-reopened.png>)。

## beta102 回合状态复验（2026-11-23）

- beta101 中途露出回复操作的设备红证据见 B06。beta102 修复将动作资格与同一回合的 `turn/end.reason.kind=completed` 绑定；不是按单段已落盘、正在重试、宿主空闲或工具结果来判断。组件红绿覆盖历史回复、快照重开、中间答复和最终答复。
- [实时轨迹](</d/dsh-native-chat-validation/beta102-c7678f2663-live-turn.json>)：从原生输入框发送，实际 Android Host 接本地确定性服务；重试中的 Stop=1，两次工具各返回后仍为 1，三阶段赞/踩/分享均为 0。[工具二返回](</d/dsh-native-chat-validation/beta102-c7678f2663-after-tool-2.png>)、[完成截图](</d/dsh-native-chat-validation/beta102-c7678f2663-finished-turn.png>)。首次终态取样被键盘缩小的视口遮住，确认宿主已完成后移到最后一条可见回复，再按同一路径复跑，退出 0。临时服务商、测试 Key、样本文件和 ADB 转发均撤销，保留测试会话。
- `flutter analyze --no-pub` 无问题；`flutter test --no-pub --concurrency=1 --reporter expanded` 583 项通过；Gradle `:app:assembleDebug` 成功（1m45s）。beta102/code477 x86_64 [测试 APK](</d/dsh-native-chat-validation/dsh-mobile-1.6.0-beta.102-x86_64-debug.apk>) SHA-256 `16046939a595adb63384e55df14c3777cc3282a6a889cf424ce5c6616fa2b2fc`，专用模拟器保数据覆盖安装并读回同一哈希。未操作真机，未调用外部模型。

## beta101 原生模型配置与模式锁定（2026-11-22）

- beta101/code476 x86_64 debug APK SHA-256 `17a04c2b1b34c0b44bc875c8417c030d1af608b736e6df5d82c50c36e704e738`，安装到专用 AVD `dsh_flutter_20261116` / `emulator-5570` 后版本、架构和设备 APK 哈希一致；analyze 无问题，全量串行 581 通过 / 1 跳过；Gradle `BUILD SUCCESSFUL in 1m 54s`。
- F01：输入栏「配置模型」与设置「模型配置」共用原生页。实测新增 `qa-beta101-native`、回环 URL、`qa-beta101-model` 和测试 Key，保存后从抽屉重进设置仍显示 provider/model；截图 `beta101-provider-reopened.png` SHA-256 `7f3439e1276ac3df2b81842e655c3efbcd8c8f31385d3ce53431c55f87a2dab3`。Key 仅通过 credentials RPC 写入并以 configured 元数据核对，未读取明文；测试 provider、Key、转发已撤销，其他 provider 不变。编辑、删除、模型发现和其他 namespace UI 仍未完成。
- F03：对已完成测试会话 `session-864be274-8c68-423f-8602-51c0d20a9141` 调用 `agentPresets/select`，Host 返回 `agent-preset/locked`；这验证首轮后的 Host 锁定契约。尚未取得首轮前设备选择和首轮后控件灰化的完整视觉证据。

- B03/B05：beta101 专用模拟器录屏 `D:/dsh-native-chat-validation/beta101-expanded-marquee.mp4`（720×1560，580 帧，19.45 秒，SHA-256 `f7a96e27d4beed44b8e026a7ecadc666eb9ba64e9a35f17e9f94785a9b3c8965`）。以 20 Hz 分析标题字形投影，两个停顿约 1.55/1.45 秒，观察到的位移均向左。展开错误卡比较录屏 2.0/3.0 秒：卡内左侧样区 1693/1694 像素有至少 8 灰度级变化，中位数 +44；卡外左侧与上方合计 1858 个采样像素均未达到该阈值。仅证明本场景及采样区域；实时重试提示待验。短标题 QA Native Chart 在同机静置 6.72 秒取 10 次原始截图，标题区域字形掩码哈希 10 次一致。短标题录屏仅产出 3 帧，不作为对照证据。

## 专用模拟器补充（2026-11-22 03:55-04:10）

- AVD 身份：dsh_flutter_20261116 / emulator-5570 / 1080x2340；只执行 install -r -t，无清数据。安装前 beta89 APK 已备份为 D:/dsh-native-chat-validation/beta92-avd-acceptance.9dLMLj/before-beta89.apk，248370758 B，debug 证书 SHA-256 4dcec87d…。本轮 x86_64 debug APK 证据见上方集成条目。
- 正常 MainActivity 启动耗时 981ms，Web 历史内容与输入区可见；点击原生切换入口后录制 D:/dsh-native-chat-validation/beta92-avd-acceptance.9dLMLj/web-to-native.mp4（720x1560，11.980622s，258 帧），settled 截图 native-settled.png，contact sheet transition-contact.png。录屏可确认 Web 内容保留、原生页面随后淡入；正文模板测量只覆盖了录制开始阶段，未形成完整位移对照，因此 B01 仍待严格结论。
- 抽屉实际语义包含 DSH Mobile、native-ui-check、未分组、新建会话和账号入口；进入「模型配置」后真实停留，显示当前会话模型 gpt-6-luna 及「完整模型配置」Web UI 降级说明。该证据只证明路由/可见入口，不证明设置数据桥已实现。


## 本轮新鲜验证（2026-11-22 08:58-09:20）

- 代码基线：`feat/flutter-native-chat` / `7d9b26df`；未改主仓库、PLZ110 或 emulator-5554。
- 新鲜门禁：`flutter analyze --no-pub` exit 0，日志 `D:/dsh-native-chat-validation/beta92-verify-analyze.log`，输出 `No issues found! (ran in 71.2s)`；串行全量 `flutter test --no-pub --concurrency=1 --reporter expanded` exit 0，日志 `D:/dsh-native-chat-validation/beta92-verify-test.log`，末尾 `+575: All tests passed!`。
- 新鲜构建：Gradle x86_64 debug exit 0，日志 `D:/dsh-native-chat-validation/beta92-verify-build.log`，`BUILD SUCCESSFUL in 1m 37s`；APK `D:/dsh-native-chat-validation/beta92-feedback-emulator-build/app/outputs/apk/debug/app-debug.apk`，SHA-256 `7ee791b0f23fca106bfe6afb14e09ffcd4d9749e988b8efefaa67f9602d75ab4`，versionCode 467/versionName 1.6.0-beta.92/native-code x86_64，debug 证书 `4dcec87dbb07395cb65e63c72af6754d7b0ad24ed5c9e5660cecea2051579585`。
- 专用 AVD：`dsh_flutter_20261116` / `emulator-5570`；`install -r -t` 返回 `Success`，回读 `versionCode=467`、`primaryCpuAbi=x86_64`。冷启动先进入 OnboardingActivity；通过真实点击完成 1/7 至 7/7，未清数据、未使用 intent 绕过。step5/6/7 截图哈希分别为 `8c1751ce3569d75731a4db90325bf664490c1b21e5adbf670fe07d8e97e403a3`、`d154d0cd51c1279323de6ef1b0f3d23adf434a133bdc5912f669b2a736c3a169`、`40b7e684ddfb4c9ff0a206c18cb759b48584338c1d5a708dcf20c8125bda6775`，文件在 `E:/tmp/native-chat-web-preview/android-evidence/`。
- 正常入口：完成引导后 MainActivity/WebView 可见；关闭预览提示后点击 Web 工具栏「原生对话」，原生页真实出现。截图 `beta92-verify-native-entry.png` SHA-256 `51e7848935a6cc612cefb7831ae3438fcfd5e447c30ce3c9c9abd7416e538508`，可见六个欢迎草稿入口、`对话模式：standard`、Bionic Node 已连接、模型/权限/发送区域。
- 抽屉与设置：点击「打开会话抽屉」真实打开，显示 DSH Mobile、新建会话、会话区、账号入口；截图 `beta92-verify-native-drawer.png` SHA-256 `5a20d26c8640188162bf1e6383c050ba492d2428ab22b6eaae7aa7e9f679325a`。点击账号入口后真实展开「设置/登录 DeepSeek」菜单，再点「设置」进入设置页面；截图 `beta92-verify-settings.png` SHA-256 `0a4f0971612514f7a2345569ff3c71bfdde7f6107e51f2ab687a06c3fc8ad9ab`，页面可见运行与能力、模型配置和插件管理入口。
- 分项判定：B01 仍「证据不足」（本轮未做完整逐帧位移测量）；B02/B05/B06/B07/B08a/B08b/B09/L01/L04/L05/L06/L07/F01 仍未完成真实设备级验收；B03 只有组件证据；B04、L02、L03、F02、F03 为「设备入口/可见性部分验证」，不等于全部交互或持久化通过。

## 欢迎页补验（2026-11-22 09:33）

- F02：组件回归在 390 dp 下测得第一张卡的内容整体偏左 29.75 dp；调整后六张卡的图标与文字占位中心和卡片中心在横纵两轴均相差不超过 0.5 dp，点击仍只填草稿而不发送。
- F03：上方截图中的 `standard` 是该会话当时显示的选中值，不是固定选项；UI 使用宿主返回的菜单项。组件测试实际点开菜单、更换选项并验证 RPC 参数、宿主确认后的显示和首轮锁定。`chat_screen_test.dart` 与 `engine_home_test.dart` 串行共 19 项通过。未重建或安装 APK，模拟器宿主模式目录和新布局仍待设备侧补验。

## beta93 欢迎页真机验证（2026-11-22）

- beta93/code468 真机验收（2026-11-22）：ARM64 release `:app:assembleRelease` exit 0（3m54s）；签名与 PLZ110 现装包相同。APK `D:/dsh-native-chat-validation/dsh-mobile-1.6.0-beta.93-arm64.apk`，163840392 B，SHA-256 `4a7b95810146c1a719a3b022936d5487c5bd5585b93e4f8f7966529af5f8cf0d`。旧包备份 `D:/dsh-native-chat-validation/plz110-beta93.dtNrjl/before-beta92.apk` 与现装 SHA-256 `4d7308254f640523b5e026bb66057eb88341c06099402c79f052644966ea0cb8` 一致（不含应用数据）。
- PLZ110 序列号 `3B164M0105000000`：9000 直装无结果、已取消本轮任务；改用分步 `adb push` 在 450s 超时（exit 124），中途测得 86114304 B，超时后暂存路径已不存在，未执行暂存包安装；mDNS 广播的 TLS 端口 34147 连接被拒（10061）。最终回读仍为 1.6.0-beta.92/code467；未卸载、未清数据、未启动应用测试。下一步用 USB ADB 或在手机上手动覆盖安装已校验 APK，再验新对话六卡对齐、点卡填草稿不发送、模式菜单展示及首轮前可选。

- F02/F03：真机未验；以上不覆盖 09:33 的组件测试结果。构建日志：`D:/dsh-native-chat-validation/beta93-arm64-build.log`。

## beta94 专用模拟器验收（2026-11-22）

- beta94/code469 专用模拟器已安装（x86_64，SHA-256 `79525bf5d48b4bb9da7e60030cb24861b52bc7999b19b8f17767024e0dcbebc5`）。B02 的 Linux SSH 入口已与本机隔离 Paramiko 服务实测：验证指纹与密码、执行固定样本得到 stdout `local-ssh` / stderr `diagnostic` / 退出码 `7`、断开后执行控件消失；错误指纹没有触发认证，正确指纹配错误密码有认证但未执行。仅证明此测试服务，未验证实际 Windows 主机。F02 实测六卡可见及「轻松聊天」只填草稿；F03 真实宿主菜单有 `standard`、`ptc`、`minimal`、`cordis`，成功从 standard 切到 ptc；24 小时、其他入口、持久化和设备首轮锁定仍未全验。
- 取证目录 `E:/tmp/native-chat-web-preview/android-evidence/`：`beta94-ssh-command.png` / `beta94-ssh-disconnected.png` / `beta94-ssh-bad-pin.png` / `beta94-ssh-bad-password.png` / `beta94-mode-open.png` / `beta94-mode-ptc.png` / `beta94-starter-draft.png`。服务日志 `D:/dsh-native-chat-validation/beta94-ssh-fixture.log` 顺序为 ready、auth、exec、closed、closed、auth、closed；已移除专用 reverse、停止测试服务。

## beta95–96 专用模拟器验收（2026-11-22）

- B01：beta94/code469 的录屏（720×1560）中，两个稳定 Web 锚点均上移 22 px，6 个高置信帧后回正。beta96/code471 的 `TYPE_APPLICATION_PANEL` 从 Android 30 起不再适配系统栏 inset；同一专用 AVD 正常入口录屏 142 帧，两个锚点在 120 个相关性 >0.90 的帧均为 0 px，剩余混合帧低于阈值不计位置；亮度标准差 <4 的空帧为 0，混合画面窗口约 821 ms，不等于冷启动时长。对照视频 `D:/dsh-native-chat-validation/beta94-closure-transition.mp4` 与 `beta96-closure-transition.mp4`。Android 握手契约红测 8 项中 1 失败，修后 8/8；Gradle 构建与专用 AVD 安装成功，APK SHA-256 `fff22b74234438c07174222c36ee3ab59af3ca80860e22ebe0afdd67fcf50a39`。本判定限此模拟器、这一入口和画面；旧 Android、真机及其他画面仍未测。
- B04/B02：beta95/code470 中，设置 → 连接与远程 → Windows/Linux 已通过真实点按进入各自的原生 SSH 表单并返回（`beta95-settings-windows-ssh.png`、`beta95-settings-linux-ssh.png`）；此结果不是实际连接 Windows，也不代表全部原生设置的数据读写通过。

## beta97 设置路由实测

- B04/F01：beta97/code472（x86_64，SHA-256 `5969cf6f7f526d91a82145a9ccd050c2aaa54166cfc13a80298ba28854cb4a06`）修复抽屉设置的七处占位路由。修前回归抓到插件入口未调用 `openSettingsSection`，修后组件测试 5 项通过。专用 AVD 实际打开并返回：插件市场、扩展中心、Linux 容器、语言、日志、备份与恢复、关于。语言原生弹窗需要先关闭弹窗，再返回 Flutter 设置；旧自动化只按了一次返回，续验核对了实际 Activity。未修改选项、安装插件或导入备份，不据此判定 F01 完成。

## beta98 欢迎语整点实测

- F02：beta98/code473，x86_64 APK SHA-256 `cbdb543e67ddf860bb29bde49e9932aff191ec2d20ead54e32e69fecdb85c505`。欢迎页新增前台整点唤醒，退后台和卸载组件时取消计时，恢复前台按本地时间刷新。修前 23:59:59→00:00 测试失败，修后覆盖 24 条不同文案。专用 AVD 保持页面打开，05:59:22 为「给新的一天起个头」，06:00:14 为「早安，今天想做什么？」；截图 `beta98-welcome-post-boundary.png`、`beta98-welcome-hour-final.png`。本快照 Flutter analyze 无问题、串行全量 576 项通过（`beta98-analyze.log`、`beta98-test.log`），Gradle 构建及专用 AVD 安装 exit 0。

## beta98 工作区归组实测

- B09：beta98/code473，专用 AVD 通过 Web 产品界面登记两个空测试工作区 `dsh-qa-beta98-alpha`、`dsh-qa-beta98-bravo`。原生抽屉排序与宿主一致（bravo 在 alpha 前）；原生组内新建前 bravo/alpha/未分组各 1 条，依次点 alpha、bravo 的组内入口后为 1/2/1、2/2/1。宿主登记只读核对两个测试组（仅输出名称和去重计数），各 2 条。证据 `beta98-b09-native-groups.png`、`beta98-b09-native-after-alpha.png`、`beta98-b09-native-after-bravo.png`。Web 回切抽屉仅确认两组顺序，未证明实时同步完整空会话列表；不覆盖这项。未发送消息，保留测试组和会话记录，不直接改宿主登记。

## beta98 原生外观持久化实测

- F01 外观子项：beta98/code473，专用 `dsh_flutter_20261116`。从标准/深色/雾灰实际点按切到紧凑/浅色/暖砂，三项从 `native_chat_ui` 回读为 `compact/light/sand`。退出到 MainActivity 再走正常入口，NativeChatActivity 实例记录从 `5cf479b` 变为 `dae0768`；重进外观页三值不变。截图中同一背景像素为 RGB(248,245,239)，显示大小标题高度 65 px；恢复原值后为 RGB(21,26,25)、71 px，和初始截图一致。证据在 `E:/tmp/native-chat-web-preview/android-evidence/beta98-appearance-{pre-change,changed,reopened,restored}.png`，重进截图 SHA-256 `b9c40a52440f0b717635fbbfd0c194035da395eda37ec8405a5640008cd5312f`。通过产品界面恢复原有效值，无数据清理；不代表模型、权限或其它管理项的持久化通过。

## beta98 欢迎卡真实设备交互

- F02 六张快捷入口实测：beta98/code473，专用 AVD 新会话初始草稿为空。真实触点坐标分别落在 3×2 网格：x=[50,529]/[551,1031] px，y=[867,999]/[1021,1153]/[1175,1307] px。依次点「无障碍操作、编写代码、构建和调试、轻松聊天、理解一个概念、整理计划」，输入框逐项精确等于各卡文案以双换行连接；每步六张卡、模式按钮仍在，未触发发送/离开欢迎态。测后仅将本轮生成的草稿在原输入框清空并验证空值。证据 `beta98-starters-before.png` SHA-256 `2e3a8f6147d5cc9cc9e416c24896795d2aeec40980bfe52df96b75c60ba5bcd7`；填入终态 `beta98-starters-filled.png` SHA-256 `f7a4a8c4fdcf2b673fa0d082448927261e941f36fc87a08c050ec2eed3bbb2bb`。

## beta98 宿主模式重进验证

- F03：beta98/code473，专用 AVD 的真实宿主目录为 `standard/ptc/minimal/cordis`。空会话菜单点选 `ptc`，经「打开 Web 版」并再次走原生入口后，NativeChatActivity 从 `dae0768` 换成 `85532ff`，模式仍为 `ptc`；随后通过原生菜单恢复 `standard`。重进截图 `beta98-f03-reopened.png` SHA-256 `fe0a4258cbfcb070730615d6cefd01762e28334328dffe5a9050061393655e11`。未发送消息；设备端首轮后的锁定未验。

## beta99 宿主轮询与静置负载

- B07，2026-11-22 16:40：专用 AVD `dsh_flutter_20261116` / `emulator-5570`，空白欢迎页、引擎已连接。beta98/code473 前台两轮约 10 秒的单核 CPU 占用为 35.95%、34.36%，Android gfxinfo 累计帧增量为 30、35；后台为 2.78% / 0 帧。beta99/code474 在同一 AVD 同页面复测为 3.48%、3.38% / 0、0 帧，后台 2.98% / 0 帧。每轮首尾核对前台 Activity、进程 PID 和启动时钟；CPU 用 /proc/PID/stat 差值按 CLK_TCK 和 /proc/uptime 换算。两个版本间有重新装机及进程重启，结果仅代表本次模拟器静置采样，不代表真机续航；长会话滚动尚未测量。
- 回归：修前每 2 秒查询到相同宿主状态也重建原生聊天；新增测试“identical Host polls leave the chat tree untouched”修前失败，修后验证相同状态不重建且真实状态变化仍更新。未关闭宿主轮询或 WebView 保活。Flutter analyze 0 问题；全量 576 通过 / 1 跳过；Gradle :app:assembleDebug exit 0（2m14s）。
- 产物：beta99/code474 x86_64，SHA-256 `e134be5a7e83c1a058a31f57c351aeb618632864eb0b1f34ad1de7fd5f8b8c4c`；仅专用 AVD install -r -t 成功并回读版本。证据：D:/dsh-native-chat-validation/beta99-{analyze,test,closure-build}.log；截图 beta99-idle-welcome.png（9351d4ef01f4ada47fa6ac970801f22217c8c0f0c9ed88ecf8694c65805625ec）。

## 阶段证据

- B01/B03：标题 fallback 不再截成 24 字；跑马灯两空格、1.5 秒停顿不占用 ticker，后台暂停；淡入约 1 秒，三重门仍在。修前测试分别出现标题截断、停顿位置偏移 13.97dp 和一秒后遮罩仍为 0.344。修改后 chat_screen、launch_fade_handshake、overflow_title 合跑 31 项（当时快照）通过；Android 实际录屏未验，Web 位移仍待测。
- B02/L05：宿主回环 Paramiko 验明错误指纹先于密码被拒、错密码被拒、正确连接回读真实 stdout/stderr/退出码，支持断开及有界输出；原生 Windows/Linux 入口与间距通过组件检查。依赖 dartssh2 4.1.0（MIT）；不是 HardSSH 的宿主别名管理。模拟器内运行仍待验证。
- B04/L04/F01：组件走抽屉→账号→设置→搜索，11 个非 Web 路由可停留返回；退出同行、Token 上移。缺数据桥的管理页未完成，不能把停留成功算成功能完成。
- B05/B06：projection/RPC/flow 41 项通过；已验证重试状态与整轮完成判据的组件逻辑。错误表面 Clip.none 真红，裁边后 turn_notice_card 8 项通过。真实引擎的连续工具轮次仍待验。
- B09/F02/F03：组件验到宿主分组顺序和按工作区创建、空会话模式读回/提交/首轮锁定、六个入口填入而不发送且保留已有草稿。
- 集成：Flutter 全量 575 项通过（D:/dsh-native-chat-validation/beta92-feedback-test-r2.log，03:08 exit 0）；analyzer 结果沿用本轮无 issues。Android x86_64 debug 构建在 3m37s 完成，APK D:/dsh-native-chat-validation/beta92-feedback-emulator-build/app/outputs/apk/debug/app-debug.apk，SHA-256 7b7f0fb65ae1147c6b42b0a5886ba60143df4ed1996514885f035863ff7e7835，versionCode 467/native-code x86_64；仅 install -r -t 到专用 AVD dsh_flutter_20261116（emulator-5570），未操作共享 emulator-5554、PLZ110 或主仓库。安装前包已备份到 D:/dsh-native-chat-validation/beta92-avd-acceptance.9dLMLj/before-beta89.apk，248370758 B，debug 证书 4dcec87d…。正常 MainActivity 入口启动成功，截图 main-first.png；Web→原生已录制 web-to-native.mp4（720x1560，11.98s，258 帧）并抽帧 transition-contact.png。录屏可证 Web 内容持续存在、随后原生淡入和 settled 状态；当前帧测量仅显示录制开始的正文位置保持不变，尚未建立完整无位移判定，B01 仍待严格逐帧验收。抽屉标签显示 DSH Mobile、工作区 native-ui-check/未分组、账号入口；设置模型配置页真实停留并显示当前模型与 Web 配置降级说明。
