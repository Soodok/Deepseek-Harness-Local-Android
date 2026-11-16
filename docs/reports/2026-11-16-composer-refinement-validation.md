# Composer 细化与真实模型验收报告（beta.35 / code410）

分支 `feat/flutter-native-chat`，隔离树 `E:/tmp/wt-flutter-native-chat`，基线 `831bd414`（beta.34 / code409）。
主仓库 `E:/Deepeek harness/dsh-android` 未写入；设备只用 `emulator-5570`（AVD `dsh_flutter_20261116`，Android 34 x86_64）。

## 1. 结论摘要

| 规格 | 结论 | 主要落点 |
| --- | --- | --- |
| 1 主会话入口 / 子会话返回 | 主会话左上角改为会话抽屉入口，真实子会话仍为返回箭头；子会话不提供模型选择 | `chat_shell.dart`、`session_drawer.dart`、`engine_sessions_native.dart`、`subagent_panel.dart` |
| 2 模型胶囊几何 | 34dp 视觉表面 + 48dp 触控区，与 48dp 发送键区分；长名省略号、左右对称 12dp | `composer_surface.dart`（`ComposerModelPill`） |
| 3 玻璃实现 | 查明 DSH Go 为自绘玻璃（无第三方库、AGPL-3.0），改用 Flutter 官方 API 自绘，移除 `liquid_glass_widgets` | `glass_panel.dart`、`main.dart`、`pubspec.yaml` |
| 4 子代理入口 | 空目录完全不布局；出现真实子会话后显示；重连回空快照再次隐藏 | `chat_shell.dart`（`collectSubagents`） |
| 5 发送/停止同槽 | 单槽位互换、清稿回停止、空闲禁用、停止防连点、失败保留错误可重试 | `composer.dart`、`composer_surface.dart`、`chat_controller.dart` |
| 6 真实模型验收 | 真实 gpt-6-luna 链路可用并完成短回复；只读工具与取消的真实行为见 §6 的实测记录 | 设备 profile + 报告证据 |

## 2. 规格 1：主会话入口与子会话返回

- 根因：`ChatShell` 有 `onBack` 参数，`EngineHome` 用 `_showList` 接上它，于是主会话顶栏渲染了返回箭头（`arrow_back_rounded` + tooltip「返回会话列表」）。
- 现在：`ChatShell` 删除 `onBack`；顶栏固定为菜单图标（`session-menu`、tooltip「打开会话抽屉」）打开 `SessionDrawer`。会话列表入口在抽屉里，Android 会话页标题为「会话列表」（fixture 预览仍为「连接本机引擎」）。真实子会话标题栏在 `SubagentChatPage`，仍是返回箭头，未改动。
- 子会话不选择模型：`SubagentChatPage` 从不渲染 `composer-model`，也没有 `selectModel` 调用；新增只读展示 `subagent-model-readonly`，值来自 Host 的 `modelSelection.next.model`（`native_subagent_transport.dart` 新增 `_modelOf`，读取失败返回 null 而不是让子会话失败）。
- 测试：`test/top_bar_entry_test.dart`（无返回箭头、菜单开抽屉、抽屉含会话列表、子代理入口开关）；`test/subagent_panel_test.dart` 新增「child shows the inherited model read only and cannot change it」，并保留原「无 composer-model / 无 composer-stop」断言；`test/engine_home_test.dart` 改为走菜单→抽屉→会话列表。

## 3. 规格 2：模型胶囊

- `ComposerModelPill`：外层 48dp 触控区（key `composer-model`），内部 `Material`+StadiumBorder 表面 34dp（key `composer-model-surface`），`StadiumBorder` 描边、12dp 对称内边距、`height:1` + 均分行高实现垂直居中、`maxLines:1` + `TextOverflow.ellipsis`、文字缩放夹到 1.2 倍。
- 发送键仍 48dp，因此胶囊视觉比发送键更轻，但触控面积不缩水。
- 测试：`test/composer_action_matrix_test.dart`（表面 32–36dp、外层 ≥48dp、小于发送键）、`test/floating_runtime_test.dart`（`shell` 几何 + 表面区间 + StadiumBorder 形状）、`test/composer_controls_test.dart`（三档宽度触控 ≥48dp）。

