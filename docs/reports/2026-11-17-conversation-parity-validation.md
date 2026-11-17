# 会话平权验收记录（2026-11-17/18，beta.37 → beta.50）

设备：专用 AVD dsh_flutter_20261116（emulator-5570，1080x2340，Android x86_64）。未操作任何物理真机，未读桌面/设备凭据。在线回合使用设备已配置的 gpt-6-luna（用户授权），全部为真实模型请求，无 fixture 冒充。

## 1. 自动化门禁（串行执行，最终态 beta.50）

| 命令 | 退出码 | 结果 |
| --- | --- | --- |
| flutter analyze --no-pub | 0 | No issues found!（2.6s） |
| flutter test --concurrency=1 | 0 | 427 tests passed（/tmp/fulltest_b50.log） |
| node --test client.test.mjs core.test.mjs | 0 | 34 tests / 34 pass / 0 fail |
| node --check .../turn-rewind/client.js | 0 | 语法通过 |
| gradle :app:assembleDebug（beta.50） | 0 | BUILD SUCCESSFUL in 2m 11s |
| flutter analyze --no-pub（beta.51） | 0 | No issues found!（1.3s） |
| flutter test --concurrency=1（beta.51） | 0 | 427 tests passed（/tmp/ft_b51b.log） |
| node --test client.test.mjs core.test.mjs（beta.51） | 0 | 37 tests / 37 pass / 0 fail |
| gradle :app:assembleDebug（beta.51） | 0 | BUILD SUCCESSFUL in 34s |

构建命令（JAVA_HOME=D:/新建文件夹/jbr，ANDROID_HOME=E:/Android/Sdk）：

```bat
gradle.bat --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process 
  --no-configuration-cache --console=plain :app:assembleDebug 
  -Pabi=x86_64 -Ptarget-platform=android-x64 -Ppackage=full 
  -PruntimeZip=E:/tmp/dsh-flutter-runtime-20261116/runtime-x86_64-dsh-0.2.2-rc.2-20261116.zip
```

安装：adb -s emulator-5570 install -r -t -d app/build/outputs/apk/debug/app-debug.apk（必须带 -t；设备上曾有其他工作树的 versionCode 430，必须带 -d 才允许降级）。装机后 dumpsys package 确认 versionCode=425 / versionName=1.6.0-beta.50。

## 2. 新增/修改的测试用例（每条都有负对照）

### Flutter（flutter_ui/test/message_actions_test.dart，共 29 例）

本轮新增 4 例，并保留早前 2 例：

1. a turn finished after the page opened still has its file plan —— 页面打开后才结束的回合，planForFresh 仍给出「将回滚 1 个文件的改动」。负对照：把 planForFresh 改成直接 return planFor(message)，该例在断言 plan.canRestoreFiles 处变红（test line 424）。
2. a stale plan is never shown when the Host cannot be asked —— Host 读不到时抛 TransportException，调用方拿不到「没有文件」的假结论。
3. a Host conflict keeps the paths it names in the top level —— 用 rc.2 真实冲突形状（无 files 键）断言 conflicts == [conflict-demo.txt]。负对照：把 conflicts: conflictPaths() 改回 paths(files, conflicts)，该例变红。
4. a rewound answer still reads the file outcome under files —— 兼容 files.* 嵌套形状，防回归。
5. 早前两例：撤回在页面消失后落地不抛异常、fork 后草稿共享。负对照分别为去掉 _disposed 判断、把 _ownsDraft 固定为 true。

### Web 插件（docs/reports/2026-11-17-conversation-parity-plugin-test/client.test.mjs，共 34 例）

本轮新增 8 例：编辑态挂在 fork 会话上、取消编辑还原草稿、保留草稿带进 fork、被拒的编辑不留编辑态、编辑横条可取消、编辑横条浮层与 rc.2 令牌、回答身份读 finalNode、系统分享走原生桥。各自负对照均打红后还原。

beta.51 在 core.test.mjs 增 3 例（共 14 例）：fork 失败时一个文件都不动 / 撤回先 fork 再还原文件（断言调用顺序） / 只撤回文本时不碰任何文件。负对照三次均打红后还原：①把还原循环挪到 fork 之前 → 顺序断言 actual 变成 [restore:agent, fork]；②fork 失败后继续走还原、最后才报错 → 「fork 失败时一个文件都不动」红（13 pass / 1 fail）；③fork 失败被吞掉当成功 → 同例红。

## 3. 设备实机验收

### 3.1 原生端（Flutter，NativeChatActivity）

