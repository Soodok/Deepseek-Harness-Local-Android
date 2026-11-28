# 原生对话反馈逐项实现与验收计划

> 按 subagent-driven-development 或 executing-plans 分批执行。每项独立记红灯、修复、绿灯和 Android 实测，不用安装、入口或组件证据互相替代。

**目标：** 完成 2026-11-22 用户重贴的反馈；20 个台账 ID 全有判定和证据。

**架构：** 宿主负责会话、工作区、模式、配置及审批状态，原生和 Web 只投影权威结果。客户端独立实现 UI；参考 dsh-Go 时只记录可验证的协议事实，再以实际宿主核对。先跑已有的真实路径，复现失败后才修改对应模块。

**边界：** 隔离树 feat/flutter-native-chat / 7d9b26df 加既有 WIP；只操作 dsh_flutter_20261116/emulator-5570。单次只运行一个构建或 Flutter 套件，保留现有历史；测试数据仅用独立工作区/会话、已有无凭据夹具和独立服务，不调用真实付费模型。设备结论绑定代码版本、APK 哈希、入口、输出或录屏。

## 分批执行

- [ ] **A，转场 / 标题 / 布局（B01 B03 L01–L07）**：检查 MainActivity 的 captureWindowBackdrop 和 NativeChatActivity 的握手，以及 launch_fade.dart、overflow_title.dart、conversation_flow.dart、session_drawer.dart、composer.dart。录制同一 Web 画面真实进入原生，跟踪两个静态锚点 Y 位置至遮罩淡化，报告最大位移、异常空帧及耗时；录下长标题至少两个周期，测方向、实际循环间隔及约 1.5 秒停留，另跑短标题对照。记录展开工具/思考、抽屉、账号、设置、运行环境和输入区截图；标准与窄屏大字对照，逐项核文字、间距、触达与固定按钮位置。失败后放大原始症状写回归，再最小修复。
- [ ] **B，原生设置（B04 F01）**：settings_home.dart / settings_detail_pages.dart / engine_sessions_native.dart / host_bridge.dart。逐项进入所有 12 个设置目的地，检查停留及正常返回；“打开 Web 版”本身是明确独立入口，不用于托管其他设置项。为每个可修改项记录读取端、写入端、锁定原因、刷新/重进后的值；测试只改样本值并恢复。管理项占位、跳 Web 或无持久化均记未完成，按可验证的客户端安全 RPC / Android bridge 逐项接通，不把提示当功能。
- [ ] **C，SSH（B02）**：manual_ssh_page.dart / manual_ssh_service.dart。启动已有 manual_ssh_server.py 回环夹具；专用 AVD 只接夹具。实际窗口依次核错误指纹不触发认证、错误密码不执行、正确指纹及认证后执行 fixture-small，核对 stdout、stderr、退出码 7，断开后无法执行。Windows/Linux 两入口共享协议，但夹具成功不代表真实 Windows SSH 主机兼容。
- [ ] **D，真实回合与富文本（B05 B06 B08a）**：dsh_session_projection.dart / native_session_transport.dart / turn_notice_card.dart / conversation_flow.dart / assistant_rich_text.dart。以真实宿主连接测试用的确定性应答服务，产生重试后成功、最终失败、同轮多段思考和工具，以及普通 Markdown 表与图表。记录运行中无终态按钮、终态只有一套操作、重试立即可见、展开水波纹在卡内、失败降级保留可读信息。用例需要真实客户端到宿主的投影，不只直接渲染 Flutter 样例。
- [ ] **E，权限申请（B08b）**：核宿主的普通审批契约和 CLI 接线是否存在。若无端到端实现，补宿主一次性权限请求的防御性流程：身份绑定会话，默认拒绝，真实用户决策才可批准，不允许调用者自行提权；两个 UI 共用一个请求 ID。不使用真实危险权限或外部目标，使用无副作用测试操作验证同意、拒绝、20 秒超时、跨会话拒绝、不可重放、拒绝后回合继续。普通审批卡存在不算通过。
- [ ] **F，分组与新建（B09 F02 F03）**：engine_sessions_native.dart / engine_workspace.dart / session_drawer.dart / message_list.dart。使用真实宿主建立两个临时工作区，核 workspace/follow 排序、各自新建会话后的归属、原生与 Web 一致；欢迎页验 24 小时文案映射、六卡三排两列与居中、点卡只填草稿不发；模式菜单核宿主目录多项可选、选后回读以及首轮锁定。缺具体模式时记录当前宿主目录，不编造选项。
- [ ] **G，运行负载（B07）**：相同产物与设备状态，记录前台静置、后台静置和固定滚动三组 CPU、帧和周期任务；区分模拟器代理指标和电池续航；先确认热点再动订阅、计时器或渲染。修复前后重复同样测法。
- [ ] **集成门禁**：串行 flutter analyze --no-pub、flutter test --no-pub --concurrency=1；构建签名核验后的 x86_64 debug APK。通过正常 Android 入口逐项复跑，记录未实现 / 组件通过 / 模拟器实测通过 / 实测失败 / 环境不支持，不以总测试数代替 20 项。至少独立复审一次主要改动；不代提交已有 WIP。

## 当前基线

旧 beta92 仅证实启动、引导、Web→原生进入、抽屉和设置入口，未证实转场全过程。欢迎页修改后仅有组件验证；beta93 ARM64 已构建但未装上真机。F01 有原生路由占位，B08b 未见已验证的 CLI 入口，B07 尚无受控基线。其余分项见反馈台账，全部不得视作设备验收通过。