## 4. 规格 3：玻璃材质

**DSH Go 依赖来源（固定提交 `9f31a4620b1faeba2a5540f1c56be4419f64473d`）**

- `app/pubspec.yaml` 中没有任何玻璃/模糊第三方库（依赖为 flutter_riverpod、shared_preferences、flutter_secure_storage、webview_flutter、gpt_markdown、path_provider、dartssh2、flutter_webrtc、sherpa_onnx 1.13.4、archive、image、mobile_scanner 等）。
- `app/lib/ui/theme/glass.dart` 自绘 `GlassSurface`/`GlassStyle`/`_GlassEdgePainter`/`_GlassShadowPainter`；`app/lib/ui/theme/surfaces.dart` 定义 `GlassTier {chrome, sheet, card, pill}`（浅色 chrome：fill white@0.70、blurSigma 18、saturation 1.3、hairline ink@0.08、rim white@0.85、两层阴影；深色 chrome：smoke@0.72、blur 18、saturation 1.2）。
- 仓库 LICENSE 为 AGPL-3.0。结论：**不是库，是自绘实现**；本仓库只借鉴设计取舍（模糊+半透明填充+发丝边+顶边高光、阴影只在形状外、分级 tier、关模糊走近实色兜底），未复制其源码。

**本仓库实现（Flutter 官方 API）**

- `GlassPanel` 重写：`ClipRRect` + `BackdropFilter(enabled:, filter: ImageFilter.compose(ColorFilter.matrix(饱和度), ImageFilter.blur))` + `CustomPaint` 画发丝边与顶边渐隐高光 + `CustomPaint` 阴影（even-odd 裁剪只在形状外绘制，避免透过玻璃看到阴影）。
- 减少动效/高对比：只把 `BackdropFilter.enabled` 置 false 并换近实色填充，**组件结构不变**，因此编辑器 State、焦点、滚动位置在切换时保留（`test/natural_glass_test.dart` 断言同一 State 与草稿、偏移）。
- 新增 `DshBackdrop`：页面纵向渐变 + 两处柔光圆（`backdrop-glow-top`/`backdrop-glow-bottom`），使模糊有真实内容可透出；此前纯色 `DshPalette.paper` 背景导致模糊不可见。
- 移除 `liquid_glass_widgets 1.17.3`（`pubspec.yaml`/`pubspec.lock`/`main.dart` 的初始化与 wrap 全部删掉），`flutter pub get` 输出 “These packages are no longer being depended on: liquid_glass_widgets 1.17.3”。

## 5. 规格 4：子代理入口

- `_TopBar` 用 `collectSubagents(controller).isNotEmpty` 决定是否渲染 `subagent-selector`，空目录不占位（不是隐藏仍占宽）。
- 测试：`test/top_bar_entry_test.dart` 三态（初始空 → 发布目录出现 → 重连空快照再次消失）、`test/floating_runtime_test.dart`（空时 `findsNothing`，发布后有真实间距）。

## 6. 规格 5：发送/停止同槽

- `ComposerActionButton` 取代原来的「停止按钮 + 发送按钮」并列：
  - `running && !hasContent && (canStop || stopInFlight)` → 停止（key `composer-stop`）；
  - 有草稿/附件时同槽变发送（key `composer-send`），走原有 queue/steering；
  - 空闲空稿 → 发送禁用；空稿运行中且不支持停止 → 发送禁用并给 tooltip 原因。
