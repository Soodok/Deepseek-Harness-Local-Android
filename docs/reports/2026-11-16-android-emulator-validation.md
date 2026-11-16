# Android 模拟器实机验收（beta.34 / code409）

时间：2026-11-16 17:28–17:55（Asia/Shanghai）
隔离工作树：`E:/tmp/wt-flutter-native-chat`（分支 `feat/flutter-native-chat`，基线 `616efa8ab9e6142f3570e7bea4455bd2d5ea2db7`）
设备：专用 AVD `dsh_flutter_20261116` / `emulator-5570` / Android 34 x86_64
主仓库 `E:/Deepeek harness/dsh-android` 未写入；其他会话设备（5558/5562/物理机）未操作。

## 本轮变更（相对 beta.33）

1. `flutter_ui/lib/src/transport/dsh_session_projection.dart` 的 `_view()`：
   - `user/message` 只在 `source.kind == 'user'` 时成为人类气泡；`compact-checkpoint` 仍作为系统行（它是替代掉旧表面的压缩摘要）；其余注入来源（`user-approval`、`agent-instructions`、`runtime-context`、`skill-catalog`、`time-context`、插件/模型注记等）不再进入对话正文。
   - `system/message` / `developer/message` 不再平铺正文，只校验信封结构（畸形帧仍按协议错误失败）。
   - 依据 rc.2 `dsh-client-ui-chat/src/client/conversation-nodes/message.ts:85-112`：仅 `source.kind === 'user'` 是输入消息，其余是 context 节点（Web 侧可折叠）；`request-prompt.ts` 同样不再为 `system/message` 生成 transcript 行。
   - journal 记录、`surfaceOp: replace` 语义、工具/失败记录均未改动：全部事件仍留在 `_records` 中，Web 端完整日志可查。
2. 回合结束文案：新增 `_failureText` / `_turnEndText`。`MISSING_CREDENTIAL`、`NO_CREDENTIAL_STORE` 映射为「模型尚未配置：当前模型提供方没有可用凭据。请登录账号或添加 API Key 后重试。」；`INVALID_CREDENTIAL`/`ACCOUNT_TOKEN_INVALID`、`AUTH`/`ACCOUNT_SIGN_IN_REQUIRED`、`QUOTA`/`ACCOUNT_QUOTA`、`RATE_LIMIT`、`CONTEXT_WINDOW_EXCEEDED`、`MODEL_UNSUPPORTED`/`UNKNOWN_MODEL` 各有独立文案；未知 code 保留原始 message 并附 code。`aborted`/`interrupted`/`max-tokens`/`blocked`/`forked` 也给出可读句子，未完成的回合仍不发出 `turnComplete`。
3. 版本：`versionCode 408 → 409`，`versionName 1.6.0-beta.34`（`app/build.gradle.kts`）。

## 有效红 → 绿

先落盘的新测试（`flutter_ui/test/dsh_session_projection_test.dart:80`，
`internal model context stays out of conversation while user and turn errors remain visible`）
在实现前实测失败（`android-internal-context-red.log`，exit 1，实际 7 条内部 ChatMessage 非空）。
实现后该用例与整文件 13 项全部通过。

覆盖断言：内部 system/developer 与五类注入 `user/message` 不出现在 `state.messages`；唯一 `ChatRole.user` 行是真实输入；最后一行含「模型尚未配置」与「API Key」；失败回合不产生 `turnComplete`。
既有 `surface replacement removes the addressed rows and keeps non-human context distinct`（compact-checkpoint）与 `only completed turn/end reports complete` 仍通过，没有被放宽。

## 门禁（串行，单次运行）

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 完整测试 | `flutter test --reporter expanded`（workdir `flutter_ui`） | **339 项全通过**，00:52；日志 `E:/tmp/native-chat-web-preview/android-beta34-flutter.log` |
| 静态检查 | `flutter analyze` | `No issues found!`（1.7s）；日志 `android-beta34-analyze.log` |
| 构建 | gradle `:app:assembleDebug`（x86_64、full、workers2、Xmx1536m） | `BUILD SUCCESSFUL in 1m 6s`，exit 0；日志 `android-beta34-build.log` |

APK 产物（`android-evidence/beta34-artifact.json`）：