uiautomator content-desc 证据（动作行齐全）：用户行「复制」「撤回」；助手行「有帮助」「没帮助」「复制」「重新输出（回滚这一轮的文件改动）」「分享」。

| 场景 | 操作 | 结果与证据 |
| --- | --- | --- |
| 复制 | 点「复制」 | 系统复制浮层弹出，内容为气泡原文 |
| 撤回（仅文本） | 点用户气泡「撤回」→ 确认 | 出现「回合已分叉 / 这一轮已在分叉点关闭」，原气泡消失，原文回填输入框（/tmp/b44_ra.png） |
| 编辑 | 点用户气泡本体 | 气泡出现描边与水滴标记、下方出现「正在编辑这条消息，发送会从这里继续」横条、输入框填原文（/tmp/b44_e2.png） |
| 取消编辑 | 点横条右侧 × | 编辑态消失、SnackBar「已取消编辑，原来的草稿也还回来了」（/tmp/b44_e6.png） |
| 点赞 | 点「有帮助」 | 图标高亮并提示已点赞；会话日志第 87 条事件 type=feedback/message-put，item={messageId:553ba79b-…, rating:positive, version:3c7c5653-…}（/tmp/n1.png） |
| 分享预览 | 点「分享」 | 面板显示「问：NATIVE_ACTIONS_OK / 答：Acknowledged…」，四键：关闭 / 复制 / 保存到文件 / 系统分享（/tmp/n_share1.png） |
| 系统分享 | 点「系统分享」 | topResumedActivity 变为 com.android.intentresolver/.ChooserActivity，显示 Sharing 1 file（/tmp/n_share2.png） |
| 保存到文件 | 点「保存」 | /sdcard/Download/NATIVE_ACTIONS_OK.txt 106 B，内容为问+答（/tmp/n_save2.png） |
| 重新输出 | 点「重新输出（回滚这一轮的文件改动）」→ 确认 | 确认框「将回滚 1 个文件的改动」→ receipt {turn:10, files:{applied:true, restored:[{path:native-rollback.txt, action:deleted, ok:true}]}}（/tmp/b49_re2.png、/tmp/b49_re3.png） |

### 3.2 文件回滚六种场景（设备级真实样例）

| 场景 | 造数据方式 | 撤回后 | receipt |
| --- | --- | --- | --- |
| 新建文件 | 让模型 write 出 native-rollback.txt = NATIVE-V1 | 文件被删除 | action=deleted, ok=true（/tmp/b49_rt.png、/tmp/b49_rt2.png） |
| 修改文件 | 让模型把 mod-demo.txt 从 MOD-V1 改成 MOD-V2 | 内容还原为 MOD-V1，文件仍在 | action=restored, ok=true（/tmp/b49_m3.png、/tmp/b49_m6.png） |
| 删除文件 | 让模型 bash rm 删掉 mod-demo.txt | 文件被恢复，内容 MOD-V1 | action=restored, ok=true（/tmp/b49_d7.png） |
| 改名（mv） | 让模型 bash mv ./cg.txt ./renamed.txt | renamed.txt 被删除、cg.txt 被恢复（内容 EXTERNAL-CG）；确认框写「将回滚 2 个文件的改动」 | restored:[{path:cg.txt, action:restored}, {path:renamed.txt, action:deleted}]（/tmp/ren1.png、/tmp/ren2.png、/tmp/ren3.png） |
| 二进制删除 | adb 造 bin.dat = 字节 00 01 02 42 49 4e 56 31 ff（9 B），让模型 bash 删除 | 字节级还原，撤回后 sha256 = 02c87b81138cd938bfdc579ccd68d1e3e07b57550945b4f528a0cacf9d512e3f，与 manifest 的 before.blob 完全一致 | action=restored, ok=true；turn manifest before={size:9, binary:true}（/tmp/bin7.png、/tmp/bin9.png） |
| 外部冲突 | 模型写 cg.txt 后用 adb 改成 EXTERNAL-CG 再撤回 | 文件保持 EXTERNAL-CG，界面红条「这些文件在这一轮之后被改过，没有覆盖：cg.txt」 | status=conflict, conflicts=[{path:cg.txt, reason:changed-after-turn}]（/tmp/b50_cg2.png） |

注意：冲突场景第一次尝试时工作目录写错（native-ui-check 而非会话真实 cwd workspaces），那次提示里的文件名是空的——正是 beta.50 修掉的 RewindOutcome.parse 缺陷；换到正确 cwd 后复测通过。

### 3.3 Web 端（应用内 WebView，127.0.0.1:3080）