- `ChatController` 新增 `isBusy`/`canStop`/`stopInFlight`；`stop()` 置位后通知，`finally` 清除；**取消失败不再伪装成连接错误**：保留原状态与该错误文案（顶栏显示「操作未完成」+ 原因），因此同一槽位可重试。stop 在飞行中按钮禁用，防连点。
- 主子一致：`SubagentChatPage` 用同一个 `ComposerActionButton`；子会话没有 cancel RPC，所以 `canStop=false`（不伪造停止），continuable 仍可排队发送，one-shot/unknown 仍只读。
- 测试：`test/composer_action_matrix_test.dart` 8 项（空闲禁用、运行空稿只显示停止、运行有稿只显示发送并真排队、清稿回停止、停止只调一次并自禁用、取消失败保留错误且可重试、胶囊几何、长名省略号、大字号不溢出）；`test/mobile_geometry_test.dart` 三档宽度完整走「空闲→运行→有稿→发送→清稿→停止→结束」；`test/chat_screen_test.dart` 队列用例改点 `composer-stop` 后仍需「发送下一条」。

## 7. 规格 6：真实模型授权与实测

**桌面侧路由（只读核对，密钥不落地、不打印）**

- `C:/Users/Qt/.dsh/profiles/desktop/cordis.patch.yml`：`liang-da-guan-bao`（displayName 量大管饱），`api: openai-completions`，`baseURL: http://118.178.131.110:9178/v1`，模型 `gpt-6-luna`（同组还有 deepseek-v4.1-flash、glm-5.3-flash、deepseek-v4-flash）。
- 凭据引用 `FFF_API_KEY`（存于 `C:/Users/Qt/.dsh/.credentials.yaml` 的顶层 `refs`，51 字符）；`llm-pi-ai` 解析顺序为 managed `.credentials.yaml` > 进程环境 > 项目 `.env` > `$DSH_HOME/.env`，缺失时报 `MISSING_CREDENTIAL`。
- 端点自检（不打印密钥）：`GET /v1/models` 200、43 个模型、同时存在 `gpt-6-luna`；`POST /v1/chat/completions`（model=gpt-6-luna，带一个 function 工具）返回 `LUNA_OK`，usage 正常。

**设备侧写入（仅 emulator-5570，已备份）**

- 备份：`E:/tmp/dsh-luna-provision/backup-20261116-205020/`（`.credentials.yaml` 161 B、`cordis.patch.yml` 343 B，均为写入前原样字节）。
- 设备 `.credentials.yaml`：保留原 `records`（client-connection/browser-session），追加顶层 `refs: { FFF_API_KEY: <51 字符> }`；核验只读长度，不打印值。
- 设备 `profiles/web/cordis.patch.yml`：保留原 `ui-settings-general`，追加 `llm-pi-ai` provider `luna-route`（apiKeyEnv `FFF_API_KEY`、openai-completions、同一 baseURL、模型 `gpt-6-luna`）与 `agent-default-model` = luna-route/gpt-6-luna。
- 引擎侧只读核验（`session/modelCatalog`）：`provider luna-route name Luna Route models ['gpt-6-luna']`，证据 `android-evidence/rpc-catalog-beta35.json`。
- 写入方式：`adb push` 到 `/data/local/tmp` + `run-as app.dsh.mobile cp`（`run-as ... sh -c '>' ` 在该设备被 SELinux 拒绝，属实现细节，已记录）。

**实测（见 §8 的门禁与 §9 的设备记录）**：真实回复、只读工具、取消三项结果与缺口在下节逐条列出，未用 fixture 或固定回复替代。

## 8. 门禁

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 完整测试 | `flutter test --concurrency=1 --reporter expanded`（workdir `flutter_ui`） | 353 项全通过（01:35），日志 `E:/tmp/native-chat-web-preview/android-beta35-flutter.log` |
| 静态检查 | `flutter analyze` | `No issues found!`（3.8s） |
| 构建 | gradle `:app:assembleDebug`（x86_64、full、workers2、Xmx1536m） | 结果见 `E:/tmp/native-chat-web-preview/android-beta35-build.log` |

## 9. 设备实测记录（emulator-5570，versionCode 410 / 1.6.0-beta.35）