- 路径：`E:/tmp/native-chat-web-preview/dsh-native-chat-beta34-x86_64-full.apk`（不入库）
- 字节：250 678 251
- SHA-256：`ca6923b6e86bd19cfbd0c6d844016f619c332228befa9522862008b2dd3f7323`
- ABI：仅 `x86_64`；内嵌运行环境 `assets/runtime.zip` 138 391 732 B，SHA-256 `0b693c76ae829035bb9c2f74ebaa869ad3f7abd086c97840d861bbf80c5e92a9`（与 0.2.2-rc.2 归档一致）
- 安装后设备回报：`versionCode=409`、`versionName=1.6.0-beta.34`

## 设备实测（emulator-5570）

1. 覆盖安装 + 冷启动：`LaunchState: COLD`，引擎 `health ok`，原生入口默认进入真实引擎会话列表（非 fixture）。
2. 打开真实会话 `session-a417e50d-81a2-48fe-92c8-231d197ee6e9`：对话正文只有
   - 人类气泡 `Please reply NATIVE_UI_OK`
   - 一行系统提示「回合结束（error）：模型尚未配置…请登录账号或添加 API Key 后重试。」
   截图 `docs/reports/assets/2026-11-16-android-beta34/beta34-conversation.png`，同帧 uiautomator dump 只有上述两行正文。
3. 同一会话再发一条真实消息 `Beta34 second send`：写入成功、引擎接受、回合以同一缺凭据错误结束，界面只追加人类气泡与同样的可读错误行（`beta34-second-send4.png` / `.xml`）。
4. 相同会话的原始 journal（`android-evidence/rpc-events.json`，40 条事件，2 个回合）实际包含
   `system/message`×1、`user-approval`/`agent-instructions`/`runtime-context`/`skill-catalog`/`time-context` 各 1 条、`turn/end {kind:error, code:MISSING_CREDENTIAL}`×2。
   即：内部上下文确实在会话里，只是不再平铺到原生对话正文。摘要见 `rpc-events-summary.json`（不含内部正文与凭据）。
5. 设置：Token 区块显示「所选时段暂无记录」，无异常、无渲染报错；模型配置入口如实跳转 Web 模型页，Web 首启弹「开始使用 / 添加 API Key / 登录」（`beta34-settings.png`、`beta34-settings-scrolled.png`、`beta34-model-config.png`）。
6. 模型选择面板：当前 catalog 为空时明确显示「还没有可用模型，请先配置模型提供商或登录账号。」，并提供「配置模型」「重新读取可用模型」两个出口（`beta34-typed3.png`）。
7. 冷启动恢复：`am force-stop` + 重新启动后，原生入口、会话列表、会话内容与浅色 sand 主题都恢复；`android_probe.py crash` 在 AndroidRuntime/flutter/EngineSupervisor 三个 tag 上无输出，无崩溃。
8. 期间一次真实现象：强停后立刻重开时，页面曾短暂显示「等待本机引擎就绪 / 引擎状态：Backoff」，约 15 s 内自行恢复并显示「已连接」。属启动期短暂退避，未阻塞后续操作；未有日志能证明是回归（记录在此，不引申）。

## 未验证 / 需要用户授权

- **在线模型回复、真实工具执行、审批交互与停止按钮**全部依赖模型凭据。本机 catalog 为空、未登录、无 API Key；按用户要求禁止读取/复制桌面凭据，也不允许用 fixture 或本地固定回复冒充在线 AI。上述路径仍未验收。
- 因此「正常使用」只到「真实会话可建立、可发送、可读回、失败原因可见」这一层；不能声称模型已可用。
- 子代理面板、Web 与原生双端同步、长时间运行与性能（FPS/内存）本轮未覆盖。

## 复现命令

```bash
cd E:/tmp/wt-flutter-native-chat/flutter_ui
E:/tools/flutter/bin/flutter.bat test --reporter expanded
E:/tools/flutter/bin/flutter.bat analyze
# 构建
JAVA_HOME='D:/新建文件夹/jbr' ANDROID_HOME='E:/Android/Sdk' \
  'C:/Users/Qt/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin/gradle.bat' \
  -p E:/tmp/wt-flutter-native-chat --no-daemon --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' -Pkotlin.incremental=false \
  -Pkotlin.compiler.execution.strategy=in-process --no-configuration-cache --console=plain \
  :app:assembleDebug -Pabi=x86_64 -Ptarget-platform=android-x64 -Ppackage=full \
  -PruntimeZip=E:/tmp/dsh-flutter-runtime-20261116/runtime-x86_64-dsh-0.2.2-rc.2-20261116.zip
```