| 场景 | 结果与证据 |
| --- | --- |
| 撤回 | 确认后出现「回合已分叉」，原文回填输入框 |
| 编辑 | 横条「正在编辑这条消息，发送会从这里继续」浮在输入卡片上方，右侧 × 可点（/tmp/b46_n1.png）；点 × 后横条消失、草稿 KEEP_THIS_DRAFT 回到输入框（/tmp/b46_s2.png） |
| 点赞/踩 | 点「好的回答」打开「提交反馈」面板；提交后 CDP Network 抓到真实 POST /api/messageFeedback/put，按钮 aria-label 变「取消标记」 |
| 重新输出 | 确认后页面回滚到该轮起点并自动重发，约 56 秒后生成新回答 |
| 分享 | 预览显示问+答；保存到 /sdcard/Download/DSH MOBILE/dsh-conversation.txt（77 B）；系统分享唤起 ChooserActivity（/tmp/b47_a1.png） |
| 文件回滚 | 造 rollback-demo.txt 后撤回，receipt {turn:7, files:{applied:true, restored:[{path:rollback-demo.txt, action:deleted}]}}，工作区文件消失 |

### 3.5 beta.51：撤回顺序与小屏/大字适配（设备级）

| 场景 | 操作 | 结果与证据 |
| --- | --- | --- |
| 先 fork 后还原（正常路径回归） | Web 端让模型 write 出 order-demo.txt=ORDER-V1（manifest turn 9 status=created），再点该轮「撤回」并确认 | receipt files/dsh-home/turn-rewind/session-c7bfed23-…/rewinds/rewind-mwl305f8-tj3x09.json = {"status":"rewound","childSessionId":"session-f8d9c815-2768-4a12-ad6a-b40334e782c5","turn":9,"files":{"applied":true,"restored":[{"path":"order-demo.txt","action":"deleted","ok":true}],"skipped":[],"conflicts":[]}}；order-demo.txt 已删除（/tmp/b51_4.png 确认框「将回滚 1 个文件的改动」、/tmp/b51_7.png） |
| 大字（font_scale 1.30）下编辑横条 | 点「编辑这条消息」→「开始编辑」 | 横条 getBoundingClientRect = [16,559,398,39]，position:absolute 浮在输入卡片上方；elementFromPoint 命中 dsh-rw-editing-text（不是被压住的输入框）；文字「正在编辑这条消息，发送会从这里继续」可读（/tmp/font6.png） |
| 大字 + 小屏（720x1280 @320dpi，视口 400x643） | 同上 | 横条 rect = [16,461,361,40]，同样 position:absolute 且命中自身；点 × 后横条消失、草稿原样回到输入框（/tmp/small6.png、/tmp/small7.png） |
| 减少动效 | settings put global animator_duration_scale 0（并同关 transition/window）后重开应用 | 会话列表、抽屉、编辑横条均正常出现，无残留中间态（/tmp/font1.png） |

注：以上三项测完已把 wm size / wm density / font_scale / 三个动画倍率全部还原（wm size reset、wm density reset、font_scale 1.0、动画值 1）。

### 3.4 会话入口与切换

- 抽屉列出真实引擎会话，只显示人类可读标题（多行均为 KEEP_THIS_DRAFT 这类真实首条消息），无 session 标识、无 cwd（/tmp/b50_10.png）。
- 「新建会话」直接进入空会话，输入框可用，无需填工作目录；新会话 cwd 由引擎按工作区决定（实测两种：native-ui-check 与 workspaces）。
- 灵动岛「切换原生对话」从 Web 直接进原生，承接当前会话；反向返回 Web 对齐同一会话。

## 4. 环境问题（非本轮引入）

- 引擎在模拟器上每约 60–80 秒自行退出并自愈重启（logcat engine exited: status=0 signal=0），界面随之重建；期间原生页可能长时间停在「等待本机引擎就绪 / 引擎状态：Starting」。
- 偶发降级启动：plugin health: ok=false missing=[directoryPickerController,workspaceRegistry,workspaceController]，冷启后恢复 ok=true。
- 设备上曾装着其他工作树（E:/tmp/wt-island2，versionCode 430）的 APK，导致本轮插件一度「未进 bundle」。教训：出现该现象先核 dumpsys package 的 versionCode 与本轮 build.gradle.kts 是否一致。

## 5. 未覆盖

- 文件回滚的「权限不足 / 磁盘配额 / 部分失败重试」只有离线单测，没有设备级真实故障注入。
- fork 失败的真实故障注入：只有离线单测（core.test.mjs 三例 + 三次负对照），没在设备上造出 fork 真实失败的场景。
- 切换动效：未录逐帧视频。
- ARM 真机、TalkBack、硬件键盘遍历、全部主题像素对比。