产物：`E:/tmp/native-chat-web-preview/dsh-native-chat-beta35-x86_64-full.apk`
- 大小 248029690 B；SHA-256 `5ef94ff9599dcac8b3c21d05536857710dbe5202aac8eb3c41ae64705f2adc9e`；ABI 仅 x86_64；`versionCode 410`/`1.6.0-beta.35`；内嵌 runtime 138391732 B，SHA-256 `0b693c76…` 与要求一致（`android-evidence/beta35-artifact.json`）。
- `adb -s emulator-5570 install -r` 成功（Success），设备回报 `versionCode=410`。

截图/同帧 dump 均在 `docs/reports/assets/2026-11-16-composer-refinement/`。

1. **规格 1（主会话入口）**：进入真实会话后顶栏首个元素是「打开会话抽屉」按钮（`beta35-03-conversation.png/.xml`），没有任何「返回会话列表」；点它打开抽屉（`beta35-22-drawer-menu.png/.xml`），抽屉内是「新建会话 / 当前 / 会话列表 / 外观 / 自然配色 / DeepSeek 账号」。会话列表入口文案为「会话列表」。
2. **规格 4（子代理入口）**：该会话没有真实子会话，顶栏**完全没有**子代理按钮（`beta35-03-conversation.xml` 与后续所有 dump 均无「子代理」节点），说明空目录时不留占位。空→非空→空三态由 `test/top_bar_entry_test.dart` 覆盖。
3. **规格 2（模型胶囊）**：`beta35-21-real-luna-reply.png` 中胶囊（gpt-6-luna）明显比右侧 48dp 圆形发送键矮，同帧 dump 里胶囊触控区 48dp 高、发送键 48dp（`beta35-04-composer.xml`）。
4. **规格 6（真实模型）**：
   - 模型面板真实列出 `gpt-6-luna / Luna Route`（`beta35-05-model-sheet.png/.xml`）；选择后当前会话 `modelSelection.next` 变为 `luna-route/gpt-6-luna`（journal seq 41 `model/selection`，并写入一条 `source.kind=model-selection` 的切换说明）。
   - **真实短回复**：输入 `Reply LUNA_DEVICE_OK and nothing else.` 后，journal seq 50-54 依次为 `request/header{config:{provider:luna-route,model:gpt-6-luna}}` → `assistant/message`（正文恰为 `LUNA_DEVICE_OK`）→ `step/end` → `turn/end{completed}`；界面显示用户气泡与回复 `LUNA_DEVICE_OK`（`beta35-21-real-luna-reply.png/.xml`，journal `rpc-events-beta35.json`）。这是真实链路返回，不是 fixture。
   - **一次取消**：再发一条真实任务后，输出过程中同一槽位变为「停止执行」（`beta35-24-stop-slot-running.png/.xml`）；点击一次后 journal 出现 `turn/end{reason:{kind:aborted,reason:{kind:user}}}`（`rpc-events-beta35-cancel.json`，seq 62），槽位立即回到「发送」，未产生任何伪造回复。
   - **只读工具任务：未通过（真实失败，已如实记录）**：以只读权限发 `Use the bash tool ... pwd ...` 与 `Use the read tool ...` 两次，journal 中 `assistant/attempt` 都是 `pi-ai stream idle timeout after 300000ms`（code `TIMEOUT`），随后触发 `llm/retry`（retry 1、2）仍超时；界面一直停在「停止执行」，最终由我点停止结束（seq 76 `turn/end` aborted）。
   - 直接对同一端点复现（把 journal 里真实的 48 个工具 schema 原样发出，`probe_luna_tools.py`）：HTTP 200、耗时 59.4s、`tool_calls` 为 0，模型正文自述「I'm unable to access the bash tool in this session」。结论：**该中转站上的 gpt-6-luna 不真正执行工具调用**（带工具 schema 时不出 tool_calls，长请求还会超过 300s 空闲阈值）。这是上游能力限制，不是本次界面改动引入的缺陷；原生端的工具卡、审批卡与停止路径已由既有 353 项测试与上述取消实测覆盖。

## 10. 未覆盖

- 子代理真实链路（本设备无真实子会话）、Web 与原生双端同步、长时间运行与性能仍未验收。
- 玻璃在真实 GPU 上的观感只能以模拟器截图为准，未做真机对比。
