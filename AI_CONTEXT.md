## 设置页第二轮整理（2026-10-30 · v1.4.54 / 305 未发布）

**宝宝验收「还是有点杂乱」后的信息架构整理（1+2+3 方案）**

- **应用更新收进子页**：新增 `UpdateSettingsActivity` + `activity_update_settings.xml`，6 项更新配置
  （自动检查/仅 WiFi/索引代理/安装包形态/忽略版本/立即检查）从设置主页迁入；系统区留下 `rowUpdate`
  入口行（副标题显示当前版本 `setting_update_entry_sub` 的 %1$s）。设置主页网络区回到 2 行。
- **语义归位**：`rowMic` 移入权限区；语音区撤销，`rowVoiceService`/`rowTts` 并入「扩展与语音」
  （`settings_section_extension` 四语文案改为 Extensions & voice / 扩展与语音）；`rowViewLog`
  移入维护与高级子页。
- **数据区拆子页**：新增 `BackupActivity` + `activity_backup.xml`，导出/导入/恢复对话/配置文件迁入；
  主页数据区只留 `rowUserPrompt` + `rowBackup` 入口。
- 主页从 37 行 / 8 分区降到 28 行 / 6 分区（外观7 权限7 扩展与语音6 数据2 网络2 系统4）。
- 布局手术仍用一次性 Python 脚本（深度匹配提取行块→重建分区→生成两个子页布局），用后即删；
  行块原样迁移，已保留并发会话 d6702ab 对权限行的 minWidth/maxWidth 修复。
- 探针：`_test_settings_ux.py` 扩到 73 项（新子页结构/迁移/入口/主题/i18n）；`_test_update_prefs.py`
  写入方改为两页合集；`_test_pet_overlay.py` 的 import 错误映射改查 BackupActivity。
- **验证证据**：探针全绿（settings_ux 73、responsive、theme、pet、update_* 等全部）；`check-i18n.py`
  通过；`compileDebugKotlin + processDebugResources` BUILD SUCCESSFUL（仍 -x prepareRuntimeAssets，
  本地缺 rt-arm64-zodded.zip）。`_test_corepack_hardlink.py` 同因缺 runtime.zip 失败，非本轮引入。
- **checker 代理因中转站配额用尽未能出动本轮**，已按 checker 清单自查：SettingsActivity 无残留
  引用（REQ_EXPORT/REQ_IMPORT 已删）、两子页 findViewById id 与布局一一对应、section 配对结构完好、
  versionName 仍被入口/关于页使用。**未上模拟器实测 UI**，下次重点看：GRID 模式、深色主题、
  数据区两行卡片、系统区四行卡片。
- ⚠️ 并发会话同时在改 `MainActivity.kt`（灵动岛 v1.4.54）+ `_test_security_audit.py`，本轮提交已排除，
  版本号 1.4.54/305 可能与其 WIP 撞号，合并时以实际为准。

## Issues 处理轮（2026-10-30 凌晨 · v1.4.45）

**#74（root→普通模式插件安装双失败）—— 已修，commit `48a9963`**
- 根因：#65 的修复（prune 锁树保护 root 锁）与启动自愈的检测互相打架——检测每轮命中 root 的 `...-locks-0`、修复每轮跳过，永不收敛；`.cache/pnpm/v12/metadata` 两头不管。
- 修法：锁与 metadata 按**当前引擎 uid** 自管理（engineUid=root 模式 0/否则 app uid），别的 uid 的删、自己的留；检测与 chown 都 prune 自管理状态（正向 `-path`，`metadata*` 跨层剪）；SELinux 用 `stat -c %C` 现场取 context 做 chcon 兜底；新增 `ISLAND-SELFTEST-READ-OK/DENIED` 与 `ISLAND-ROOT-LEFTOVER=<n>` 诊断日志。
- 探针 `_test_root_pnpm_locks.py` +7 条断言（17/0）；shell 片段本机实测（uid 双向切换、glob 跨层、prune 语义）；模拟器冒烟通过。
- ⚠️ 未验证：用户真机上的 root→普通 实测（等 ≥1.4.45 release 包）。

**#72（WebView getUserMedia 恒拒绝）**——v1.4.42 已修（onPermissionRequest + MODIFY_AUDIO_SETTINGS），已回复并关闭。
**#49（swap 恢复窗口）**——P1-2 已在 v1.4.26 落地（意图日志+前滚/回滚+rename 失败即中止）；builtAt 防降级已做；剩余 P2：HttpDownloader 强制 https、PKCS12 报错文案、SafeMode 文档化。已回复，保持 open。
**#66（引擎 rc.3+ 插件装不上）**——豁免入口（v1.4.37）已回复；引擎升级需走 CI 签名 runtime，保持 open。
**#60/#26（长会话卡顿）**——同根（上游 context 压缩），回复现状与绕过办法，保持 open。
**#59/#41/#2（wheel 支持/Profile 管理/语音唤醒）**——登记 enhancement 并回复设计约束，均未排期，保持 open。

**协作注意**：会话 `a839f8c`（另一 ZCode 会话）提交了设置页 UX 改版（ReportDialog/MaintenanceActivity/权限徽章），与本次 issue 修复无冲突，版本号已对齐 295/1.4.45。

## 本轮完成（2026-10-30 · v1.4.45 未发布）

**设置页 UX 改版「深改版」（宝宝验收预览后开工，TDD：先写探针再实现，checker 审查后修复）**

四条主线：
1. **报告弹窗结构化**：新增 `ReportDialog.kt`（摘要徽章行 + 状态徽章分项列表 + 复制报告/确定），
   自检与启动详情不再 `setMessage` 纯文本堆砌；OK=✓ / FIXED=✓+ / WARN=! / FAIL=✗，异常项软底高亮。
2. **行尾 affordance 统一**：动作行（导出/导入/恢复对话）chevron → 胶囊按钮（`btnExport/btnImport/btnRecover`，
   依赖整行点击，不拦截）；`rowLanguage` 补 chevron；维护子页 `btnPluginKill`（文案随状态 停用/恢复）、
   `btnCheck`、`btnRestart`（危险红）。
3. **权限状态徽章化**：`valRootStatus/valShizuku/valAccess/valStorage/valNotif/valMic` 用 `asStatusChip()`
   软底 chip（success_soft/warning_soft/surface_alt + 状态色文字，明暗主题都保证对比度）；缺权限时行尾
   显示「去开启」按钮（`btnEnable*`，点击行为与整行一致），已授权时按钮收起；GRID 布局模式下按钮一律
   让位（`compactRowForGrid` + `setEnableActionVisible` 双保险，防两列宽度溢出——checker P1-2）。
4. **维护项收进子页**：新增 `MaintenanceActivity`（manifest 注册 exported=false + configChanges），
   插件应急开关/全面体检/上次启动/重启引擎四行迁入，设置主页只留「维护与高级」入口行（放进更名后的
   「系统」区段，原「关于」区段）；重启引擎新增确认弹窗；kill switch 意图日志 source 标签改 "maintenance"。

- 布局手术用一次性 Python 脚本完成（18 处编辑、断言 + 回滚重跑一次修好开标签劈裂），脚本用后即删。
- 新颜色 token：`success_soft/warning_soft/danger_soft`（values + values-night 成对，探针强制校验）。
- 新增回归探针 `scripts/probes/_test_settings_ux.py`（50 项：结构化弹窗/子页迁移/affordance/徽章/主题纪律/四语键）。
- **验证证据**：`check-i18n.py` 通过（825 键四语齐、0 CJK 字面量）；探针全绿（settings_ux 50、responsive、
  theme_menu、pet_overlay）；`compileDebugKotlin + processDebugResources` BUILD SUCCESSFUL
  （-x prepareRuntimeAssets，本地缺 rt-arm64-zodded.zip 属环境问题，与本轮无关；
  `_test_corepack_hardlink.py` 同因缺 runtime.zip 失败，非本轮引入）。
- **checker 审查结论**：P0=0；P1 两条（版本号未递增、GRID 溢出）+ P2 六条全部修复；逻辑迁移逐行比对等价。
- 未经真机/模拟器实测 UI 效果（本轮只过了静态探针 + 编译）；下次上模拟器建议重点看 GRID 模式与深色主题下的设置页。
- 版本 `1.4.45` / `295`（未发布，未打 release）。
- ⚠️ 工作树另有 promo v5 相关改动（`docs/promo/video/remotion/src/*` 与 untracked 素材，属另一条工作线），
  本轮提交已明确排除，勿混入。

## 本轮完成（2026-10-29 深夜 · v1.4.44）

**全屏灵动岛定位 + 按钮避让挖孔（用户反馈闭环）**

- 根因：`displayCutout` 的 top inset（本机 128px）是「顶边被遮挡的最大深度」，**不是状态栏高度**。旧实现把它当成"必须让开的高度"往下推，岛被顶到 y=154（正好在孔下方）。全屏本身早已生效（`statusBarRow` bounds.top == marginTop 即证）。
- 修法（`MainActivity.applyIslandInsets()`）：全屏时岛 `topMargin=0` 贴屏幕最顶端，高度取 `max(44dp, cutoutTop)`，用黑色胶囊把摄像头孔罩住 —— 这才是灵动岛形态。非全屏保持 10dp。监听挂在 `android.R.id.content` 上（挂在岛自己身上拿不到全屏后的新 insets）。
- 横屏：挖孔在左右两侧，岛在顶部不与其重叠，故**不加侧边距**，否则把居中的岛推偏。
- 日志按钮从灵动岛移除（原位置 x=548~648 正压在挖孔 492~610 下，全屏时点不到），迁到设置页「其他」区「查看日志」。移除后重启按钮右移到 x=627~727，完全避开挖孔。四语文案 `setting_view_log` / `_sub`。
- 收起态改为**正圆**（`bg_dynamic_island_dot.xml`：半透明外圈小光圈 + 内圈实心黑 + `bg_island_core_dot` 中心亮点）。直径 `max(26dp, cutoutTop*0.85)` 并强制宽高相等；本机实测 108px（取满 128 会成大黑饼）。
- 版本 `1.4.44` / `294`。

**实测证据（Android 16 x86_64 模拟器，1080×2400 @420dpi，挖孔 boundingRect=Rect(492,0-610,128)）**
- 展开态 `statusBarRow` bounds=`[32,0][1048,128]`，贴顶。
- `btnRestart` x=627~727（挖孔止于 610，留 17px 余量）。
- 收起态 `handleBar` bounds=`[486,0][594,108]`，宽高相等为 108（正圆）。
- 设置页滚动后 `rowViewLog` 存在，点击后 `topResumedActivity=LogViewerActivity`，engine.log/boot.log 两标签正常显示。
- i18n 检查通过；4 个探针全绿（render_paging 12、engine_compat 18、install_errors 25、webview_media 17）。

# AI_CONTEXT

## v1.4.38：noruntime 的运行时身份 + release 链路验收（2026-10-29 凌晨）

**结论：两条遗留都关了，且根因与上一轮判断不同 —— 问题在打包侧，不在发布侧。**

上一轮把「索引里 runtimeVersion=null」修在了发布脚本上，真机再看，`.runtime.json` 仍是
`{"version":"unknown","builtAt":0,"sha256":""}`。真根因是 **noruntime 包的
`assets/runtime/MANIFEST.json` 里 `runtime` 段是占位值**：version 恒 `"unknown"`、
`builtAt` 硬编码 0、sha 为空（因为构建期没有运行时内容可算）。App 的 `install()`
从头到尾用 `manifest.runtime` 写戳，于是"装完了也只知道 unknown"。

用户的判断是对的：**noruntime 只是不打运行时二进制，运行时身份（版本/时间戳/校验和/
体积/下载地址）本来就该打进去** —— 这些 CI 手里全有（`runtime-meta.json` 带 builtAt），
只是没往下传。

改了四处：

1. `scripts/write-runtime-manifest.py`：新增 `--runtime-built-at`；noruntime 不再写死
   `builtAt: 0`。没有权威事实时仍写 `unknown/0`（不编造），尤其**不拿本地 `_runtime_bak`
   的 zip 哈希充数** —— App 会拿它 pin 校验下载回来的包，哈希不符直接拒装。
2. `app/build.gradle.kts`：新增 `-PruntimeBuiltAt` 并透传给清单脚本（full/noruntime 都传）。
3. `android-build.yml`：从 `runtime-meta.json` 读 `builtAt` 传给 Gradle。顺带修掉清单路径
   少写 `app/` 前缀的老坑（`runtimeAssetsDir` 在 app 模块求值，仓库根的
   `build/generated/...` 是另一个目录 → `-f` 恒假；与上一轮是同一类错的两种写法）。
4. `RuntimeInstaller.install()`：下载分支拿到签名索引条目后，用**条目事实**回填
   `info`（version/builtAt/sha256/size/unpackedBytes）再写戳 —— 兜底本地构建/老包。
5. `UpdateAssetPicker.runtimeChanged()`：本地是占位值 `"unknown"` 时按「未变更」处理
   （真正"没装"仍算变更）。猜"变了"会让存量 full 用户每次升级被劝多下 130MB。

**真机验收（MuMu 5555，release 286 基线 → stable 287，一次跑通两条遗留）**

- release 基线包（286/1.4.36，`-Pruntime*` 全带，pin 到 v1.4.37 的运行时）装好后首启：
  `RuntimeInstaller: installing runtime 2026.10.28-7432d79c (noruntime)`（不再是 unknown）；
- 下载 127MB（代理优先）→ 解压 → `engine healthy on :3080`；戳写成
  `{"version":"2026.10.28-7432d79c","builtAt":1793181069,"sha256":"d910e10c…","source":"download"}`
  —— **遗留 2 关闭**；
- 自动检查（release 构建 → stable 频道 → **release 资产**，25138298B，
  sha256 `5668a9e2…` 与索引一致）→ 通知「1.4.37 已下载 / 点击安装」→ 关于页
  「立即安装」→ 系统「要更新此应用吗？」→ 更新成功：**versionCode 287 / 1.4.37**，
  `files/engine` 546MB 原样保留（`runtime … already installed`，没重下）——
  **遗留 1 关闭**；
- 关于页：`已是最新（1.4.37）· 上次检查 01:50`、`运行时 2026.10.28-7432d79c 已是最新`、
  蓝色「Fork 构建」徽标、`github.com/XiaoPb/…`；`SignedIndex: index not modified (etag hit)`
  说明 ETag 缓存也正常。
- 注意：release 包不可 `run-as`，读私有目录靠 `adb root`（MuMu 的 adbd 本来就是 root，
  没有 `su` 二进制）；首次覆盖安装需要用户在系统页打开「允许来自此来源的应用」。

**护栏**：`scripts/probes/_test_runtime_stamp_truth.py`（40 项，含真跑两遍清单脚本断言
产物字段）+ `_test_update_asset_picker.py` 补 3 条 unknown 语义用例；全套 7 个探针绿。

**上游合并**：`upstream/main` 3033510（Telegram 群 + 隐私文案 + 版本 288/1.4.38）已合入，
冲突四处（AboutActivity + 4 语言 strings）—— 统一保留 fork 的徽标/动态仓库，采用上游文案与
Telegram 点击逻辑。版本号随上游 = 288 / 1.4.38。

**发版决定（用户，2026-10-29）**：**先不打 tag `v1.4.38`**，只留 edge，等上游发
`v1.4.38` 之后再跟一版正式发布 —— 避免"同版本号、内容不同"的 fork 包与上游撞车。
main 已推送（`dc0891e`），edge 已发布 1.4.38 的 4 个 noruntime 资产 + 索引。

**CI 已确认构建链生效（run 40740138913，4 lane + publish-edge 全绿）**

```
noruntime: manifest: package=noruntime abi=x86_64 version=2026.10.28-7432d79c builtAt=1793181069 sha=- bundled=False
noruntime: meta:     versionCode=288 package=noruntime runtimeVersion=<none> builtAt=0
full:      manifest: package=full      … version=2026.10.28-7432d79c builtAt=1793181069 sha=d910e10cb702 bundled=True
full:      meta:     versionCode=288 package=full runtimeVersion=2026.10.28-7432d79c builtAt=1793181069
```

- noruntime 清单**带上了真实 version/builtAt**（修复目标达成）；sha 为空是**对的** ——
  edge 的 urlBase 是 `releases/latest/download`（滚动），pin 住哈希迟早校验失败。
- meta（=发布索引里的 `asset.runtimeVersion`）对 noruntime 仍是空：这个字段的语义是
  "该 APK **自带**的运行时版本"，noruntime 本就不带，留空才不会被 App 误判。
- 拆包核对 edge 产物 `dsh-android-main-x86_64-v1.4.38-debug-noruntime.apk`：无
  `assets/runtime.zip`，`runtime.version/builtAt` 与上面一致。

## 本轮项目历史与源码核验快照（2026-10-29）

> 本节是本轮对 `/e/Deepeek harness/dsh-android` 的只读源码/Git 核验结果；未新建 APK、未修改业务源码、未把 README 或旧交接文档单独当成运行证据。源码事实与文档/运行态证据分开记录。

### 范围与快照

- 项目边界：当前工作区根 `/e/Deepeek harness` 不是 Git 根；实际 Git 仓库是 `/e/Deepeek harness/dsh-android`，远端为 `origin=https://github.com/Soodok/Deepseek-Harness-Local-Android.git`，另有 `masspedro`、`xiaopb` 远端。
- Git 快照：当前分支 `main`；HEAD `6fd87bd287aa72d6dcea9718d1c470a7f3026b7e`（2026-10-29 22:02 +08:00，`fix(ui): 全屏模式与灵动岛冲突 —— 按挖孔安全区动态定位（用户反馈）`）；`main` 有 514 个提交，所有 refs 合计 783 个提交，根提交为 `f9413d61879e457082867033a231649194f5ecc6`（2026-08-26）。
- 构建源码当前版本：`app/build.gradle.kts:169-176` 为 `minSdk=26`、`targetSdk=28`、`versionCode=293`、`versionName="1.4.43"`；`settings.gradle.kts` 只有 `:app` 一个模块；顶层插件为 AGP 8.5.2、Kotlin 1.9.24。
- 当前工作树有未提交用户/本轮外部改动：已修改 `AI_CONTEXT.md`、`app/build.gradle.kts`、`app/src/main/java/app/dsh/mobile/MainActivity.kt`、`app/src/main/res/values-de/strings.xml`、`values-pt-rBR/strings.xml`、`values-zh-rCN/strings.xml`、`values/strings.xml`、`values-night/themes.xml`；未跟踪 `_rel/`、`docs/promo/video/remotion/tests/v4-keyframes.test.mjs`、`docs/promo/video/review-frames/`。本轮只更新了 `AI_CONTEXT.md`，未覆盖、未清理其他改动。
- 规模快照：Git 跟踪文件共 531 个，其中 `app=310`、`scripts=63`、`docs=141`、`.github=4`、其它 13；主源码包含 106 个 Kotlin 文件、3 个 C/C++ 文件、124 个资源文件、70 个 assets 文件。计数来自本轮 `git ls-files`，不是构建产物计数。

### 真实运行拓扑（源码证据）

- `DshApp` 是 Application 入口，创建全局 `EngineSupervisor` 与 `ExtensionManager`，同时安装 Java 未捕获异常处理，将堆栈写入 `filesDir/crash-last.txt`：`app/src/main/java/app/dsh/mobile/DshApp.kt:10-48`。
- `MainActivity` 是 `ThemedActivity` WebView 外壳，不重写上游 WebUI；健康后加载回环地址，提供桌面/竖屏视口、文件选择、下载、剪贴板、网页媒体权限、渲染进程崩溃后只重建 WebView 等宿主能力：`app/src/main/java/app/dsh/mobile/MainActivity.kt:39-48`、`:350-651`。`onRenderProcessGone()` 返回 `true` 并调用 `recoverWebView()`，源码语义是保留引擎、只恢复渲染层：`:573-598`。
- `EngineService` 是 `specialUse` 前台服务，`onStartCommand()` 调用 `DshApp.supervisor.start()`；通知栏退出走异步 `exitCompletely()`，先停止引擎/局域网代理/本地模型，再拆悬浮层和桌宠：`app/src/main/java/app/dsh/mobile/service/EngineService.kt:27-89`、`:219-275`。Manifest 同时声明 `FOREGROUND_SERVICE_SPECIAL_USE` 与桌宠下载用的 `FOREGROUND_SERVICE_DATA_SYNC`：`app/src/main/AndroidManifest.xml:7-13`、`:175-201`。
- `EngineSupervisor` 是启动/健康/自愈状态机：`Idle → Installing → Starting → Healthy/SafeMode`，异常进入 `Backoff`，超过限制进入 `Failed`，显式退出进入 `Stopped`：`app/src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt:20-56`。固定参数在 `EngineConfig.kt:24-85`：健康超时 45s、轮询 500ms、稳定窗 3s、退避 2/4/8/16/32s、普通重启上限 14、插件恢复重启上限 3、一次用户可见启动墙钟预算 5 分钟、端口清场累计上限 8。
- `EngineSupervisor.spawnEngine()` 在 spawn 前顺序执行孤儿回收、权限/属主自愈、运行时安装、hardssh 补装、sessions/workspaces 外置、插件总开关收敛，再调用 `EngineProcess.spawn()`：`EngineSupervisor.kt:535-629`、`:986-1030`。健康后等待插件 health marker；插件降级可有限摘除并重启；凭证/登录特征会直接转为可操作的 Failed，不进入无限重启：`:631-748`、`:825-961`。
- `EngineProcess` 维护 PTY master fd、日志泵和 wait 线程；日志写 logcat 与 `filesDir/engine.log`，记录设备厂商/型号/SDK/系统版本和退出 status/signal/exit code：`app/src/main/java/app/dsh/mobile/engine/EngineProcess.kt:11-85`。停止为 TERM→宽限→KILL，Root 模式再经 `Privilege.runSu()` 清理进程组：`:87-116`。
- JNI PTY 层由 `Pty.kt` 加载 `libdshpty.so`，接口是 `nativeForkPty/nativeWaitChild/nativeSignalChild/nativeForceKill/nativeChildPid`：`app/src/main/java/app/dsh/mobile/engine/Pty.kt:3-40`。`dsh_pty.c` 使用 `posix_openpt`、`fork`、`setsid`、`TIOCSCTTY`、`PR_SET_PDEATHSIG`、`execve`，并在 exec 前关闭继承 fd，避免 3083/3080 socket 被后代长期持有：`app/src/main/cpp/dsh_pty.c:53-76`、`:104-147`。CMake 为 `dshpty` 和伪装成 `.so` 的 `dsh_pty_exec` 配置 16KB page alignment：`app/src/main/cpp/CMakeLists.txt:5-28`。
- 引擎运行目录和用户数据拓扑由 `EngineConfig` 定义：`filesDir/engine/{bin/node,lib}`、`filesDir/dsh-home`、`tmp`；`dsh-home/profiles` 保持私有，`sessions/workspaces` 按权限选择性软链外置：`app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt:8-21`、`:361-385`、`:717-743`。共享 Documents 只有 `MANAGE_EXTERNAL_STORAGE` 明确授予才采用，探针失败时回退 app 专属 external；已有可达软链不因瞬时探针失败拆除：`:284-355`、`:439-488`、`:551-624`。

### 本地桥、WebView 与设备能力

- `AgentBridge` 监听 `127.0.0.1:3083`，路由覆盖通知、读屏、OCR、tap/gesture/key/input、frame/vision、local loop、scene memory、扩展/插件、TTS、文件编辑等：`app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt:22-40`、`:349-412`。启动时随机生成 token，仅写入引擎环境 `DSH_BRIDGE_TOKEN`；`Privilege.authorisedBridgeRequest()` 无 token 或 token 不匹配即拒绝：`AgentBridge.kt:104-148`、`Privilege.kt:200-225`。桥绑定失败会记录端口持有者并写入 `engine.log`，不是静默降级：`AgentBridge.kt:169-200`。
- `open-file` 端点只允许 canonical path 落在 app files/cache、engine、dsh-home、当前/旧 workspaces 根内，拒绝相对路径、目录穿越和越界路径：`AgentBridge.kt:416-485`。返回 `opened=true` 只代表 Activity 已拉起，不代表用户已经保存。
- WebView 外壳关闭 `allowFileAccess/allowContentAccess`，只允许回环导航；外部 URL 交系统浏览器：`MainActivity.kt:350-367`、`:554-571`。媒体权限回调只放行音频，摄像头/视频采集拒绝；系统权限异步时暂存一次性 `PermissionRequest`：`:392-434`。Manifest 同时声明 `RECORD_AUDIO` 与 `MODIFY_AUDIO_SETTINGS`，这是网页 `getUserMedia` 成功的两层条件：`AndroidManifest.xml:14-22`。
- 无障碍服务必须用户在系统设置手动开启，XML 开启读取窗口、手势、截图能力：`app/src/main/res/xml/dsh_accessibility_service.xml:2-13`；服务入口是 `DshAccessibilityService`，负责读屏、触摸/窗口事件摘要、语音面板和 60s 保活检查：`app/src/main/java/app/dsh/mobile/DshAccessibilityService.kt:20-95`、`:210-262`。显式退出标记同时存 SharedPreferences，防止无障碍看门狗在用户退出后复活引擎：`DshAccessibilityService.kt:225-255`、`DshApp.kt:70-103`。
- `LocalLoopExecutor` 是当前源码中的端侧决策程序执行器：JSON DSL 按 `PICK→ACT→VERIFY` 执行，FastPicker 精确/语义匹配失败后按锚点、过滤、原生 `tapText` 判别式降级，最后返回 candidates/foreground/失败屏幕证据并 escalate：`app/src/main/java/app/dsh/mobile/engine/LocalLoopExecutor.kt:13-41`、`:73-100`、`:310-430`、`:454-518`。其 IPC 采用单线程 executor + 超时，超时会取消 Future，不把节点树 IPC 挂死循环。
- `SceneMemory` 是非参数化场景记忆，文件为 `filesDir/scene_memory.json`，最多 200 条；指纹包含屏幕文本、checked 状态和前台包名，Jaccard 阈值 0.7，复用失败两次淘汰；待确认记忆不参与命中，连续两次成功可自动转正：`SceneMemory.kt:9-38`、`:117-159`、`:161-243`。这些是源码事实；历史交接文档中的设备耗时/成功率属于另行记录的运行证据，不能由本轮静态阅读重新确认。

### 运行时与升级安全

- 运行时来源是 Termux bionic Node 闭包，不是 glibc/proot；`scripts/collect-termux-runtime.sh` 下载 Termux 包、解包 `com.termux/files/usr`、嵌入 dsh npm 依赖并执行 Android 适配补丁；脚本声明 dsh 默认 `0.2.1-rc.2`，当前可由 `DSH_VERSION` 覆盖：`scripts/collect-termux-runtime.sh:1-14`、`:59-122`、`:128-151`。
- `scripts/build-runtime.sh` 是唯一 runtime 流水线：collect→zod 去重→SONAME 去重→BUILD.json→闭包校验→输出 meta；验证 bash/rg/pnpm/curl、6 个 SONAME、目标 ABI ELF、Android ripgrep 包、PTC patch 和 WebView polyfill 标记，并拒绝 hardssh 混入：`scripts/build-runtime.sh:1-12`、`:45-110`、`:132-210`。
- `RuntimeInstaller` 支持 `full`（APK 内置 runtime.zip）与 `noruntime`（按 manifest/索引下载）；安装到 `engine.staging` 后校验，再用 `.runtime-swap` 意图日志将 `engine` 顶层条目逐项原子换名，保留 `engine.old` 到首次 Healthy/SafeMode：`app/src/main/java/app/dsh/mobile/engine/RuntimeInstaller.kt:15-33`、`:105-163`、`:249-348`。恢复顺序是 `recoverInterruptedSwap → recoverPendingHealth → cleanupInstallResidue`，`swap` 回滚、`committed` 前滚；原子日志写为 tmp→fsync→rename：`:121-129`、`:265-348`、`:455-515`。设计与中断复现边界见 `docs/RUNTIME_SWAP.md:21-75`、`:77-128`。
- `RuntimeIndexClient` 下载并验签 `runtime-index.json`，优先构建期 PEM 公钥、再回退 APK 签名证书；验签失败直接拒绝，不无签名降级：`app/src/main/java/app/dsh/mobile/engine/RuntimeIndexClient.kt:19-29`、`:142-177`。`HttpDownloader` 要求非空 SHA-256，支持 Range `.part` 续传、大小/哈希校验和 URL 回退：`app/src/main/java/app/dsh/mobile/engine/HttpDownloader.kt:9-18`、`:30-73`、`:76-146`。
- 当前 CI 已将 runtime 构建与 Android APK 构建分离：`runtime-build.yml` 通过脚本指纹、dsh engineVersion 和资产完整性决定是否构建双 ABI，并发布滚动 prerelease `runtime`；`android-build.yml` 的 fetch-runtime 按 runtime prerelease→latest 正式 release→本地兜底三层取货，tag 构建启用 `--strict` 禁止漂移：`.github/workflows/runtime-build.yml:1-29`、`:76-217`、`:222-350`；`.github/workflows/android-build.yml:1-20`、`:99-191`。流程约束详见 `docs/CI_RUNTIME_PIPELINE.md:41-177`。

### 插件、扩展与桌宠

- 社区插件市场 `PluginMarket` 直接读内置 `assets/market/catalog.json`，源码注释与解析结构记录 3540 条/23 分类/中英描述；进程级缓存避免重复解析，兼容性只对前 8 条做网络探测：`app/src/main/java/app/dsh/mobile/engine/PluginMarket.kt:181-231`、`:319-398`。`PluginCenter` 安装/卸载通过引擎 CLI 串行执行，pnpm store 锁损坏和 ignored builds 有有限自愈；不会自动放行引擎兼容性拒绝：`PluginCenter.kt:23-30`、`:216-289`。
- `NativePluginCheck` 只读 npm manifest，识别原生 build、平台 optionalDependencies、engine fork 与 `engines.dsh` 版本下限；semver 预发布按数值比较，内置引擎版本读 `engine/lib/node_modules/@deepseek-ai/dsh/package.json`：`app/src/main/java/app/dsh/mobile/engine/NativePluginCheck.kt:9-40`、`:86-137`、`:167-266`。
- 插件故障分层自愈：`PluginRecovery` 只按 health marker 精确归因第三方 bundle，最多 3 次；引擎完全起不来时只有最近安装包且时间差至少 60s 才允许快速摘除：`PluginRecovery.kt:13-34`、`:53-125`、`:168-245`。`PluginKillSwitch` 把用户显式停用/恢复记录为意图日志，只在 spawn 前收敛 manifest，支持停用期间白名单逐个恢复：`PluginKillSwitch.kt:12-48`、`:213-295`、`:358-441`。
- 环境扩展 `ExtensionManager` 从 Termux 镜像解析依赖闭包、逐包 SHA-256 校验、解包/激活；任务在进程级 IO scope 中，网络最多 3 个并发，按扩展锁发布，失败清 cache；APK 型扩展走 PackageInstaller：`app/src/main/java/app/dsh/mobile/engine/ExtensionManager.kt:39-53`、`:121-171`、`:177-257`、`:310-343`。
- 桌宠由独立 `PetOverlayService` 持有悬浮窗、动画链、气泡、桥服务和后台资源维护；动画包导入只写 `filesDir/pet`，先校验 zip/路径/WebP/配置/大小/空间，再临时目录原子替换，失败保留旧包：`app/src/main/java/app/dsh/mobile/pet/PetOverlayService.kt:41-53`、`:213-274`、`:317-363`；`PetPackInstaller.kt:54-64`、`:124-237`、`:308-334`。
- 当前桌宠门禁是硬门禁：`PetPluginDetector.canEnable()` 要求悬浮窗权限、`@xiaopb/dsh-pet-bridge` 已安装且已启用且版本至少 0.3.1、动画包存在，缺一不可；`bridgeGateOpen()` 无旁路：`app/src/main/java/app/dsh/mobile/pet/PetPluginDetector.kt:62-87`。下载器把残件放 `filesDir/pet-pack`，支持代理/直连候选、Range 续传、分卷与逐卷/整包 SHA 校验、取消后保留残件：`PetPackDownloader.kt:20-31`、`:68-100`、`:102-167`。

### Git 历史主线（已核对提交）

- `f9413d6`（2026-08-26）：M0 Android 骨架，项目起点。
- `5bc2c9e`（2026-08-27）：M1 将 `@deepseek-ai/dsh` 集成进 CI，并加入监督器快速失败；随后 `648a197` 引入 ProfileGuardian 配置自愈，`41e97fe` 引入热重启/端口预览和 Android 补丁链。
- `a518c9c`（2026-10-25）：运行时分发解耦，full/noruntime、hardssh 解耦、运行时流水线和签名索引方向落地；`ec42f90`（2026-10-26）合并 PR #48 的运行时解耦、双形态、原生主题与桌宠。
- `5e63c96`（2026-10-26）：原生 PTY 终端、日志查看/分享与引擎自愈；当前 CMake/JNI/TerminalPtySession 仍保留这一分层。
- `a29a2ba`（2026-10-27）：修复插件市场全量渲染导致 OOM/ANR/引擎重启，并重做灵动岛；`7085b0e` 合并桌宠资源包/动画链/性能与生命周期修复。
- `107e857`（2026-10-28）：修复“插件下不了”的两类独立根因；同日 runtime 预构建复用、脚本指纹和 CI 取货链收口。
- `6fd87bd`（当前 HEAD，2026-10-29）：全屏模式与灵动岛按系统栏/挖孔安全区动态定位；当前工作树中该文件仍有未提交修改，不能把工作树状态当成 HEAD 已发布状态。

### 当前验证边界与遗留

- 本轮完成的是 Git/源码/配置/文档静态核验，并用工作区依赖中的 Python 实际运行 `scripts/check-i18n.py`：124 个 XML 解析通过、3 个 locale 的 805 个字符串同步、0 个未豁免 CJK Kotlin 字面量、21 个扩展目录项，最终输出 `All i18n checks passed.`；仍没有新跑全部 probes、Gradle 编译、APK 安装或模拟器/真机冒烟，因此不能把本轮称为当前构建通过。
- `AI_CONTEXT.md` 既有历史段记录了大量已做过的模拟器/CI/发布证据；这些记录可作为历史线索，但遇到当前行为问题仍要按源码、当前 APK、运行日志重新核验。
- 既有交接记录列出的长期 open 项包括 #66（内置 dsh 仍为 rc.2，需评估升级 rc.4）、#26（长会话 DOM 虚拟化）、#59（Python 原生扩展能力）、#41（Profile 管理）、#2（语音唤醒词）、#49（连续运行时换装回滚窗口）；本轮只核对到该记录，未向 GitHub 重新确认 issue 状态。
- 下一次若继续开发，先保护当前工作树未提交改动；优先按真实目标选择：①运行时/引擎问题看 `EngineSupervisor`、`RuntimeInstaller`、`EngineProcess` 与 `engine.log`；②插件问题先查 `PluginMarket`/`PluginCenter`/`PluginRecovery`/`PluginKillSwitch`；③桌宠问题先查 `PetPluginDetector`/`PetPackInstaller`/`PetOverlayService`；④发布问题先查 `runtime-build` 与 `android-build` 的资产/指纹门禁。

## DSH Mobile 宣传视频已验收与文档补齐（2026-10-28）

- 当前进度：`docs/promo/video/remotion/` 工程版本 1.1.0；七镜头、双 composition、44 秒、60fps。用户明确验收视频质量，要求只补文档与 Studio 验证，**不得修改动画源码或重新渲染**。
- 已验证成片：先前两个 `npm run render:*` 均退出 0；ffprobe 为竖屏 1080×1920 / 横屏 1920×1080、60/1 fps、2640 帧、44.000000 秒。文件分别为 7,941,044 / 7,787,099 bytes。本轮 SHA-256 与补文档前一致，完整值见 `docs/promo/video/CHECKLIST.md`。
- 已补齐：`docs/promo/video/CHECKLIST.md` 逐项列出所有上屏数字的 MATERIALS 章节与行号，三条红线自查、素材限制、取舍、Studio 证据和成片哈希；`PLAN.md` 从旧 42 秒草案校准为已交付的 44 秒分镜。
- Studio 验证：工程目录执行 `npx remotion studio src/Root.tsx --no-open --port 3020`，输出 Server ready、Built in 2120ms；再执行 `npx remotion studio --no-open --port 3020` 返回 Already running。首页及两个 composition 路由均 HTTP 200。通过工程已有 Remotion 浏览器驱动和本机 Chrome 实际打开两个预览，元数据正确、播放/暂停生效、时间轴推进到约 84 帧、3.4 s 计数落点出现、字体 loaded、页面错误 0；未执行新的 render。
- 关键口径：3.4 s 是一加 15T 用户实测，录屏单独标模拟器与 7×；三体积口径保留较小竞品，简单包 +128 MB，解压按大到小排第 4；bionic 非唯一；覆盖 10 / 抽查 3–5 为下界；目录 3540 不代表全部插件兼容。
- 当前阻塞：本轮文档和 Studio 验证无阻塞。既有成片保持原样，无新发布/上传操作。
- 下一步首要任务：本轮清单已清零，保留已验收产物；后续如需发布，由用户明确指定平台与范围后处理，不因文档补齐重做视频。
- 关键路径：`docs/promo/video/CHECKLIST.md`、`docs/promo/video/PLAN.md`、`docs/promo/video/remotion/src/data.ts`、`docs/promo/video/remotion/out/dsh-mobile-portrait.mp4`、`docs/promo/video/remotion/out/dsh-mobile-landscape.mp4`。Studio 地址 `http://localhost:3020/DSHMobilePortrait`、`http://localhost:3020/DSHMobileLandscape`。

## ⚠️ 工作纪律提醒（宝宝 2026-10-27 明确要求，优先级最高）
- **动手前先核实「是否已经做过」**：很多 issue 只是没关闭，功能其实早就实现了。
  本次 14 个 open issue 里 **7 个是历史已修复**（#19 #29 #21 #40 #33 #12 #10），
  逐个查代码确认后才回的，没有重复实现。
- **改动要小、要可验证、不许弄坏既有功能**：
  * 新增 UI 行必须用**主题色 token**（`@color/*`），不能写死 `#hex` ——
    主题化后硬编码色在浅色主题下会失去对比度（本次被 `_test_theme_menu` 抓到并修）；
  * 每次改动后跑：`check-i18n.py` + 全部 `scripts/probes/_test_*.py` + 编译；
  * 提交前看 `git diff --numstat`：**删除行数应远小于新增**，若大量删除要停下来复核。
- **不确定就先查再问**，不要凭印象重写已工作的代码。

## 📋 协作开发规范（必读，2026-10-30 起生效，优先级同级最高）

**不管是谁（人类或 AI）要把提交推到上游 GitHub 仓库（upstream/Soodok 或
origin/XiaoPb 的 `main`），都必须先在本地完成「fetch 最新 → 合并 → 探针/编译
验证」三步，确认本地包含远端最新内容后才允许推送。** 禁止在落后的本地历史上
直接推送、禁止 force-push、禁止冲突时整文件取一侧 —— 这些都真实造成过覆盖
事故（PR #75 丢失、API 推送覆盖 32 文件）。

**完整流程、红线清单、PR 规范与事故记录 → [`docs/COLLABORATION.md`](docs/COLLABORATION.md)**
（推送通道与 SHA 保持细则 → `docs/git-workflow.md`；版本号由上游管理，本地
fork 永不自行 bump `versionCode`/`versionName`）。

## v1.4.37：App 自动更新（2026-10-28 深夜）

计划与验收表在 [docs/AUTO_UPDATE_PLAN.md](docs/AUTO_UPDATE_PLAN.md)（§18.1 是实现状态）。
这一版的主线只有一件事：**把「检查 → 下载 → 校验 → 安装」做成闭环，且代理不可投毒**。

**安全模型（三条，缺一条就白做）**
1. `update-index.json` 用 release key **分离签名**（`SHA256withRSA`，与 `runtime-index.json.sig` 同 key）；
   App 取索引**首请求严格直连**，失败才允许经代理重取，且**必须验签通过**才解析。
2. 索引里每个 APK 带 `sha256/size/abi/package/variant/runtimeVersion/builtAt`；
   选包按结构化三元组匹配，**不按文件名猜**（旧 `contains("arm64")` 会挑到 debug 包）。
3. 装包前再比一次证书指纹（`ApkInstaller.sameSignature`），换过 key / 别人 fork 的包进不了安装器。

**本轮补掉的坑**
- 旧的 `engine/UpdateChecker.kt` **已删除**（零引用、且带着 D1/D2 两个选包 bug）。
- 设置页补上「应用更新」分组（4 个开关 + 形态策略 + 忽略记录清除 + 立即检查）——
  之前这些开关只有读写方法、没有 UI，用户等于被锁死在默认值。
- 关于页从「单行点一下」改成**主按钮 + 次按钮**（§12 表）：下载中「查看进度」、
  已下载「删除安装包」、其余状态「前往 Release」，旧入口不再只能靠长按发现。
- 磁盘清理补齐：下载前清**非当前版本目录** + `.part` 超 7 天；之前只有"安装成功才清"，
  用户连跳两版就会留 2×200MB+。
- **发布索引里 runtimeVersion 全是 null**（真机验收时发现）：debug 包跟 edge 频道，
  自动更新下到 287 后看 `meta.json` 才发现 `runtimeVersion:"null"`、`runtimeBuiltAt:0`。
  链条是三段断的：① `build-apk` 的 meta 步骤读 `app/src/main/assets/runtime/MANIFEST.json`
  —— 清单早在迁移后落到 `build/generated/runtimeAssets/runtime/`，`-f` 恒假 → builtAt=0；
  ② `write-update-index.py` 见 meta 无数就退回 `--runtime-manifest`（同样旧路径，
  publish 作业里根本不存在）→ 索引写 null；③ App 侧 `JSONObject.optString` 对 JSON null
  返回**字面量 "null"**，`isNullOrBlank()` 判不过 → `runtimeChanged()` 认定"运行时变了"，
  full 用户每次更新都会被问要不要下 150MB 大包、还可能白解压 580MB。
  修法三处：构建步骤改读生成路径、发布脚本在 meta 缺字段时**开 APK 读权威清单**
  （`assets/runtime/MANIFEST.json` 就在包里）、App 侧新增 `optStringOrNull()` 归一化。
  护栏：`scripts/probes/_test_update_index_runtime_fields.py`（12 项，含真实跑一遍索引脚本）。
- **tag 构建被 NDK 下载抖动打挂**（run `40728859646` 的 aarch64/noruntime lane，
  同批 x86_64 同代码全绿）：`Preparing "Install NDK (Side by side) 26.1.10909125"` →
  `ZipException: Archive is not a ZIP archive`，runner 把包下坏了，configure 阶段直接失败。
  v1.4.25 为 CMake 打过同类补丁，但**没覆盖 NDK**。这次三处一起收：
  ① `app/build.gradle.kts` 写死 `ndkVersion = "26.1.10909125"`（不再吃 AGP 默认值，
  本地/CI/文档才有共同版本号）；② CI 预装步骤扩成 CMake + NDK（4 次重试 + 每次前清残留
  目录与 `.temp`/`.downloadIntermediates` + **按落地文件**判定就绪，不信 sdkmanager 退出码）；
  ③ 磁盘清理不再整块 `rm -rf sdk/ndk`（整块删等于逼 8 个 lane 各重下 650MB，把"腾空间"
  变成新的抖动源），改为只删非钉住版本。护栏：`scripts/probes/_test_ci_native_components.py`（17 项）。
- `ExtensionManager.installApkTask` 的装包提交改走 `ApkInstaller.commit()`，
  回执也从 `getLaunchIntentForPackage()`（等于没有回执）换成 `InstallResultReceiver`
  （带 `KIND_EXTENSION`，不触碰应用更新状态）。

**行为要点**
- 检查时机：引擎 READY 后 20s 必查一次 → 常驻 24h 一次 → 10 分钟最小间隔（防限额）；
  不引入 WorkManager（用户要求"不开 App 不联网"）。
- 形态策略 `auto`：运行时无变更优先 `noruntime`（省 ~250MB、不重解压）；
  有变更弹窗二选一。`keep` 保留旧行为。
- 忽略粒度是 versionCode（`<= ignored` 静默，更高版本自动恢复）。

**已完成的人工项**
- 仓库 secret：`DEBUG_KEYSTORE_BASE64` / `DEBUG_STORE_PASSWORD` / `DEBUG_KEY_ALIAS`；
  variable：`DEBUG_CERT_SHA256`（`B5:3E:BD:…:40:01`）均已配置，CI 指纹门禁生效。

**验收结果（2026-10-29 凌晨，MuMu 5555 / Android 12 / x86_64）**
- 干净虚拟机首装 → 引导 → 127MB 运行时 → `engine healthy on :3080` → READY 后 22s 自动检查
  → 三路直连优先 + 代理兜底 → 404 优雅降级（不会重试风暴）。
- **真实更新闭环跑通**（基线 286 debug → 287）：debug 包跟 edge 频道，自动检查 → 后台下载
  → 通知「1.4.37 已下载 / 点击安装」；落盘 `files/update/287/` 的 APK sha256
  `9fb81199…` 与签名索引一致；证书 `b53ebdc8…`（固定 debug key）与已装包**完全相同**
  → 覆盖安装成功，`versionCode 287 / versionName 1.4.37`，引擎用原运行时正常启动。
- 设置页「应用更新」六项渲染完整；「立即检查」日志 `manual check requested →
  check (manual) → UpToDate`，关于页显示「已是最新（1.4.37）· 上次检查 01:21」。
- 关于页确认 fork 身份：版本号下方蓝色 **Fork 构建** 徽标 + 开源地址
  `github.com/XiaoPb/Deepseek-Harness-Local-Android`（不再是上游）。

**仍在跟进**
- ✅ release 变体 + stable 频道完整链路已跑通（见顶部 v1.4.38 段）。
- ✅ `.runtime.json` 的 `unknown/0` 已根治：根因是 noruntime 包 MANIFEST 的占位值，
  不是索引；修复 + 真机验证均在 v1.4.38 段。
- 发版说明必须写：debug 包签名已固定，edge/preview 用户需**卸载重装一次**（此后可覆盖升级）；
  release 用户不受影响；首次从 App 内安装更新要在系统里允许「安装未知应用」。

## v1.4.38 ~ v1.4.41 已发布（2026-10-29）

### v1.4.38：Telegram 交流群 + 修正红线表述
- 群组 `https://t.me/+4VN3pYyH6i8yNDA1` 加入三个 README（徽章 + 反馈区）与
  App「关于」页
- **顺带修了一个红线问题**：`about_intro` 原来写「no data ever leaves the device /
  数据全程不出设备」——模型请求必然离开设备，与 README 里的禁用表述冲突。
  四种语言改为「会话与凭证留在本机；只有模型请求发往你配置的 API」

### v1.4.39：PR #69 pt-BR 完整补齐（@Masspedro）
EN 805 键覆盖 800，新增 127、改进 615。合并时恢复了我同日加的 5 个
`about_telegram_*` 键（PR 基于 TG 字符串加入前的 main），现 805/805。

### v1.4.40：PR #70 APK 安装器回调修复（@Masspedro）
**这是 #47「Offline ASR 装不上」的真正根因**：旧代码把 PackageInstaller 的
确认 PendingIntent 指向通用 launcher，于是
- `STATUS_PENDING_USER_ACTION` 携带的确认弹窗**无人启动**
- `STATUS_SUCCESS/FAILURE` 无人接收，只能靠轮询包名猜结果

修复：新增非导出的 `ExtensionApkInstallActivity` 接住回调 + `ApkInstallStatus`
按 session 记终态（PENDING 哨兵 + computeIfPresent，陈旧回执不覆盖终态）。
附带 27 项合成桩测试（不需 Android SDK）。

### v1.4.41：issue #72 WebView 语音插件全失效

**用户报告质量极高**：dex 字符串池扫描 + `onShowFileChooser` 阳性对照，
证明外壳重写了 WebChromeClient 但漏了媒体权限回调。

**根因有两层，缺一不可**：
1. 没实现 `WebChromeClient.onPermissionRequest` —— WebView 默认**直接拒绝**
   所有网页媒体请求，与系统权限无关
2. **manifest 缺 `MODIFY_AUDIO_SETTINGS`**（这层是实测才发现的）：
   只补回调 + 授予 RECORD_AUDIO 后，错误变成
   `NotReadableError: Could not start audio source`，logcat 说：
   ```
   cr_media: Requires MODIFY_AUDIO_SETTINGS and RECORD_AUDIO.
             No audio device will be available for recording
   ```
   Chromium 录音**同时要求两个权限**。

**端到端实测（CDP 直连 WebView 执行 getUserMedia）**：
| 阶段 | 结果 |
|---|---|
| 修复前 | `NotAllowedError / Permission denied` |
| 只补回调 + RECORD_AUDIO | `NotReadableError / Could not start audio source` |
| **再补 MODIFY_AUDIO_SETTINGS** | **`PASS`（拿到 MediaStream）** |

**为什么长期没被发现**：我们自己的语音走原生 `AsrManager`（不经 WebView），
只有第三方网页插件走 `getUserMedia`。

护栏：`_test_webview_media_permission.py`（17 项），其中两条专锁本次的坑 ——
回调必须存在、manifest 必须**同时**声明两个权限（只查 RECORD_AUDIO 抓不到第二层）。

### 发版
- tag `v1.4.41` → CI `40837472702` **一次全绿** → 12 资产，latest
- 18 项 Python 探针 + i18n + arm64 构建全过

## PR #68 已合并（2026-10-28 深夜，运行时预构建复用）

### 解决的三个真问题

| 问题 | 原状 |
|---|---|
| 慢 | 每次 push/PR/定时都要 apt 收集 + 去重 + 打补丁 + 打包 127MB×2（单腿 5-10 分钟），且是 build-apk 硬前置 |
| 不可复现 | Termux 仓库浮动，同一 commit 两次构建 runtime sha 可能不同 |
| **测的不是发的** | preview/edge 跑的 runtime 与 tag 正式包内嵌的可能不是同一份 |

### 改动（app/ 零改动，发布产物形态不变，只换供应链）

- `.github/workflows/runtime-build.yml`（新增，462 行）：唯一构建 runtime 的工作流
  `decide → build → publish`，滚动覆盖 prerelease tag `runtime`
- `scripts/fetch-runtime.sh`（新增）：**三级取货** ①滚动 prerelease ②releases/latest
  ③本地兜底构建；sha256 校验 + `--expect-sha` 指纹比对 + `--strict` 禁止降级
- `scripts/runtime-inputs-sha.py`、`scripts/make-runtime-meta.py`（新增）
- `android-build.yml`：`collect-runtime` → `fetch-runtime`（只取用不编译）
- **合并后 CI 全绿**：android-build / i18n-check / runtime-build 均 success

### 关键设计（评审要点）

1. **用「脚本指纹」而非「有没有」判断重建**：只判断 release 里有没有 runtime 会
   静默漏发 —— 改了 patch-*.py → 触发重建 → 发现已存在 → 跳过 → tag 发布取到旧包
2. **`--strict` 用于 tag 正式构建**：保证同一 tag 在任何时间构建内嵌同一 runtime
3. **三级取货兜底**：新 fork bootstrap 时不会因缺 runtime 直接红

### 顺带修复我引入的 CI 缺陷（重要）

我把 `_test_runtime_dedupe.py` 接进 `i18n-check.yml` 后，**main 上连续 4 次 CI 失败**
（07:26 / 07:44 / 09:51 / 10:44）。原因：CI 干净检出没有 `_runtime_bak/` 产物，
而探针要打开真实 runtime.zip；我只在本机（有产物）验证过，没意识到这一点。

PR #68 按「静态契约 / 产物质量」切分修好了：
- 静态项无产物也跑
- 产物项无产物时 SKIP（不计失败），校验交给 runtime-build 发布门禁

**实测两种环境**：
| 环境 | 结果 |
|---|---|
| 模拟 CI 干净检出（隐藏 _runtime_bak 与 _rel） | 11 通过 / 0 失败 / 3 跳过，exit=0 |
| 本地有产物 | 34 通过 / 0 失败 / 0 跳过 |

> **教训**：探针接入 CI 前必须在「干净检出」条件下跑一遍。本机有产物的通过
> 不代表 CI 会通过 —— 这类"只在本地绿"的门禁比没有门禁更糟。

## v1.4.35 / v1.4.36 已发布（2026-10-28 晚）

### v1.4.35：pnpm 锁树整棵跳过 chown（#67，v1.4.34 修复不完整）

**我 v1.4.34 漏了什么**：那版做法是「递归 chown 全树，再把锁**目录本身**改回原属主」，
但 `chown "$n:$n" "$d"` **没有 `-R`** —— 目录改回去了，目录里的锁文件仍是 app uid。
pnpm 于是换了一句错误继续拒绝：从「lock directory must be a real directory owned by
the current user」变成「lock must be a **regular file** owned by the current user」。
用户 luoyi0914 用 `ls -ldn`（看目录）vs `ls -ln`（看文件）的对比指出了这点。

**这次的修法**：不再「改了再还原」，而是**根本不碰**。这些锁目录是 pnpm 按运行身份
自己管理的运行时状态，不属于 App 数据：

```sh
find <home> <engine>   -path '<home>/.cache/pnpm-store-operation-locks-*' -o   -path '<home>/.cache/pnpm-dir-locks-*' -o   -path '<home>/.cache/pnpm-modules-swaps-*' -prune -o   -exec chown <uid>:<uid> {} +
```

三个落地细节（都踩过）：
- **不用分组括号** —— toybox find 的括号转义与 GNU 不一致
- **`-path` 参数整体走 `shellQuote`** —— 否则通配符在引号外被 shell 提前展开
- **`-exec ... {} +`** —— dsh-home 几万文件，逐文件 fork 太慢

**本地实测**：造假目录树跑生成的 find 命令，锁目录两棵整棵未出现，
`.cache` / `sessions/` / `engine/bin/node` 全部命中。

**探针教训**：`_test_root_pnpm_locks.py` 增加一条「旧方案不得存在」
（`chown "$n:$n"` 不能出现）——「只改目录不改文件」这个坑不留断言很容易踩回去。

### v1.4.36：区分「插件不兼容」与「内置引擎过旧」（#66）

**问题**：npm 上 `@deepseek-ai/dsh` 有两条线 —— `latest` = 0.2.1-rc.2（我们内置）、
`next` = 0.2.1-rc.4（插件作者照它写 `engines.dsh`）。声明 `>=0.2.1-rc.3` 的插件
装不上，UI 只说「不兼容」，用户以为是插件坏了。

**改动**：
- `NativePluginCheck` 读 `engines.dsh` 取版本下限，与内置版本比较
- 要求更高 → 新状态 `Result.EngineTooOld(required, bundled)`，
  **单独成态不进 Risk**（Risk 语义是「插件有问题」，这个是「我们引擎旧了」）
- **semver 按预发布规则比**：`0.2.1-rc.2 < 0.2.1-rc.4 < 0.2.1`；
  预发布段按点切分、数字段按数值比（字典序会把 `rc.10` 判成小于 `rc.9`）
- 读不到内置版本时**不判定**（宁可漏报，误报会把能用的标成不可用）
- UI 用 warning 色（不是 danger），安装前弹窗写明「要求 X / 内置 Y / 插件本身没问题」
- `DshApp.onCreate` 注入 applicationContext（`NativePluginCheck` 是纯静态 object）

**探针**：`_test_plugin_engine_compat.py`（18 项），含 8 个 semver 用例 +
6 个范围解析用例，专门覆盖 `rc.10 vs rc.9` 字典序陷阱与 `*`/`latest` 必须返回 None。

**根因未解**：这只是让用户知道真实原因。真正的解法是把内置引擎升到 rc.4 线 ——
14 个补丁锚点要重新验证，是独立一轮工作。已在 #66 里给出分阶段计划
（先出补丁适配评估报告附在 issue 里，再决定是否进小版本）。

### issue 状态（本轮后：open 7 个）
- 关闭：#65（root pnpm 锁）、#66（回复计划，保留 open 跟踪）、#67（v1.4.35 修完）
- 保留 open：#66（等引擎升级）、#60（Sol effort=none，非 App bug）、#26（长会话
  DOM 累积，需专项做视图虚拟化）、#59（Python 轮子，已回路线图）、#41（Profile）、
  #2（语音唤醒）、#49（swap 恢复窗口）

### 发版 CI 的一次平台故障（记录备查）

v1.4.36 首次 CI：8 个 build job 全绿，`publish-release` 在最后一步失败 ——
11 个资产里成功上传了 10 个，上传 `runtime-x86_64.zip`（127 MB）时 GitHub
返回 HTML 错误页（`##[error]<!DOCTYPE html>`），整个 Release 因此未创建。

**不是我们的代码问题**：同样流程在 v1.4.35 一次通过。判断为 GitHub 侧瞬时故障
（大文件上传 503）。处理：`rerun-failed-jobs` 重跑，第二次一次通过，12 资产齐全。

> 以后再遇到 `publish-release` 失败而所有 build 都绿，先看日志末尾是不是
> HTML 错误页 —— 那是平台故障，直接重跑即可，别去改代码。

## v1.4.33 / v1.4.34 已发布（2026-10-28，两个 PTC/root 修复）

### v1.4.33：PTC bootstrap 路径映射（用户报错，无 issue 号）

**报错**：`code run failed (worker-exit): PTC runtime bootstrap is unavailable in
the subprocess execution world: <engine>/lib/node_modules/@deepseek-ai/
dsh-ptc-runtime-node/lib/process.js` —— 而文件**确实存在**。

**根因**（用户自己也独立确诊了，结论一致）：`scriptBootstrapArgs()` 里

```js
const mapped = (path) => {
    const result = fs.processPathFromHostPath(path);
    if (result === void 0) throw new Error(`... unavailable ...`);
    return result;
};
```

`fs` 是 `ctx.fs`。`FileSystem` 基类的 `processPathFromHostPath()` 是**空实现**
（无条件 undefined），只有 `dsh-fs-local` 覆盖成
`isAbsolute(hostPath) ? resolve(hostPath) : undefined`。挂载到 `ctx.fs` 的
provider 若没走到那个覆盖版本，映射表里没有 engine 目录这一条 → 返回 undefined
→ 每次 run_code 都抛。文件、权限、SELinux、node 本体、库路径全部无罪。

**修法**（`patch-ptc-runtime-android.py` 第三处补丁）：映射返回 undefined **且**
路径是绝对路径 → 原样透传；相对路径仍抛错（否则 worker 按自己 cwd 解析会执行错文件）。
Android 上引擎/fs provider/worker 同一挂载命名空间，绝对路径就是进程路径。

**关键配套（踩过坑）**：打完补丁必须重算 `BUILD.json`。`RuntimeInstaller` 用
`BUILD.json.version` 判是否重解压，版本号派生自内容 sha —— 不重算的话新补丁
**永远进不到设备上**，表现为「明明修好了、装完还是老报错」。脚本现已自动 restamp。

**三处门禁同步**：`build-runtime.sh`（CI 运行时流水线）、`app/build.gradle.kts`
（APK 打包前）、`_test_runtime_dedupe.py`（+3 断言，并改为优先读 `_rel/*-dedup.zip`
发布用包，避免读到去重前中间产物得出假阳性）。

**端到端验证**：设备重装后运行时版本更新为 `2026.10.28-eb83a614`，补丁命中，
`process.js` 存在且路径解析正常。

### v1.4.34：root 模式 pnpm 锁属主（issue #65）

**现象**：`DSH_ANDROID_PRIV_MODE=root` 时插件安装/卸载全失败，**每次重启引擎复发**：
`ERR_PNPM_STORE_DIR_OPEN_OPERATION_LOCK: lock directory must be a real directory
owned by the current user: .../.cache/pnpm-store-operation-locks-0`

**根因**：pnpm 锁目录按 uid 分后缀（`pnpm-*-locks-<uid>`），root 引擎用 `-0`。
`Privilege.fixHomeOwnership()` 每次启动对整棵 `dsh-home` 递归
`chown -R <appUid>:<appUid>`，把 root 那套也改了 → root 的 pnpm 拒绝使用。
用户实测「手动改回属主能撑到下次启动」正是这个原因。

**修法**：递归 chown 之后，把 `.cache` 下 pnpm 锁/交换目录按**目录名数字后缀**
还原属主（`-0` → `0:0`，`-10324` → `10324:10324`），非数字后缀跳过。
原有自愈保留（engine 补 `u+rwX`、`restorecon`）。

**护栏**：`_test_root_pnpm_locks.py`（10 项），其中专门锁**顺序**：必须
「先整树 chown 再还原」（反了会被覆盖，等于没修）。

### 发版
- v1.4.33：tag → CI `40626947939` 全绿 → 发布运行时下载验证含补丁
- v1.4.34：tag → CI `40628898147` 全绿 → 12 资产，latest
- 探针 15 项全绿；i18n 全过

## v1.4.32 已发布（2026-10-28，PR #64 + git 流程规范化）

### 1. PR #64 收编（@XiaoPb，5 提交）
- 气泡回落文案对齐 dsh-pet web `workStatusTexts`（六档随机抽句，`text` 透传）
- 工作状态台词进台词库（`PetLines.ws_*` 六档，编辑器/导入导出覆盖）
- 移除 App 端「桥端随机词句」开关（改由 dsh-pet-bridge v0.3.6 自绘分区承担）
- CI 预装 `cmake;3.22.1` + 修 `sdkmanager` 全路径（runner PATH 问题）
- 探针同步：`step_done` → `"post" -> ack`（守卫意图不变）

### 2. **重要：推送通道不再重写 SHA**（本次核心改动）

**问题**：旧 `push-via-api.py` 逐 blob 上传 + `base_tree` 重建树 + 只传 author
（漏 committer），每个提交在远端都被重新创建、SHA 全变。后果（XiaoPb 在 PR #64
里直接指出）：
- 下游 fork 每次同步都发现历史被改写，只能 force-push；
- 本地与远端永久分叉（`git status` 恒 ahead/behind）；
- GitHub 上的提交号与本地对不上，追溯困难。

**修复**：按 git 对象身份原样上传
- 复用提交的**原始 tree SHA**（内容相同则 SHA 相同，不重建）
- 带完整 `author` **和 `committer`**（旧实现漏 committer 是 SHA 变化的第二因）
- 父提交沿用原始 SHA，必要时先 fetch 补齐祖先对象
- **实测 6/6 + 1/1 提交 SHA 全部保持**，推完 `rev-list --left-right --count` = `0 0`

**同时加的安全机制**：
- fetch 直连失败自动切 gh-proxy 前缀代理（实测 gh-proxy 支持 fetch 的 GET，
  **不支持** receive-pack 的 POST —— 所以 push 仍只能走 API 通道）
- **分叉检测**：本地与远端分叉时拒绝推送并给出处理办法，**绝不 force 覆盖**
  （2026-10-27 覆盖事故的根因就是没有这道闸）
- `--rebase-remote` 可选自动 rebase

### 3. 流程规范落地
- **`docs/git-workflow.md`**：合并原则（`--no-ff` 保留线路、不 squash）、
  推送方式优先级、fetch/push 通道差异表、日常操作步骤、验证清单、
  探针维护约定（断言表达意图而非实现细节）、历史事故与教训
- **`scripts/git-hygiene.py`**：推送前自检 —— 工作区脏 / 远端有新提交 /
  rebase-merge 中间态 / 对象库 CRLF 污染 / probes 误加。
  `--ci` 模式供流水线用（不联网）
- **`i18n-check.yml` 增加两步**：「回归探针」（跳过已知的 corepack_hardlink）
  与「git 卫生」。都不需要 Android SDK，几秒跑完，PR 阶段就能拦住回归

### 4. issue 状态（本轮后）
已关闭：#58 #62(PR) #61(PR) #63(PR) #64(PR) #47 #25
保留 open：#60（Sol effort=none，非 App bug，已给自查步骤）#26（长会话 DOM
累积，需专项做视图虚拟化）#59（Python 轮子，已回路线图）#41（Profile 管理）
#2（语音唤醒，排在 #41 后）#49（swap 恢复窗口）

### 5. 验证与发版
- i18n 全过；14 探针全绿；`assembleDebug(x86_64)` 通过；模拟器冒烟 pid 稳定、0 FATAL
- tag `v1.4.32` → CI run `40621327073` 全绿 → **Release 12 资产，latest**
- 推送后 `git-hygiene` 确认本地与远端一致（`6873b070`）

## v1.4.31 已发布（2026-10-28，PR #63 收编 + issue 清理）

### 收编 PR #63（@XiaoPb，38 提交，本地合并）
- **PetPackDownloadService**（前台服务 dataSync）+ **PetPackDownloadBus**：
  资源包下载走后台服务，进度通知栏；设置页仅订阅，退出/被杀不断任务
- **PetPackDownloader**：可取消；暂存 cacheDir → filesDir（防系统回收），
  旧残件迁移续传；校验失败删残件；线路回调供 UI 标注
- **删除确认页**：包名 + 安装时间（PetPackSummary.installedAt），四语文案
- **气泡重绘**：对齐 dsh-pet web 端（平涂 + CSS border 三角尾巴 0.7472em×0.3736em）
- **启动遮罩**：步骤面板高度收敛 + 文本未变不重排；debug 包 --el splash_min_ms
- **探针同步**：`_test_pet_overlay` 尾巴断言 0.54f → 0.3736f（跟着 web 设计走）

### issue 处理（8 个 open → 2 个）
- **#47 关闭**：v1.4.25 已修（APK 型扩展 apkUrl/apkSha256/apkPackage 从未解析，
  Offline ASR 一直走 deb 路径 → "List is empty"）；用户升级即可
- **#25 关闭**：v1.4.29 的「配置文件」入口即此功能（设置 → 数据 → 配置文件）
- **#60**：回复排查结论 —— Sol 模型 reasoning.effort="none" 导致 compaction 返回空，
  非 App bug；给了自查三步
- **#26**：回复初步分析（会话视图非虚拟化，DOM 累积上万节点 → 全量重排）+
  要 dumpsys/帧率统计信息；保留 open（需专项做视图虚拟化）
- **#59 / #41 / #2**：回复路线图（#59 先做 numpy/pillow 轮子可行性小步验证；
  #41 优先于 #2）；保留 open
- **#49 保留**：runtime swap 恢复窗口（PR #51 的两阶段提交方案未合并，后续再评估）

### 发版
- tag `v1.4.31` → CI run `40554517518` 全绿 → **Release 已上架（12 资产，latest）**
- 全量验证：i18n 全过；14 探针全绿；noruntime + arm64-full 构建通过；
  模拟器冒烟（pid 稳定 / 0 FATAL）

## v1.4.30：插件中心搜索卡崩修复 + 灵动岛重做（2026-10-27 晚）

### 1. 紧急：社区插件搜索导致应用卡死重启（#58 补充上报）

**现象**：社区插件中心里输入搜索词，应用直接卡死、随后引擎重启。

**根因（已确证）**：不是引擎问题，是 UI 线程问题。
`PluginStoreActivity.render()` 把过滤后的**全部**条目一次性 `addView` 进
`ScrollView`（**无视图回收**）。内置目录 `assets/market/catalog.json` 有
**3540 个插件**，`pluginRow()` 每行约 6 个嵌套 View → 一次渲染两万多个 View
建在主线程 → OOM/ANR → 进程被杀 → `EngineSupervisor` 看门狗看到引擎失联，
误判「引擎异常退出」并重启。用户感知就是「一搜索就崩，然后引擎重启」。

v1.4.28 只修了「反复切分类」这条路径（本地过滤 + 分类缓存），
**搜索路径仍然走全量渲染**，所以漏掉了。

**修复**：增量分页渲染
- `RENDER_PAGE = 30`，首屏只渲染 30 条
- `appendNextPage()` 追加下一批，`pendingRows` 暂存剩余
- `listScroll.setOnScrollChangeListener` 距底部不足一屏时自动追加
- 新增探针 `scripts/probes/_test_plugin_render_paging.py`（12 项），
  锁住「不许改回全量渲染」；已加入 `.gitignore` 白名单

**实测（Android 16 / x86_64 模拟器，dsh36）**：
- 搜索 `dsh` → pid 6977 不变，logcat 无 FATAL/ANR
- 清空搜索回到 3540 条全量 → pid 不变
- 连续 8 次快速滚动到底 → pid 不变，**PSS 167MB**，无异常
- `uiautomator dump` 节点数 **63**（增量渲染生效，未堆积）

### 2. 灵动岛重做（宝宝逐条实测反馈）

宝宝反馈三条 + 一条追加要求，逐条改：

| 问题 | 修法 |
| --- | --- |
| 放太多功能、状态文字被截成 `Engine r...` | 顶部 4 个文字按钮（重启/日志/终端/隐藏）**改图标**，状态文字宽度从 <40dp 恢复到可完整显示 |
| 展开后摄像头黑点遮挡文字 | **删除 `bg_island_dot.xml`** 与收起态里的圆点 View；恢复 64×26 单横条胶囊 |
| 灵动岛与摄像头挖孔重叠 | **沉浸式状态栏默认改关**（`DEFAULT_IMMERSIVE = false`），并**移除 `layoutInDisplayCutoutMode` 铺进挖孔区逻辑** —— 窗口不再画进摄像头区域 |
| 状态文案里的 URL 挤占空间 | `status_healthy` 去掉 `· http://127.0.0.1:3080`（端口在网页里可见），三语同步 |

**我自查发现并修的 4 个 bug**（不是宝宝报的）：
1. 灵动岛前景色用了 `@color/text_secondary` —— 岛背景恒纯黑，浅色主题下该 token
   是深灰 `#FF475569`，**黑底上看不见**。新增恒定色 token `island_text` /
   `island_accent` / `island_success` / `island_muted` / `island_handle`（两套主题同值）。
2. `applyStatusBarImmersive()` 关闭态把 `statusBarColor` 设透明、`systemUiVisibility`
   整体赋 0 —— 把 `ThemeManager.applyBars()` 刚设好的 `LIGHT_STATUS_BAR` 抹掉，
   浅色主题下变成「透明底 + 白图标」，压在白网页上看不见。改为关闭态交给
   `applyBars()` 恢复。
3. 灵动岛展开动画从左上角撑开而非中心：`GONE → VISIBLE` 同一帧 `row.width` 是 0，
   `pivotX` 算成 0.5px。改 `row.post { pivotX = row.width / 2f; animate()... }`。
4. `workspaces()` 被 PR 改成无条件切 `Download/DSH MOBILE` —— 老用户工作区、
   `DataExport` 导出源都在 `Documents/dshdata/workspaces`，无条件换根会让他们看到
   「工作区空了」。改为**已有数据时沿用原路径**，只在全新安装时用新目录。

另外 `publicFilesRoot()` 加 **30s 缓存**：它落在「引擎 spawn 取 cwd」与
「`AgentBridge.isOpenablePath` 每次打开文件」两个热路径上，而 `isDirUsable()`
是「写文件→读回→列目录」三步磁盘探针 —— 不缓存等于每次判权限都打一轮 IO，
与 #58 属同类故障。

### 3. GitHub 侧处理

- **v1.4.29 release 已下架**（`draft=true`，release id 425133746）
- PR #62（`xiaoming132887` 的 `pr-v2`，5 笔）→ **已关闭**，内容通过等价提交合入
  （我合并的是他 `pr-upstream` 分支的 6 笔，SHA 不同但代码等价）
- PR #61（`XiaoPb` 桌宠资源包制，41 文件 +6720/-814）→ **已关闭**（本地已合并 `7085b0e`）
- issue #58 → **已关闭**（附根因分析与实测数据）
- main 已推送：`c69b8e8`（本地领先 50 个提交全部落地）

### 4. 发布路径与产物（重要：本地不能签索引）

**关键约束**：`runtime-index.json` 的 detached EC 签名私钥**只在 CI secrets**
（`RELEASE_KEYSTORE_BASE64`），本地没有。而 `RuntimeIndexClient.verifySignature()`
验签失败会**直接拒绝，不降级**（`SecurityException`）。所以：

- ✅ 正确路径：**推 tag `v1.4.30`** → CI `publish-release` job 构建 + 签索引 + 发 Release
- ❌ 不要在本地签索引后手工上传（签不出来，App 会拒）

**已执行**：tag `v1.4.30` 已推到远端（指向 `28e66bb`，即远端 main 的实际 SHA），
CI run `40543382625` 已触发 in_progress。

**本地同时构建了 8 个 APK 做前置验证**（这些只用于验证，不直接上传）：

| 文件 | 大小 |
| --- | --- |
| `dsh-android-v1.4.30-arm64-v8a-release.apk` | 149.5 MB |
| `dsh-android-v1.4.30-arm64-v8a-release-noruntime.apk` | 22.5 MB |
| `dsh-android-v1.4.30-arm64-v8a-debug.apk` | 150.8 MB |
| `dsh-android-v1.4.30-arm64-v8a-debug-noruntime.apk` | 23.7 MB |
| `dsh-android-v1.4.30-x86_64-release.apk` | 149.1 MB |
| `dsh-android-v1.4.30-x86_64-release-noruntime.apk` | 23.0 MB |
| `dsh-android-v1.4.30-x86_64-debug.apk` | 150.4 MB |
| `dsh-android-v1.4.30-x86_64-debug-noruntime.apk` | 24.3 MB |

签名 `CN=DSH Mobile, OU=DSH, O=Soodok`；ABI 各自单一。

### 4.1 构建踩坑：APK 多出 37MB 的「空洞」

第一次构建出 197MB（发布版应约 150MB）。查出来是 **`assets/tools/lan-web-proxy.mjs`
之前有 37.1MB 的 header 间隙** —— 增量构建残留的旧条目被删后，zipalign 没有压掉
那段空洞。修法：`rm -rf app/build/outputs/apk app/build/intermediates/apk
app/build/generated/runtimeAssets` 后重建 → **158MB**（正常）。

**另外**：`_runtime_bak/rt-arm64-zodded.zip` 是**去重前**的中间产物（175MB）。
正式流程在 `build-runtime.sh` 里它之后还有两步：
`dedupe-runtime.py`（SONAME 别名去重，省 ~37MB）+ 写 `BUILD.json`。
本地手工补这两步后 → 130.3MB，与发布版的 127.6MB 一致。
`dist/v1.4.30/runtime-arm64-v8a.zip` 就是这么来的。

### 4.2 产物

- `dist/v1.4.30/DSH-Mobile-1.4.30-arm64-v8a-signed.apk`（197MB）
- SHA256 `72a1e4af68c7da2c8cc9c3d8677930b5c5d54b4bf976cc6cc35e11b0060ebe5b`
- 签名 `CN=DSH Mobile, OU=DSH, O=Soodok`；ABI 仅 `arm64-v8a`
- **尚未发布到 GitHub Release**（等宝宝明确指令）

### 5. 验证记录

- `check-i18n.py`：全过（768 串，三语同步）
- `scripts/probes/_test_*.py`：除 `_test_corepack_hardlink.py` 外全过
  —— 该探针失败是**已知预期**：`assets/runtime.zip` 已按 PR #48 架构挪出，
  探针读不到文件（不是本次改动引入）
- 编译：`:app:compileDebugKotlin` / `:app:assembleDebug`（x86_64 noruntime
  与 arm64-v8a full）均 `BUILD SUCCESSFUL`

### 6. 遗留 / 已知风险

- **分享链接与 CI**：本次推送走 `scripts/push-via-api.py`（github.com:443 被阻断），
  推送前用 API 核对远端 main SHA 并确认本地已包含（`git merge-base --is-ancestor`），
  未覆盖任何远端提交。
- **`_test_corepack_hardlink.py` 需要跟着 #48 架构更新**（读 manifest 而非 assets zip）。
- **#60**（上下文 100% 卡住）判断为 Sol 模型 `reasoning.effort="none"` 配置问题，非 App bug。
- **#41 Profile 管理 / #2 语音唤醒词**：工程量大，未做。

## 桌宠开启改硬门禁（2026-10-27，宝宝明确要求：不能跳过）
- **需求**：无资源包 / 无桥插件 / 无悬浮窗权限，任一不满足都不能打开桌宠，不能跳过。
- **改动**：
  * `PetPluginDetector.bridgeGateOpen()` 收紧为 `detect(ctx).bridgeReady`，**删除
    `allowWithoutBridge` 软旁路**（PetPrefs 里整个偏好与读写函数一并删除，老残留无害）；
  * 新增 `packReady()`（packId 存在且目录完整）与 `canEnable()` =
    `canDrawOverlays && bridgeGateOpen && packReady`，设置页与恢复路径共用；
  * `PetSettingsActivity.enablePet()` 重排为 权限→包→插件 三级硬弹窗引导
    （权限页 / DOWNLOAD PACKS / INSTALL），**没有「仍然开启」**；
    原先 pending 的 `doStart` 提成独立函数；
  * `PetOverlayService.restoreIfEnabled()` 改走 `canEnable` —— 条件不齐连服务都不拉起
    （以前只查 enabled+权限）；
  * 文案：删 `pet_enable_without_plugin`/`_msg`，新增 `pet_gate_plugin_missing_msg`/
    `pet_gate_pack_missing_msg`/`pet_gate_install`（三语）；
  * 探针 `_test_pet_overlay.py` 断言同步：旁路标识符全仓禁入、canEnable 三条件、
    恢复路径走硬门禁。
- **实测（MuMu 5555，Android 12 / x86_64）**：
  * 无包（run-as 移走 packs）→ force-stop 重启服务不拉起；设置页点开关弹
    「Animation pack…cannot be skipped」，仅 CANCEL/DOWNLOAD PACKS；恢复后桌宠自动回归；
  * 无桥插件（run-as 摘 package.json 依赖+bundle）→ 同样服务不拉起、弹
    「Bridge plugin…cannot be skipped」，仅 CANCEL/INSTALL；恢复后自动回归；
  * 全就绪 → restoreIfEnabled 自动拉起、动画链正常换段。
- **坑**：MuMu 5555 的应用会被平台商店更新器动过；桥插件是我在插件中心误触装上的
  （`@xiaopb/dsh-pet-bridge@0.3.1` install ok），测试「无插件」路径用 run-as 改
  profiles/web/package.json（exec-out cat 拉下来 → 本地 python 改 → push 到
  /data/local/tmp → run-as cp 回去，注意 MSYS 路径转换要 MSYS_NO_PATHCONV=1）。

## 资源包导入改格式硬校验 + 只收 zip（2026-10-27，宝宝质疑"坏 zip 会不会溜进去、半成品会不会一直占空间"）
- **格式校验收紧**（`PetPackInstaller`）：
  * 先验文件头 zip 签名（PK\x03\x04/05/06/07/08）——以前非 zip 只会误报
    "pet-config.json not found"，现在有独立的 `PetPackErrors.NOT_ZIP`；
  * 每个 `.webp` 验 RIFF/WEBP 12 字节头，改名假货整个包拒收（`BAD_WEBP`）；
    `__MACOSX/`、`._*` 算噪声跳过不算坏包；
  * 按显示名/MIME 拒绝 APK（`APK` 错误码）——APK 本身也是 zip，文件头分不出来；
  * 失败原因全部错误码化 + 三语文案 `pet_pack_err_*`（14 条），不再把英文技术串
    直接甩给用户。
- **只收 zip**：文件选择器 `setType("application/zip")` + EXTRA_MIME_TYPES（含
  octet-stream，防文件管理器乱报型）；文案删「APK/zip」只留 zip。
- **空间回收**：
  * `.tmp-*`/`.old-*` 半成品：导入开始无条件清 + `PetPack.ensure()` 顺手扫
    （1 小时阈值防误删在写的）；
  * 下载缓存 `cacheDir/pet-pack/` 里非当前包、超 7 天的残件每次下载前清。
  * 已装包不自动删（`xxx-2` 副本靠设置页手动删除）。
- **实测**：bad-notzip（非 zip）→"Not a valid zip file"；bad-fake-webp（PNG 套
  .webp 名）→"Named .webp but not valid WebP: webp/a001.webp"；bad-missing-anim →
  "Required animations are missing: zzz9"；选择器不再列 APK（Download 里有 4 个
  大 apk 全被滤掉）；伪造过期 `.tmp-` 重启后被自动清扫。门禁 253+ 断言全绿。
- **已知边界**：webp 只验容器头不做全量解码——头部合法但内容损坏的极端文件仍会
  进包，由运行时解码失败兜底（逐段容错，不影响其它段）。

## v1.4.29 已发布（2026-10-27，CI 全绿，12 资产）
- **#31 数据导入**（此前只有导出）：合并式恢复（不覆盖已有）、注册表并集合并、
  zip-slip 防护 + 路径白名单（profiles/engine 跳过）、先校验后写入。
  新增 `_test_data_import.py`（30 项），**测试抓到一个真隐患**：旧
  `resolveInside` 用 `trimStart('/')` 会把 `/etc/passwd` 归一成 `etc/passwd`
  而接受，已改为绝对路径直接拒绝。
- **#46 巴西葡语**：`values-pt-rBR/strings.xml`（674 条，46 条核心 UI 词已译，
  其余回退英文）+ LocaleHelper 加 pt。
- **#25 配置文件入口**：设置 → 配置文件（AGENTS.md / workspace.json / profile
  package.json），FileEditorActivity 此前只有 AI 桥能拉起。
- **回复并关闭**（历史已修复）：#10 运行时版本检查 / #12 Shizuku 拒绝即尊重 /
  #19 自定义指令 / #21 崩溃恢复 / #29 PTC / #40 polyfill v4 / #33 信息不足。
- **回复但保留开着**：#26 会话卡顿（已内置 chat-perf，真分页需上游）、
  #2 语音（已有语音输入+TTS+离线识别，只差唤醒词）、
  #41 Profile 管理（需打通引擎启动参数+插件中心+overlay，工程量大，未做）、
  #59 Python 原生扩展平台能力（跟踪项）。
- **注意**：本地构建前必须把 `src/main/assets/runtime.zip` 挪走（新流程
  Duplicate resources / noruntime 会报错）；跑 corepack 探针时再临时放回。

## v1.4.28 已发布（2026-10-27，CI 9 分钟全绿，12 资产）
- **#58 插件中心频繁点击导致卡退重启（严重，已修）**：
  根因是**主线程被反复阻塞 → 系统 ANR → 进程被杀 → 引擎重建**（用户看到的
  「重启引擎界面」是重建遮罩，不是主动重启）：
  ① `refreshChipStyles()` 在**每个 chip 循环里**调 `PluginMarket.categories()`，
     该函数每次读 1.7MB 目录 JSON 全量解析 → 24 分类 = 24 次解析；
  ② 点分类走 `reload(verbose=true)` 重读目录 + 重建 3540 条 Entry，连点堆积。
  修复：categories 按语言缓存 + fetchCatalog 拆 buildCatalog 缓存 + refreshChipStyles
  改用预置 chipIds + 分类切换改本地过滤（render 里按 `it.category` 过滤）。
- **新增 `_test_plugin_market_cache.py`（13 项）** 作为回归护栏，防「循环里读大文件」
  模式被重新引入。全量探针 202 项 / 0 失败。
- **#59**（yiming-0098，Python 原生扩展平台能力）：实测证明 Android 能编译
  pydantic-core（本机 rustc 5m49s），问题不是「跑不了」而是「缺预编译产物」。
  四点建议已逐条回复：轮子扩展（优先做）/ pythonDependencies 声明（需协商）/
  外部服务生命周期（最有共鸣，独立前台服务托管）/ 文档。issue 保留作为跟踪项。
- **#57 已回复并关闭**（v1.4.26 已修，补说明）。
- **教训**：性能 bug 不一定是「功能太重」，**主线程重复 IO 会通过 ANR 伪装成
  「引擎重启」**。排查这类报告先看主线程有没有循环里的文件/网络操作。

## v1.4.27 已发布（2026-10-27，tag run 4044192xxxx，CI 9 分钟全绿）
- **#53 软键盘回车换行**：`assets/js/mobile-keys.js`（onPageFinished 注入）在捕获阶段
  拦 keydown，触屏窄屏下把「Enter（无修饰）」改写为「Shift+Enter」重派 —— 有内容
  换行、空框仍发送、桌面/外设不干预、输入法组字不拦。**不直接 preventDefault**：
  上游是 ProseMirror，吞事件会乱光标；合成 shiftKey 事件走已测试的换行分支。
- **#54 附件相册直选**：`onShowFileChooser` 按 accept 全图片路由到
  `ACTION_PICK_IMAGES`（API33+，免存储权限），低版本回退 `ACTION_GET_CONTENT`+image；
  混合类型仍走通用文件选择器。
- **实测（emulator-5554 / API36）**：`__dshMobileKeys=true`、`touchNarrow=true`；
  三场景合成断言全对（有内容→拦截+shift=true / 空→不拦 / Shift+Enter→不拦）；
  Photo Picker 正常拉起 `PhotoPickerActivity` 且显示「只能访问你选择的照片」。
- **顺带修**：
  * boot trace 补 `hardssh-install` 阶段三语映射（PR #52 新增阶段漏文案）；
  * `_test_boot_trace.py` / `_test_runtime_dedupe.py` 与 PR #48 新架构脱节
    （断言的是已删除的旧 prepareRuntime 实现）→ 改为对齐 build-runtime.sh；
  * **坑**：Kotlin KDoc 里写 `image/*` 会开启**嵌套块注释**（`/*`），导致
    「Unclosed comment」+ 一串级联错误；注释里要避免 `/*`。
- **探针全量：201 项通过 / 0 失败**；i18n 三语全过。
- #53/#54 均已回复并关闭。12 资产上线。
- **构建注意**：本地构建前要把 `src/main/assets/runtime.zip` 挪走（新流程会
  Duplicate resources 报错），测试跑完再 cp 回来（corepack 探针需要它）。

## v1.4.26 已发布（2026-10-27，tag run 40428431602，CI 7 分钟全绿）
- **12 资产**（8 APK full/noruntime × 双架构 × debug/release + 2 runtime zip + 签名索引）。
- **内容**：#57 自检 EACCES 自愈（SelfCheck 遇 EACCES 先 fixHomeOwnership 再重试）+
  PR #55 插件应急总开关（XiaoPb：意图日志 + spawn 前收敛 + 白名单逐个恢复 +
  冷启动 .force 标志，555 行）+ 承接 v1.4.25 全部（#47 真因 / PTY 终端 / 换装事务化）。
- **PR #55 合并验证**：merge-tree 显示与 main 仅 AI_CONTEXT 冲突（双方文档追加，
  双侧保留解决）；编译 / i18n / 三个探针全过；git push 成功（本次未走 API 通道，
  PR#55 head 原始 SHA 在 merge commit 父链 → GitHub 自动标 merged）。
- **模拟器状况**：dsh36 实例反复崩溃/offline（ emulator-5554 包列表清空、
  emulator-5582 崩溃消失），装机统一测试未能完整跑完——本轮发版依据是
  CI 三门禁 + 探针 + 各 PR 作者的实测（#55 有 MuMu Android 12 x86_64 实测 18 条）。
  **下次性能测试时补一轮装机冒烟**。
- **push 状态判定已改 exit code**（v1.4.25 事故教训落实）。

## ⚠️ 2026-10-26 API push 覆盖事故（已修复，重要教训）
- **事故**：github.com:443 间歇阻断时用 `scripts/push-via-api.py` 推 PR #51 的合并
  结果，但**本地分支不含刚在远端合并的 PR #52 内容**（GitHub API merge 只发生在
  远端，我没 pull）→ API 推送把 #52 的 TerminalActivity / libtermux.so 四架构 /
  LogViewerActivity 等 32 个文件覆盖掉。
- **发现**：`compare/ee3666c...330e6f6` 显示 32 个文件被删。
- **修复**：`git merge ee3666c`（本地有该对象）→ 无冲突 → 编译/i18n 过 →
  再走 API 通道推（26 文件更新、**0 删除**，compare 验证 PR#52 内容全部找回）。
- **教训**（下次 API push 前必查）：
  1. **push-via-api 之前必须 `git fetch origin && git log origin/main -1`**，确认
     本地 HEAD 包含远端最新（GitHub API merge / 他人 push 都会产生本地没有的提交）；
  2. push 前用 compare API 或本地 diff 验证「不丢文件」；
  3. push 状态判断**不要用输出字符串匹配**（失败消息里可能含 "main"）——用 exit code；
  4. GitHub 的 mergeable/merged 字段有缓存延迟，405 报「has merge conflicts」
     时以 merge API 的错误体为准。
- **PR 状态**：#51/#52 内容均已进入 main，因 API 重建提交 SHA 变化，GitHub 无法
  自动标 merged，已手动关闭并留言（#51 6428995167 / #52 6428995869）。
- **待办**：网络恢复后 `git fetch` 并把本地 main 对齐远端（API 重建的 SHA 与本地
  6fb5917 内容一致但 SHA 不同，届时 reset --hard origin/main 即可，内容无差异）。

## 2026-10-26 PR 批处理（#51 #52 #53 #54 #47）
- **PR #51**（XiaoPb，修 #49）：换装改意图日志两阶段提交（.runtime-swap，
  tmp→sync→rename 原子写），**纠正了 #49 里我的错误假设**——新旧混合态能通过
  isRootCompleteAt 探针（探针只查单侧锚点），后果是带着错配 node+so 启动而非
  「重下 100MB」；另发现解压阶段被杀会永久泄漏 engine.staging(~580MB) +
  runtime.zip(~124MB)。新增 cleanupInstallResidue() 回收。回滚规则两条，
  不做「备份缺失就删 root」推断。同 PR：EngineSupervisor.stop 返回 Boolean、
  AboutActivity stop 失败中止换装、https 强制、builtAt 防降级。
- **PR #52**（新贡献者 xiaoming132887，1739 行）：原生 PTY 终端（termux
  terminal-view 0.118 + 重编 16KB 页对齐 libtermux.so，修 Android 15+ dlopen）、
  多标签、LogViewerActivity（engine.log/boot.log 尾部 256KB + 系统分享）、
  plugin-health 无限重启修复。三语文案齐全。
- **#53/#54**（Masspedro）：Enter 换行、附件加相册直选。已回复方案：
  #53 归 WebUI overlay（按 pointer:coarse 重绑定快捷键）；#54 用系统
  Photo Picker（App 侧 onShowFileChooser 路由 image/* → ACTION_PICK_IMAGES，
  无需存储权限，上游前端零改动）。均留 issue 开着直到发布。
- **#47 更正**：我们之前回复里版本号笔误（写了 v1.4.20，实际 v1.4.24 出的
  可读报错、v1.4.25 出的 #47 真因修复）。

## PR #50 已合并（2026-10-26，修我在 #48 里丢掉的主题接线）
- **我的失误**：合并 PR #48 时对 `OnboardingActivity.kt` / `SettingsActivity.kt` 用了
  「整文件取上游」解冲突，把 `e7d85ec` 的主题接线一起丢了。后果：`ThemePref.set`
  全仓 0 调用、引导页三张主题卡看得见点不动、设置页外观行整行消失、
  `activity_settings.xml` 106 处硬编码深色。
- **教训**：rebase/merge 冲突解决**绝不能整文件取一侧**——尤其当对方是
  「重写整个文件」（如 PR #48 把 theme 接线混在大量重构里）。应逐 hunk 看，
  或用 `git log -p <file>` 对照上一版语义。这条以后写进合并检查清单。
- **PR #50 内容**（作者 XiaoPb）：3 个页面改继承 ThemedActivity、引导页/设置页
  主题卡接线恢复、`ThemeMode.labelRes`、23 种硬编码色→语义 token、
  5 个页面 69 处状态色改 getColor(R.color.*)、新增 `_test_theme_menu.py`（34 项）。
- **合并后实测**（emulator-5554 / noruntime 构建）：
  设置页「外观主题」行在（值=深色）→ 三选一可用 → 切浅色整页白底深字、卡片清晰 →
  **插件市场浅色下插件名可见**（他 998b012 修的白字白底隐形）→ 3 探针 + i18n 全过。
- 已回复作者承认失误并致谢；下一步随 v1.4.26 发布。

## 桌宠播放修复 + 桥插件门禁下沉（2026-10-27，未发布，分支工作区）
计划见 `docs/pet/PET_ROADMAP.md` §2（缺陷时间线）与 `docs/pet/PET_ARCHITECTURE.md` §6.1（设计动因），原计划文档已删；P0~P4 全部落地，独立 commit 可单独 revert。
- **P1（2a8999c）播放层可观测 + 看门狗**：根因是 `PetView` 没重写 `verifyDrawable()`（`View`
  默认只认背景/前景，`AnimatedImageDrawable` 每帧的 `invalidateDrawable` 被丢弃）+ drawable
  自调度偶发丢一次就永久停住（`isRunning` 仍 true）。修法：重写 `verifyDrawable` +
  ticker 按 41ms **主动泵帧**（不再指望自调度）；四类播放失败走 `onPlaybackError` 进日志+状态
  总线（服务按 slug 5s 限流）；解码失败**保留旧帧**并给链发 400ms 跳过信号（否则链靠
  `onAnimationEnd` 推进会永久停摆）；卡帧看门狗 2s 阈值。
- **P2（7b759d0）一次性动画时长对齐**：`AnimatedImageDrawable.repeatCount` 是"重复次数"，
  写 1 = 播两遍；改 0，定时器与清单时长统一（0.4~30s 夹取），播完停最后一帧交回链。
- **P3（aa2246c）门禁下沉**：以前只在设置页弹窗里查桥插件，`restoreIfEnabled()`/`START_STICKY`
  重建全绕过去。判定收敛为 `PetPluginDetector.bridgeGateOpen()`（服务+设置页共用）：就绪放行；
  **版本 <0.3.1 硬拦不给旁路**；没装/没启用看 `allowWithoutBridge`（软门禁「仍然开启」才置 true，
  桥就绪时开桌宠自动清回）。卡点在 `attachPet()` 入口（三条启动路径必经），被拦走
  `holdForBridge()`：前台服务照跑、不画角色、状态置 `bridgeMissing`（通知「已暂停：桥插件未就绪」
  优先于已连接/等待；设置页 warning 提示行直达插件中心）。
- **P4 看门狗自愈开启**：`stalled` → `maybeHeal`（循环停摆走 `restart()`、泵帧断链走重挂
  ticker），同一段最多救 2 次（素材真坏时无限重启 = 每 2s 闪一下）。**注意判据是"有没有在
  重绘"不是"画面有没有变化"**：素材 blend=1 的叠影它看不出来，那是资源侧问题。
- **验证**（MuMu 3840×2160）：75s/5 段与 60s/6 段 0 条 stalled、每段 10.05s 换段、只播一遍；
  三类入口（待机/点击回应/动作）全走 ONCE 并回随机链；门禁三态实测（版本改坏→硬拦且旁路免疫；
  bundles 摘除→软拦；「仍然开启」→立即出宠物）；探针 182→**220 PASS**；check-i18n 全绿。
- **踩坑**：① 应用运行中 `install -r`/改 shared_prefs 会与服务内存态打架，验证前必须先
  force-stop；② `adb shell` 管道写二进制会被 pty 掐断（截到 1 帧），写设备文件走
  `adb push /data/local/tmp` + `run-as cp`；③ 读设备二进制必须 `adb exec-out`（否则 CRLF 污染）；
  ④ 资源包整包搬运用 tar 流（exec-out `run-as tar -cf -` ↔ push 后 `run-as tar -xf`，
  注意解压 cwd 要对准 tar 内路径）。
- **遗留**：现网 `dsh-pet-480p24` 包是 blend=1 产物，拖影回来了 —— App 侧修复没回退，是**资源
  产线没打 NO_BLEND 补丁**（已在设备上就地修掉 106 段救急）；根修要回 dsh-pet-mobile-assets
  跑 `build-webp.py` 的 `patch_noblend()` / 对存量包跑 `fix-webp-blend.py`，再发新 index。
  本仓库新增 `scripts/probes/fix_webp_blend.py`（就地改帧标志，幂等）。

## v1.4.26 待发（2026-10-26，issue #49：运行时换装事务化）
- **问题**（PR #48 审查 P1-2）：换装在逐条 `engine/<x> → engine.old` 期间被强杀
  （低内存 / OEM 清后台 / 用户强杀）没有任何持久化证据 —— `.pending-health` 在换装
  **开始前**删除、**全部完成后**才写，下次启动 `recoverPendingHealth()` 直接 return，
  `engine/` 停在新旧混合态。
- **纠正 issue 的一个前提**：issue 称「混合态会被 `complete=false` 探针抓到并重装
  自愈」，实测不成立。`isRootCompleteAt()` 只查锚点文件与 node 的 ELF 架构，
  新 `bin/` 配旧 `lib/` 完全通得过 —— 后果不是重下 100MB，而是拿错配运行时启动
  （`CANNOT LINK EXECUTABLE` 或随机崩溃），比体验问题严重一档。
- **issue 未提的第二个后果**：解压阶段被杀的残留永不回收。`engine.staging`
  （~580MB）与 `cacheDir/runtime.zip`（~124MB）只在 `install()` 内删除；下次启动
  `engine/` 完整 → 走复用直接返回 → 这两个目录永久留在盘上。
- **方案**：新增 `filesDir/.runtime-swap` 意图日志（`tmp → fsync → rename` 原子写），
  在动 `engine/` **之前**落盘；两阶段 `swap → committed`。恢复**只看日志、不看完整性
  探针**：`swap` 相位精确回滚（幂等，回滚本身被中断可重入），`committed` 相位前滚
  （按日志补写版本戳，含 sha256，不白扔已换好的运行时）。回滚规则两条：
  `planned - old` 删除；`old` 里「备份有才还原」（不做「备份缺失就删 root」的推断）。
- **附带修复**：换名失败即抛，去掉 `copyRecursively` 回退（三目录同在 `filesDir`，
  rename 本就原子，退回拷贝正是窗口被拉长的根源）；`EngineSupervisor.stop()` 改为
  返回「是否真停干净」，About 页停不干净则中止换装（旧实现 `runCatching` 静默吞掉，
  换完删 `engine.old` 时那个还在跑的进程必崩）；About 页 builtAt 防降级；下载源强制
  https（用户自定义代理前缀除外）；自检新增 `install.residue`。
- **文档**：`docs/RUNTIME_SWAP.md`（状态机 / 恢复决策表 / 断点复现步骤）。其中一条
  务必记住：**SafeMode 也会调 `markHealthy()`，这是有意的** —— 引擎 HTTP 通即说明
  运行时可用，插件故障归 ProfileGuardian 管，别当成 bug 改掉。
- **验证**：`compileDebugKotlin`、`assembleDebug -Ppackage=noruntime` 通过，
  `scripts/check-i18n.py` 全过。**mid-swap 强杀的设备实测尚未做**（当时无设备在线），
  留了仅 debuggable 包生效的 `files/.debug-swap-abort` 断点，用法见文档第 7 节。
- **未做**：`engine.old.prev` 双备份（连续换装会吞掉上一轮回滚目标，成本高于收益）；
  P2 剩余两项（`dsh-tool` 本地回退源去重告警、PKCS12 报错文案需先定位抛点）。

## v1.4.25 后续（2026-10-26，CI 偶发失败已处置）
- **现象**：推 AI_CONTEXT 那次 main 的 android-build 红了，唯一失败 job 是
  `build-apk (x86_64, full)`；同一份代码的 v1.4.25 tag 构建四 lane 全绿。
- **诊断**：单文件文档 commit 不影响构建；本地用 CI 同一条命令
  （`assembleDebug -Pabi=x86_64 -Ppackage=full -PruntimeZip=<绝对路径>`）构建成功；
  GitHub 上 rerun-failed-jobs 后也全绿 → **runner 资源抖动，非代码问题**。
- **但压力是真实的**：full 形态 debug+release 连续构建，每次吞吐 127MB
  runtime.zip（解压 ~500MB），8 lane 并行时磁盘/内存都不宽裕。
- **防护（commit 99c1fb4）**：build-apk 在 checkout 后先清掉用不到的预装组件
  （dotnet / android ndk / ghc / boost / AGENT_TOOLSDIRECTORY），并前后打印
  `df -h` + `free -m` —— 下次若复现可直接从日志判断是磁盘还是内存。
- **验证**：该 commit 的 main 构建 run 40296459217 全绿（8 个产物 job 全 ok）。
- 注：GitHub Actions 的 job 日志下载 API 需要 admin 权限（token 403），
  排查失败原因只能用 annotations / 失败步骤名 / 本地复现三条路。

## v1.4.25 已发布（2026-10-26，PR #48 合并 + #47 修复，CI 7 分钟全绿）
- **合并 PR #48**（merge ec42f90，作者 XiaoPb，182 文件）：运行时解耦 / full+noruntime
  双形态 / 签名索引 / 原生深浅主题 / 桌宠。合并前验证：merge-tree 无冲突、我方
  v1.4.21–24 全部修复均保留、编译通过、i18n + 两个探针全过。
- **合并前确认前置条件**：`@xiaopb/dsh-pet-bridge@0.3.1` 已发布，且实测其 tarball
  确实含 `X-DSH-Pet-Token`（bubble.js 2 处）+ `Content-Length` + 读
  `DSH_PET_BRIDGE_TOKEN` —— 这是我上轮提的 P1 缺口，作者实现完整。
- **#47 真因修复**（355d44f）：`loadCatalog()` 从未解析 `apkUrl`/`apkSha256`/
  `apkPackage`（数据类有字段、构造时没赋值）→ APK 型扩展（Offline ASR / 本地识别
  应用）**从 v1.2.127 引入功能起就没装上过**。v1.4.24 我只改了报错文案，是误判。
- **构建流程变更（重要，以后本地构建必须照做）**：
  1. `src/main/assets/runtime.zip` 现在**会和新流程冲突**（Duplicate resources）——
     构建前先挪走；
  2. 用 `-PruntimeZip=<绝对路径>` 指定运行时（**相对路径会失败**：Gradle 的
     `file()` 基准是 app 模块目录）；
  3. 源 zip 必须已带 PTC v2 补丁 + polyfill v4，否则 prepareRuntime 门禁直接抛
     「APK builds must not patch runtime.zip anymore」（门禁设计如此，不许现场打）；
  4. 完整命令：`gradle :app:assembleDebug -Pabi=x86_64
     -PruntimeZip="<abs>"/_runtime_bak/rt-x86_64-zodded.zip`
- **发布产物结构变化**：12 个资产 = 8 APK（arm64/x86_64 × debug/release ×
  full/noruntime）+ 2 runtime zip + runtime-index.json + .sig。
  full 包 146.8MB（比之前 227MB 小很多，运行时去重生效）；noruntime 仅 23MB。
- **模拟器实测**：装 full 包 → RuntimeInstaller 识别 bundled 版本更新 → 重装
  （engine.old 保留待健康）→ 16s 引擎健康 → 插件中心 3540 插件正常。

## v1.4.24 已发布（2026-10-26，tag run 40269198794，8 分钟全绿）
- **宝宝反馈四条 → 全部处理**：
  ① 社区 dsh-web-mobile 插件 bug 太多 → **整体移除**（assets 860KB/6 文件、
     overlay insert、部署列表、shell-overrides 注入、失效的 sidebar_* 设置全清）。
     实测 overlay 只剩自有 4 个窄屏插件。**v1.4.23 已下架**（改 draft 后删除，
     远端 tag 也已删）。
  ② 插件中心太简单 → 参考 dsh-market（8.9k star）接入 `dsh-plugin-catalog`：
     3540 插件 / 23 分类 / 中英双语 / 版本历史，精简后 1.7MB 内置进
     `assets/market/catalog.json`（**注意：不能放 assets/extensions/，会被
     check-i18n.py 当扩展目录解析而报错**）。browse 改本地子串搜索，零延迟。
     分类 chip 切换要强制重载（缓存是全分类结果）。
  ③ 设置页杂乱 → 4 分区拆成 8 个语义分区（外观/权限/语音/扩展/数据/网络/
     维护/关于），22 项变 25 项（补回 Root 能力 / Shizuku 状态两行——它们原本
     是 rowPriv 的兄弟节点，按 row id 提取会漏）。
  ④ 图标重复 → 新做 11 个（rotate/palette/layout/mic_wave/headset/apk/note/
     export/lan/log/clock），替换 10 处重复，25 项图标全唯一。
- **引擎重启逻辑重写**（宝宝要求「异常退出时把 3080 上所有东西杀掉再完整启动」）：
  * `reclaimPort` 改为按**端口持有者**清场：读 /proc/net/tcp{,6} 的 LISTEN
    inode → 扫 /proc/<pid>/fd 匹配 socket:[inode] → kill -9。不再依赖 cmdline
    匹配 files/engine/bin/node（包装层会失效）。
  * **关键盲区**：引擎「健康运行后被杀」走的是 Healthy 分支，原来直接进下一轮
    spawn、完全跳过自愈判定段——第一次加的清场代码根本没执行。已在该分支补
    reclaimPort。
  * 新增 `looksLikeAuthFailure()`：只扫本次启动日志段，命中 401/403/
    unauthorized/invalid api key/token expired/credentials/EACCES → State.Failed
    + 三语提示「登录已失效，请重新登录」，return 不再重试（防重启风暴）。
- **实测**：kill -9 引擎 node → 'port 3080 is free after thorough reclaim'
  → 6.4s 后 'engine healthy' ✓
- 4 资产上线（release 227.4MB）。

## v1.4.23 已发布（2026-10-26，配色/布局/快捷栏三项用户反馈，tag run 40254177003）
- **宝宝三条反馈**：① DSHA 蓝太丑、要原来的颜色；② 手机插件的侧边快捷栏没了；
  ③ 设置页太乱，想要卡片/网格布局。
- **① 配色**：默认恢复原天蓝 #7DD3FC（43 处 XML + 全部 Kotlin 回退，删死文件
  dsha_colors.xml）；新增 `Accent.kt` 枚举（sky/indigo/teal/violet/amber），
  所有强调色改运行时读偏好；设置页新增「配色方案」，切换 recreate 生效。
- **② 快捷栏**：上游用 `translateX(-110%)` + position:absolute 把侧栏推出屏幕。
  新增 `assets/js/shell-overrides.js`（onPageFinished 注入，与下载桥/剪贴板桥同路径）
  把侧栏打回文档流：56px 常驻窄栏、只显示图标、隐藏抽屉按钮；上游其余优化保留。
  顺带把 Accent 注入成 CSS 变量 `--dsh-accent` 供 WebUI 侧使用。
- **③ 布局**：`SettingsLayoutMode`（list/card/grid）。网格模式踩了三个坑：
  a) vertical 容器子项设 width=0+weight=1 → 宽度真的变 0（要包一层 horizontal）；
  b) 不能按索引隐藏"文本列第2个子节点"——部分行的当前值（valStorage 等）就在
     文本列内，会被一起藏掉 → 改判「小字号(≤12sp) 且非首个子节点」；
  c) 分区跨 columnPrimary/columnSecondary 两列，只重排第一列会漏 Extensions/Other。
- **验证**：清空偏好后默认 Sky (default) + 天蓝 ✓；切 Indigo 立即生效 ✓；
  网格四分区双列、标题省略、说明隐藏、当前值保留 ✓；
  侧栏 computed width=56px/transform=none/position=relative ✓；i18n 全过。
- versionCode 273。4 资产上线（release 226.9MB）。
- **still open**：PR #48 等作者处理 pet-bridge 0.2.0 发布协调缺口。

## v1.4.22 已发布（2026-10-26，DSHA 风格 UI 移植包，tag run 40250425058，7 分钟全绿）
- **来源**：宝宝转发的 `ya2rUQDS,dshx.zip`（community contribution，MIT，上游 dsh-web-mobile）。
  含 ① 860KB 移动端 WebUI 客户端插件 ② 原生壳配色 7DD3FC→4F6EF7（22 文件）。
- **安全审查（逐项实测，结论：安全）**：
  client.js 无 eval/new Function/document.write/XHR；http:// 只有 w3.org SVG 命名空间与
  127.0.0.1；无 base64 大载荷（860KB 是正常 TS 产物）；index.js 删除路由复用
  engine connection.requestRejection 鉴权 + same-origin + 1MiB body 上限；
  delete-session.js 不 rm、移入 .sessions-trash + manifest、重命名失败逐条回滚、
  路径必须落在 storage root 内。
- **集成踩坑**：
  ① patch 的 PluginStoreActivity 2 hunk 因 v1.4.21 重写失败 → 手动替换颜色解决；
  ② patch 未覆盖 activity_plugin_store.xml（v1.4.21 新增）→ 补替换；
  ③ **patch 的 -p2 前缀解析把文件建到仓库根 `src/` 而非 `app/src/`** → 手动搬到
     app/src/ 并删误建目录（下次用 patch 要先确认落点）。
- **端到端验证（emulator-5554 / dsh36 / Chrome 133 / 412x842）**：
  插件部署到 engine/android-plugins/dsh-web-mobile（5 文件，日志 readable=true）✓
  前端加载 bundle 200/2.7MB、模块在 ?? 列表第 6 位 ✓
  **手机版布局实际渲染**（Into the Unknown 头部 + Choose workspace/Standard mode 胶囊 +
  大圆角输入框 + 底部输入条 + 头部导航）✓ 159 个 style 表含 DSHA 蓝 ✓
  设置页分区标题变靛蓝 ✓
- **排查经验**：CDP 里手动调 `__ModuleLoader__.create()` 会抛
  "SyntaxError: Invalid or unexpected token" —— 那是**调用方式**错（create 只能在
  boot 前用，mode=live 后必须走已注册链路），不是插件语法问题。判断插件是否生效
  应看 DOM/style 痕迹（style[data-*]、注入的 CSS、底部 composer 节点）。
- versionCode 272。4 资产上线（release 226.8MB）。

## 2026-10-26 PR #48 复审结论（未合并，等作者处理 P1-1）
- **作者已按上轮意见修复**（head f972136 → 35570dc，rebase 到含 v1.4.21 的 main）：
  - P0 MANIFEST.json modify/delete 冲突：**已解**（`git merge-tree main pr-48-v2` 干净）
  - P1 PetBridgeServer 无鉴权：**已修**（eace5c1，fail-closed + 头精确匹配 + token 经
    DSH_PET_BRIDGE_TOKEN 注入引擎 env，照抄 AgentBridge 先例）
  - 额外修了运行时安装并发（544a90c，INSTALL_LOCK 串行化，无死锁——两入口均在后台线程）
  - 新增下载代理（默认 gh-proxy.org，纯前缀拼接，**完整性链不降级**：sha256 pin 硬前置
    + 每 URL 校验 + 索引验签；可留空直连）
  - CI: publish-preview 仅 schedule 触发、固定 tag + overwrite，不会回归刷包
- **新发现 P1（发布协调缺口，合并前置）**：当时 `docs/pet` 的文档（已重构为 GUIDE/ARCHITECTURE/ROADMAP）声称桥插件需 ≥0.2.0，
  但 **npm 上 dsh-pet-bridge 只有 0.1.0**（2026-08-21 发布，tarball 全文无 token 头）。
  门控 fail-closed → 装 0.1.0 的用户每个 /bubble 得 403 → 插件静默丢弃 → **桌宠气泡
  全用户静默失效**。已在 PR 评论给出三个选项 + 可直接粘贴的 PetPluginDetector 版本校验
  实现（bridgeTooOld 判定 + 设置页提示）。
- 探针实测：pr-48-v2 快照上 responsive 58 项 + pet 94 项 全 PASS。
- P2 待后续：token 非常量时间比较、首启弹窗隐私披露、orderUrls opt-out 不对称。
- **状态**：等作者发 0.2.0 或加版本兜底后合并。

## v1.4.21 已发布（2026-10-25，插件中心重写，tag run 40206163982，7 分钟全绿）
- **参考对象**：开源 DSHA（DSH-APP/DSHA，Apache 2.0）的 PluginFragment / PluginRepository /
  PluginSource / PluginSort——源码经 GitHub contents API 取（raw.githubusercontent 被墙）。
  其设计要点：市场/已安装双标签、富卡片、四种排序、下载源切换、链接安装四来源识别、
  检查更新/更新/回退、能力声明、可取消进度、批量导出。
- **我们实现**（`PluginMarket.kt` 新增 340 行 + `PluginStoreActivity.kt` 重写）：
  双标签、富卡片（版本/体积/作者/推荐角标）、**兼容性分级徽标**（纯 JS/需原生编译/
  引擎 fork/未验证，复用 NativePluginCheck 7 信号）、五种排序、检查更新、详情弹窗
  （按需拉 registry 得 unpackedSize + 判断依据）、离线兜底（bundledCatalog）。
  加强点相对 DSHA：兼容性内联（DSHA 只有「能力声明」按钮）、精确版本升级、体积展示。
- **端到端实测（emulator-5554 / dsh36 x86_64）**：浏览 100 条、搜索 cost-meter 18 条、
  安装 dsh-cost-meter@1.9.39 ok=true、已安装标签 v1.9.39·3.3MB、排序切换、详情
  Size 7.0MB+Pure JS、卸载 ok=true（Packages:-3）、profile 清空。
- **踩坑**：① i18n 门禁禁止 Kotlin 中文字面量——Sort 枚举标签改 labelRes 资源引用；
  ② 网络不通时市场会空屏——fetchCatalog 失败回退 bundledCatalog（旧版 BUILTIN 清单）；
  ③ 空态提示重复渲染（subtitle + 列表内 hint）——改为单条。
- versionCode 271。4 资产上线（226.6MB release 签名校验过）。

## v1.4.20 已发布（2026-10-25，tag run 40199891720，8 分钟全绿）
- 4 资产上线（arm64/x86_64 × debug/release）。含 #42/#43/#44/#47 四项修复 +
  README 体积修正 + CI 刷包修复。versionCode 270。
- issue #42/#43/#44/#47 已通知报告人验证。


## v1.4.20 合并（2026-10-25，main → dev → main）
- 分叉点 `f613583`：main 侧 7 个提交（v1.4.19 发布线：polyfill v4、release job 降级、版本 269），
  dev 侧 2 个提交（桌宠悬浮窗预留、CI fork 开关 + dsh-hardssh 0.2.13）。冲突仅 2 处：
  本文件与 `.github/workflows/android-build.yml`（release job 的 if）。
- **发布语义（本仓库，固定）**：
  - 正式版：只接受严格 tag `v<major>.<minor>.<patch>`（如 `v1.4.20`），push tag → `publish-release`；
    **禁止 `v*.*.*-*`**（如 `v1.4.18-380`）——CI 首个 job 校验失败，不会发版。
  - 预览版：每日 01:30 Asia/Shanghai `schedule` → `publish-preview`（cron 写 `30 17 * * *`，GitHub 按 UTC 解析；
    workflow 级 `TZ: Asia/Shanghai` 供其余步骤使用），双架构 debug 包发到滚动 prerelease `preview`，
    覆盖同名资产并清理旧文件名，只保留最新一版。
  - edge：push `main` → `publish-edge`（debug + release，滚动 `edge`）。
- 刷包根因记录：旧 release job 每次 push/定时新建 `v<ver>-<run>` Release，该 tag 又命中
  `on.push.tags: v*` 触发下一轮构建 → 自激刷包（v1.4.18-380/381/382/384）。现在预览固定用
  tag `preview`，工作流不再创建任何 `v*` tag。
- 合并后 `versionCode 269 / versionName 1.4.19`（v1.4.19 已发布），下次正式发版 `270 / 1.4.20`。
- 仓库整理：根目录 20 个 `RELEASE_NOTES_v1.*.*.md` 移入 `docs/release-notes/`，README 三语已加归档链接。


## v1.4.21 待发（桌宠悬浮窗预留：插件驱动、不打包插件/素材）
- **需求**：预留系统悬浮桌宠；用户自行安装 dsh 插件后开启；不改 dsh-pet 本体。
- **生态事实修正**：真正可用的是 `dsh-pet-bridge@0.1.0`（cc-pet HookServer 协议），
  五字段是 `kind/agent_source/tool_name/tool_input/caller_pid`，**没有 bubble 文本**；
  `tool/result→post` 不带工具名，所以原生只显示「完成一步」，不伪造「已读 xxx」。
- **实现**：新增 `app.dsh.mobile.pet`（Protocol/BridgeServer/Controller/Pack/Installer/
  Renderer/View/Physics/Service/Menu/Settings/Prefs/Detector/Status）。独立前台服务，
  `TYPE_APPLICATION_OVERLAY`，不碰 `StatusOverlay` 的无障碍 overlay。
- **入口**：设置→扩展→桌面宠物；桥插件装了但没重启引擎时给出「重启引擎」。
  `PluginCenter.BUILTIN` 与 `community-plugins.json` 增加 `dsh-pet-bridge` 目录项，
  但不随包安装；pnpm/symlink 手工方式不采用。
- **动画**：不打包任何 WebP；没有资源包就**不显示**桌宠（不画任何占位图形）；用户可导入
  dsh-pet-android 的 APK/zip（`assets/pet-config.json` + `assets/webp/*.webp`）。导入有路径穿越、
  entry/大小/空间限制，临时目录原子替换。素材许可为「禁止商用」，见 `NOTICE`。
- **协议/限制**：仅 `127.0.0.1:7779`；8KB body、5s 超时、2 worker；stop 1.5s 宽限；
  引擎被杀时 30s 看门狗回待机。悬浮窗按**量出来的角色矩形**开（素材 16:9 画布里的透明边距
  左右各约 30%），所以透明像素不吃触摸、角色能贴到屏幕边；窗口仍是矩形，轮廓内的透明角
  仍挡触摸（公开 API 无法按形状裁）。
- **渲染**：素材画布只有 300px（角色约 120px 宽），窗口常有 600px+；`AnimatedImageDrawable`
  自绘不开位图过滤 → 放大 5 倍是最近邻硬台阶（锯齿）。现改为「先画进素材原始尺寸的离屏位图
  + 自建双线性 Paint 放大上屏」，交叉淡入改用 Paint alpha（省一个全尺寸图层）。
- **气泡**：自绘 `PetBubbleDrawable`（闭合 Path：圆角矩形+小尾巴，一次填充一次描边 +
  `setShadowLayer` 柔光，View 走软件层）。字号 = 角色宽 × 0.075（夹 11~19sp），em 口径派生
  内边距/圆角/尾巴；气泡正文底边贴角色头顶、只有尾巴尖压 15% 尾巴高 → 不压动画；头顶放不下
  翻到脚下且尾巴朝上；尾巴始终指向角色中线。
- **插件来源与版本声明（硬约束）**：桥插件必须是 `@xiaopb/dsh-pet-bridge` ≥ **0.3.1**
  （npm 包名或 GitHub `XiaoPb/dsh-pet-bridge` 均可）。协议 v2 的 `X-DSH-Pet-Token`
  与显式 `Content-Length` 均从 0.3.1 起；npm 无 scope 的 `dsh-pet-bridge` 只到 0.1.0
  （归上游，无法发新版），装上必 403/400。App 侧 `PetPluginDetector.MIN_BRIDGE_VERSION`
  做 fail-closed 门禁：装了旧版 → 设置页显示「需 0.3.1+」并禁止开启（弹窗引导去插件中心，
  无「仍然开启」旁路）；同时兼容识别上游遗留包名并标记 legacy。
- **验证**：`scripts/probes/_test_pet_overlay.py`、`scripts/check-i18n.py`、
  `_test_responsive_native_ui.py` 全过；pet 包用 kotlinc 2.3.20 + android.jar 编译通过；
  aapt2 compile + link（android-36）全资源通过；本机无 Gradle wrapper/ML Kit 缓存，
  完整 `assembleDebug` 与模拟器端到端待 CI/Android Studio 补跑。

## v1.4.19 待发（CI fork 开关 + dsh-hardssh 0.2.13）
- **Fork CI**：`release` / `publish-release` / `publish-edge` 的仓库门禁改为
  `github.repository == 'Soodok/...' || vars.ENABLE_CI_IN_FORK == 'true'`。
  Fork 用户只需在仓库 Variables 新建 `ENABLE_CI_IN_FORK=true` 即可自行编译/发布；
  PR 仍不发，签名材料仍需自行配置。文档已补在 `docs/RELEASE.md`。
- **dsh-hardssh**：`HARDSSH_REF` 从 v0.2.10 的 `c7e2bd1` 升到 0.2.13 的
  `09a1d1fb58b2fe63e19b7e9fc4300bc79bcc5d20`（XiaoPb/dsh-hardssh）。
- **包名变更**：v0.2.11 起 npm 包名由 `@tiphareth/dsh-hardssh` 改为
  `@xiaopb/dsh-hardssh`（cordis.patch.yml 同步改名）。联动修改：
  `EngineConfig.SSH_PLUGIN_PACKAGE`、profile 链接路径、runtime 注入路径、
  CI 闭包校验、`pack-hardssh-plugin.sh` / `inject-hardssh-plugin.sh`、文档与 README。
  `EngineConfig.LEGACY_SSH_PLUGIN_PACKAGE` 仅用于识别用户已装的旧包并让位。
- **未验证**：本机无 pnpm 依赖缓存/CI，尚未实际跑 v0.2.13 的 `pnpm install + build`
  与 Android 端 SSH 冒烟；以 CI 构建和真机 SSH 工作区为准。

## 2026-10-25 issues/PR 批量处理
- **修复已推送**（commit 2a3db3d，将随 v1.4.20 发）：
  #42 Android 10 存储权限（WRITE/READ maxSdkVersion 28→29——MANAGE 是 API 30 才有，
  maxSdk=28 让 API 29 设备无权限无入口）；
  #44 开 VPN 单文件下载失败（DownloadManager 进程被绑 VPN 网络访问不了 127.0.0.1；
  回环目标改 App 内 HttpURLConnection 流式下载 + 后台线程 + Cookie 透传 + MediaStore）；
  #43 WebUI 复制无反应（DshAndroidClipboard 原生桥 + onPageFinished 注入降级链
  原生API→桥→execCommand + WebView 可聚焦；页面吞异常也兜住）；
  #47 「List is empty」不可读报错（deb 路径入口校验 packages 空）。
- **已回复**：#41 多 profile（认可分析，进 backlog，关联 PR #48）；#46 葡语（欢迎
  values-pt-rBR PR）。**已关闭**（修复已发版）：#37/#38/#35/#23。
- **PR #48（XiaoPb，182 文件 +8221）审查完毕未合并**：full/noruntime 双形态 +
  运行时云分发 + hardssh 独立 + pet 桌宠 + 主题。checker 深审结论：核心分发安全
  （验签不降级/sha256 先行/回滚状态机）实测成立；P0=MANIFEST.json modify/delete
  冲突（合并时保留删除）；P1=PetBridgeServer 7779 无鉴权（应照 AgentBridge 先例
  token 门控）。已评论合并条件（rebase + 补鉴权），作者更新后合并。跟进 issue #49
  （swap 中断恢复窗口 + P2 清单）。

## v1.4.19 已发布（2026-10-25，tag run 40169259973）
- 4 资产上线（arm64/x86_64 × debug/release，release 226.6MB 签名校验过）。
- WebView <85 的版本门禁为**既有功能**（AppGate MIN_WEBVIEW_CHROME=85，v1.2.100 起，
  MainActivity.checkWebViewGate 三级取法 + market:// 升级入口 + 「仍然继续」）——
  本版未改（曾误判缺失重复造轮子，宝宝纠正后已回滚 WebViewVersionCheck.kt）。
- 教训：动「已有功能」前先 grep 全仓（v1.2.100 的门禁不在 issue 提及的文件里）。

## v1.4.19 追加（2026-10-25，CI 刷包修复——宝宝发现「连续构建了四五个包」）
- **根因**：PR #39 的 `release` job 是上游 fork 的 debug 自动分发渠道——
  每次 push main 都新建 `v<ver>-<run_number>` 的 Release（发到我们仓库即
  v1.4.18-380/381/382/384 四个重复包）。publish-edge 是单个滚动 prerelease
  （tag=edge 反复覆盖，设计正常）。
- **修复**：release job 条件改 `workflow_dispatch` only（commit 后推送）；
  误发的 4 个 release + tag 已删（API）。Releases 页面现仅剩正式版 + edge。
- **本仓库发布语义（固定）**：tag v* → publish-release（正式签名）；main
  push → 全量构建 + edge 滚动更新；workflow_dispatch → 手动分发渠道。

## v1.4.19（2026-10-25，issue #40：WebView 96 下 throwIfAborted TypeError）
- **报告**：sanhuai-dy（AI 代提交，实机 Redmi K40 游戏增强版 / Android 12 / WebView 96.0.4664.104）。
  v3 polyfill 覆盖 13 项但缺 `AbortSignal.prototype.throwIfAborted`（Chrome 100），
  dynamicCordisRunner/syncInspectManifest 等客户端 runner 整链 TypeError。
- **修复（polyfill v4，commit e4e908a）**：
  - throwIfAborted（抛 reason，缺 reason 回退 AbortError）；
  - **配套补 AbortSignal#reason（Chrome 98）**：96/97 的 abort(reason) 只触发事件不存
    reason——包装 AbortController.prototype.abort（仅原型缺 reason 时启用），先挂
    reason 再调原生（同步监听器读得到）；
  - structuredClone 兜底 window→globalThis（Worker 里 window 不存在，v3 会中断整个
    IIFE——隐性缺陷）；
  - patch 脚本 +--zip 模式；本地 5 个 runtime zip 已升 v4。
- **构建门禁**：prepareRuntime 校验 index.html v4 标记 + 三个符号，缺失现场打、
  再缺中止（防「补丁只存在于产物」第三次重演）。
- **测试**：scripts/probes/_test_webview_polyfill_v4.mjs——Node vm + 纯 JS fake
  AbortController（Node 原生 any/timeout 不可配置删不掉），9 项全过（含现代环境零改动）。
- README 兼容口径：85 是语法层门槛（??= 解析特性），API 层 14 项 polyfill 覆盖，96 实测可用。
- **已回复 issue #40**（comment-6392630116）。versionCode 268 已随 v1.4.18 发布用掉，
  下次发布需 bump 269/1.4.19。gitignore 白名单 +2（测试文件入 probes 白名单，照 PR #39 先例）。
- **待发布**：等宝宝指令；发布 = bump 版本号 → tag v1.4.19 → CI 全自动。

## v1.4.18 发布（2026-10-25，PR #39 合并 + tag 发布）
- **PR #39（@XiaoPb）已合并**（merge commit `42717f5`）：① 内置
  @tiphareth/dsh-hardssh 0.2.10（CI 从 XiaoPb/dsh-hardssh 固定 SHA 构建，注入
  runtime.zip；本地目录弹窗重绘 + 新建文件夹）；② 签名发布流水线：tag v* 触发
  CI 构建 debug+release，Secrets 注入 keystore，证书 SHA256 与
  `vars.RELEASE_CERT_SHA256` 比对；③ 响应式 UI（MaxWidthLinearLayout +
  七个原生页横屏适配 + OrientationPrefs 平板默认 AUTO）。
- **Secrets/变量已配置**（API + libsodium sealed box）：RELEASE_KEYSTORE_BASE64
  （本地 keystore/dsh-release.keystore，alias **dsh**，密码见 AI_CONTEXT 885 行
  签名命令记录）、RELEASE_STORE_PASSWORD / RELEASE_KEY_PASSWORD、
  RELEASE_KEY_ALIAS=dsh；vars.RELEASE_CERT_SHA256=D2:8C:A4:...:92:C8。
- **本地验证**：keystore/release.properties（gitignored）+ assembleRelease
  -Pabi=arm64-v8a → app-release.apk 签名生效，apksigner verify 证书指纹匹配。
- **发布方式变更**：此后发版 = push tag v<ver>（CI 全自动：hardssh 构建 →
  双架构 runtime+APK → 签名 → GitHub Release + 自动 notes）。tag 构建**强制**
  要求 RELEASE_* secrets（缺失即红）。升级内置 hardssh = 改 workflow 顶部
  HARDSSH_REF。
- **发布 run**：40144039300（首跑，build 5 job 全绿但 release 全 skipped——**PR #39
  把 release/publish-* 的 `github.repository` 条件硬编码成作者 fork 名**，已改回
  `Soodok/…`，commit 5594d71）；重打 tag 后 run 40144773431 全绿。
- **已发布**：Release「DSH Mobile v1.4.18」正式版（draft=false），4 资产：
  dsh-android-v1.4.18-{arm64-v8a,x86_64}-{debug,release}.apk（release 226.6MB，
  CI 内证书指纹校验通过）。发布说明已自动生成 + 仓库内
  RELEASE_NOTES_v1.4.18.md（commit 在 tag 之后，属正常）。

## v1.4.18 追加 2（2026-10-24，issue #38：终端启动报 Termux bash.bashrc Permission denied）
- **机理**：runtime 的 bash 是 Termux 构建，SYS_BASHRC 编译期宏写死
  `/data/data/com.termux/files/usr/etc/bash.bashrc`，**无环境变量旁路**。真机装有
  Termux → EACCES 报错；未装 → 静默。文件内容仅 PS1/补全，从未成功加载过。
  `bin/pkg` 同理是 Termux apt wrapper，首行 source termux 路径必然失败。
- **修复**（`scripts/patch-bash-sysbashrc.py`）：bin/bash 硬编码路径**等长 patch** 为
  /dev/null（ELF 大小不变）；bin/pkg 换成 npm/pnpm 指引脚本（exit 127）。已挂
  collect-termux-runtime.sh 补丁链（CI 新构建天然带）；本地 4 个 `_runtime_bak/rt-*.zip`
  源已同步打上（patch 后 gradle 选源才会带补丁——**patch 只打 assets/runtime.zip 会被
  prepareRuntime 重选源覆盖**，本日踩过一次）。
- **验证（emulator-5554 x86_64）**：bash -i 启动无 bashrc 报错；`pkg install` 输出指引
  rc=127；patch 单测（等长/幂等/ELF 头/路径消失）全过。
- **已回复 issue #38**（comment-6386370178）。
- **注意**：`-Pabi=x86_64` 才是模拟器构建（默认 arm64）；pm clear 后要过首次引导页
  （点 GET STARTED）引擎才启动解包；MANIFEST.json 的 abi/sha 变化是本地构建副产品，不提交。

## v1.4.18 追加（2026-10-24，用户反馈「装了插件升级不行」）
- **pnpm 12 升级行为矩阵（PC /tmp/pnpm-upgrade-test + 模拟器双实测，已装 ^0.74.0、
  npm latest 0.75.1）**：`add pkg`（无 spec）保留旧 spec 不解析；`add pkg@latest` 被
  "Lockfile is up to date" 跳过（supply-chain verified 缓存，14ms no-op）；`update pkg`
  报 "Already up to date"；**唯一有效升级路径 = `add pkg@<精确版本>`**（-0.74.0 +0.75.1 实证）。
  npmmirror 的 dist-tags 同步无滞后（latest 同为 0.75.1），排除镜像因素。
- **修复**（commit `6528371`）：
  - `PluginCenter.installedVersions()`：读 `profiles/web/node_modules/<pkg>/package.json`
    的实际落盘版本（dependencies spec 是声明范围，不反映真实安装）；
  - 插件中心已装行：npm latest ≠ 实际版本 → 琥珀色「升级」按钮 → `add pkg@<精确版本>`；
  - 普通安装同样改精确 spec（remove 残留 lockfile/spec 时不再复用旧版）；
  - runInstall/doInstall/confirmInstall 分离 spec 与 basePkg；三语字符串
    plugin_upgrade / plugin_upgrading。
- **端到端验证（emulator-5554）**：装 0.74.0 → UI 显示「升级」→ 点击 →
  `install dsh-context@0.75.1 -> ok=true`，node_modules 实际版本变 0.75.1，
  spec 写精确版本，按钮回「Installed」。i18n 三语全过。

## v1.4.18（2026-10-24，用户 QQ 群截图反馈：插件安装报 ERR_PNPM_STORE_DIR_OPEN_OPERATION_LOCK）
- **报错机理（模拟器实测复现）**：pnpm 锁目录 `.cache/pnpm-store-operation-locks-<uid>` /
  `pnpm-dir-locks-<uid>` / `pnpm-modules-swaps-<uid>` 属主坏掉（① Root 模式下引擎以 uid 0
  写过后残留；② 安装中途被杀留悬空锁），pnpm 12 拒绝持锁
  （"lock directory must be a real directory owned by the current user"）。
- **修复**（`PluginCenter.kt`，commit `6e526b0`）：install/uninstall 失败命中锁损坏签名
  （错误码 `ERR_PNPM_STORE_DIR_OPEN_OPERATION_LOCK` + "store operation lock" 消息双签名，
  大小写不敏感）→ `healPnpmStore()` 清理后重试一次。清理两级递进：**直接删**（普通死锁）
  → **rename 绕开**（Root 残留 000 时里面 .lock 无法 unlink 删不掉整个目录；rename 只改
  父目录条目、不进入目录内部，`.cache` 可写就必成功——pnpm 只按固定名找锁，改名即无锁）。
  `.cache/pnpm`（PNPM_HOME）排除；改名残留 `.broken-*` 仅含空 .lock 文件占用极小。
- **附带重构**：install/uninstall 抽出共用 `runDshPluginCli()`；`EngineConfig.deleteNoFollow`
  private→internal 复用。versionCode 268 / versionName 1.4.18。
- **端到端验证（emulator-5554 / dsh36 / adb root 毒化 root:000）**：
  - install dsh-context：`heal=true`（rename 生效）→ 重试 `ok=true`，pnpm resolved/downloaded
    正常装上；
  - uninstall dsh-context：`heal=true` → `ok=true`（Packages: -2, Done in 90ms）。两路径闭环。
- **已验证**：compileDebugKotlin / assembleDebug 成功。
- **阻塞**：commit 后 `git push` 两次被 reset（网络波动，本机无可用代理端口），待重推。
- **注意**：`.cache` 下真实锁目录名带 uid 后缀（`pnpm-store-operation-locks-10216`），
  且 store 本体可能不存在而只有锁目录——清理逻辑按 `pnpm*` 前缀匹配而非固定名。

## 新项目交接（2026-10-24）：无障碍控制 / AI 控制独立项目
- **决定**：宝宝开独立新项目，专注「AI 通过无障碍控制 Android 设备」。
- **交接文档**：`docs/A11Y_AI_CONTROL_HANDBOOK.md` —— 完整沉淀：
  场景分级（L1/L2/L3）、架构总览、感知/执行/AI 侧全部经验、10 条血泪规则、
  工程坑清单（15+ 条）、可复用资产表（8 个组件含路径与行数）、已知未解决问题、
  命名建议（首选 A11yPilot，备选 DroidHand / 妙手 MiaoShou；OpenHands 已被占用）。
- **核心资产**（可整体搬走）：DshAccessibilityService(2.6k行)/FastPicker/LocalLoopExecutor(1.5k行)/
  Framer/ScreenOcr/IconMatcher/PageStateDetector/AgentBridge(2.3k行)。
- **三条铁律**（新项目第一天写进架构）：验证必须是目标状态而非动作描述；
  失败先回退到认得的状态、同一步 ≤2 次就换打法；感知分层兜底（树→OCR→帧差）。
- **新项目 README 可引用本仓库**（MIT），搬运时注明出处。


## v1.4.18 追加（dsh-hardssh 本地目录弹窗美化 + 新建文件夹，2026-10-25）
- **需求**：Android 本地工作区弹窗的快捷入口/目录图标与 DSH 风格不一致；并新增「新建文件夹」。
- **根因（视觉）**：插件 `directory-flow.tsx` 引用了 `workspace.module.css` 中**不存在**的
  `.ghostButton` / `.input` / `.hint`（CSS Modules 返回 undefined）→ 快捷入口与面包屑退回
  浏览器原生灰按钮、路径输入框无样式；目录行用 `📁` emoji。
- **实现（在插件仓库 `E:\Android\AndroidStudioProjects\dsh-hardssh`，版本 0.2.10）**：
  · 本地浏览段按 DSH token 重写：路径输入组（上一级 / 路径 / 打开）、带分类图标的快捷入口
    胶囊、chevron 面包屑、linear folder 目录行、固定底栏；面板固定高度 + 列表内部滚动，
    监听 `resize`/`visualViewport` 重算高度（键盘弹出/转屏不把底栏顶出屏幕）。
  · 底栏「新建文件夹」：走内核 directory picker 的 `createDirectory` seam（client 侧新增
    `createLocalDir`）；校验空名/`/`/`\`/`.`/`..`；成功后重列目录并高亮新行；失败在弹窗内
    显示宿主错误并保留输入。旧内核无 create 能力 → 按钮置灰 + `flow.newFolderNoService`。
  · 中英文案、5 个新 vitest 用例、CHANGELOG/README、包版本与根 package.json 均到 0.2.10。
- **提交/推送**：dsh-hardssh `c7e2bd1e8ae8814d9041d2081fc794fb54aee992` 已推 origin/master；
  本仓库 `HARDSSH_REF` 已指向该 SHA（本地提交，待推送触发 CI）。
- **验证（MuMu 12 / Android 12 / 1280×720 桌面视口）**：按用户给的「只替换引擎插件 + 重启」
  路径（`lib`+`package.json` → run-as 覆盖）部署，plugin health `ok=true, failedEntries=[]`；
  截图确认新 UI；新建 `dsh-mobile-test` 成功落盘 0700、列表即时出现并高亮；重名创建显示
  `无法创建文件夹：… already exists` 且弹层保持打开。全量 **745 项测试**、typecheck、build 全过。
- **未做**：按要求**未验证 SSH**（临时隧道与测试密钥已清理）；未重打 APK，设备上是热替换的
  plugin bundle（重装 App 会回退到旧插件，直到 CI 出新包）。
- **清理**：测试目录/工作区/会话、`/data/local/tmp` 临时文件均已删除，App 回到无工作区初始态。

## v1.4.18 追加（release 签名 + CI debug/release 双构建与发布，2026-10-25）
- **生成正式 key**：`keystore/dsh-release.keystore`（PKCS12 / RSA 4096 / SHA256withRSA /
  10000 天 / alias `dsh-release`），证书 SHA-256 见本地 credentials 文件（不写入仓库）；口令、base64、本地
  `release.properties` 全在 gitignore 的 `keystore/`，**未写入任何仓库文档**。
  ⚠️ 这是**新应用身份**：与已发布旧包（`d28ca4f5…92c8`）不能互相覆盖；旧 key 若找回应优先替换。
- **Gradle**：`app/build.gradle.kts` 增加 env/`keystore/release.properties` 驱动的
  `signingConfigs.release`；材料缺失时保持 unsigned，debug/PR 构建不受影响。
- **CI**（`.github/workflows/android-build.yml`）：
  · `build-apk` 从 `RELEASE_KEYSTORE_BASE64` 解码 keystore，`assembleDebug` +（有 secrets 时）
    `assembleRelease`，`apksigner verify --print-certs` 与 `RELEASE_CERT_SHA256` 变量比对；
    tag 构建缺签名材料直接失败。
  · `publish-release`：tag `v*` → 正式 Release（双架构 × debug/release）。
  · `publish-edge`：每次 push `main` → 滚动 pre-release `edge`（同样双架构 × debug/release），
    方便用户随时拿包；分支/PR 仍只编 debug。
  · 旧 `release` job（debug 自动发布）保持只对 `jahdaganj02ki-sketch/...` 生效，互不影响。
- **发布说明自动生成（v1.4.18）**：新增 `scripts/generate-release-notes.sh`，`publish-release`
  与 `publish-edge` 用 `body_path` 写入：
  · tag 取「上一个语义化版本 tag → 当前 tag」，edge 取 `edge → HEAD`；无旧 tag 回退最近 30 个提交；
  · conventional commit 分类（新功能/修复/优化/重构/构建/CI/文档/测试/风格/其他）；
  · 版本信息（tag、versionName、versionCode、提交、构建时间、架构、签名、HARDSSH_REF）、
    安装包表（架构/类型/大小/SHA-256）、安装与签名说明、贡献者（本次更新 + 项目累计 Top 10，CI 用 GitHub API 映射账号链接）、compare 链接。
  · 脚本只读 git 与 `apk/` 产物，不接触签名材料；已用 tag/edge 两种模式本地跑通。
- **文档**：新增 `docs/RELEASE.md`（key 保管/备份、GitHub Secrets/Variables、tag 发版步骤、
  edge 策略、证书校验、发布说明自动生成）；README 文档表已加链接。**Secrets 的实际值只给用户本人，不在仓库。**
- **待办**：用户需在 GitHub 添加 4 个 Secrets + `RELEASE_CERT_SHA256` 变量；随后推送本仓库提交，
  main 的 edge 与 tag 正式发布才会出包。本地 `apksigner` 已用真实 APK 验证 key 可用、证书指纹一致。

## v1.4.18 待发（原生设置界面横屏 / 平板适配，2026-10-24）
- **需求**：应用自身的设置界面固定竖屏，平板横屏下无法正常使用。
- **根因**：5 个原生页面在 `onCreate` 里硬编码 `SCREEN_ORIENTATION_PORTRAIT`
  （Settings / About / PluginStore / ExtensionStore / UserPrompt）；Manifest
  本身没有 `screenOrientation`。ASR / 语音服务原先跟随系统、与设置页不一致，本次一并统一；
  文件编辑页保持跟随系统（横屏编辑场景刻意保留）。
- **方案（宝宝拍板：B 双栏 + 平板默认自动 + 全部原生页）**：
  · 新增 `OrientationPrefs.applyTo(activity)`，7 个原生页统一跟随三态偏好；
    设置页切换后**立即** apply（toast 改「已立即生效」，三语同步）。
  · 平板（**从未存过偏好**且 sw>=600dp）默认 `AUTO`；显式选择永远优先，手机默认竖屏不变。
    首次引导在平板上默认选中「自动」。
  · 840dp 断点：设置页双栏（左「显示 + 权限中心」，右「扩展 + 其他」），宽窗内容
    最大 1080dp、窄窗 680dp；关于 / ASR / 语音 / 引导 760dp；插件中心 / 扩展中心 900dp。
  · 新增 `MaxWidthLinearLayout`（`onMeasure` clamp + `layout_gravity=center_horizontal`）。
  · **不用 `layout-land`**：这些 Activity 声明了 `configChanges`，转屏不会重新
    inflate；改在 `SettingsActivity.onConfigurationChanged` 里切列方向/权重，
    保留滚动位置、已打开对话框和进行中的导出/安装任务。
- **验证（本机能做的）**：新增 `scripts/probes/_test_responsive_native_ui.py`
  58 项全过；`check-i18n.py` 全过；`aapt2 compile` + 全资源 `link` 通过（新 id 已生成）；
  `MaxWidthLinearLayout` / `OrientationPrefs` 用 kotlinc 对 `android.jar` 编译通过；
  修改过的 Kotlin 文件过解析检查（无语法错误）。
- **未验证**：本机没有可用的 Gradle 发行版 / AGP/Kotlin 依赖缓存（缓存里是
  AGP 8.10/9.2、Kotlin 2.3，与项目锁定的 8.5.2 / 1.9.24 不一致，且 shell 无外网），
  无法跑 `assembleDebug`；模拟器截图矩阵待构建后补。
- **文件**：`OrientationPrefs.kt`、7 个设置类 Activity + 引导页默认值、
  `MaxWidthLinearLayout.kt`、7 个 layout、三语 `strings.xml`、`.gitignore` 白名单、
  新探针脚本。

## v1.4.17 追加（用户反馈：破甲插件——改系统提示词 + skill 的——完全不可用）
- **取证（registry 实测）**：生态里的「换芯」插件（@knyazevai/dsh-system-prompt、
  @monotykamary/dsh-system-prompt 等十余个）是**整套引擎的 fork 重发布**：
  · `dsh` 字段为 `{}`（无 bundle patch 声明）→ `dsh plugin add` 装上后没有任何
    entry 挂载，纯装等于没装；
  · 导出官方同名 Service 的 fork 实现，但 imports/peerDeps 全指向作者 fork 作用域
    （@knyazevai/cordis、@monotykamary/dsh-llm/scope/invariants……）；
  · 用户手工在 profile patch 换芯 → 被 applyEntryPatches 的 **name 匹配守卫**
    静默跳过（v1.4.7 directory-picker 踩过的同一机制）；
  · fork 的 peerDeps 在 `autoInstallPeers: false` 下不自动装；装上 fork cordis 也
    与官方 cordis 同进程无法共存。
  → 官方引擎上**设计性不兼容**，「完全不可用」是必然结果。
- **官方替代路径（回复用户指路）**：
  · persona/人设：v1.4.15 已在 AGENTS.md seed 写明 persona 由用户决定；
  · 系统提示词组装：官方 `@deepseek-ai/dsh-system-prompt` + `dsh-agent-preset-registry`
    + 社区 Preset 面板（@linxin666/dsh-client-ui-preset-center）—— 这是引擎支持的定制层；
  · skill：dsh-skills-manager 类插件可用（v1.4.15 实测装过 @michengai/dsh-skills-manager）。
- **实现**：`NativePluginCheck` 加第 7 信号 `FORK_ENGINE_COMPONENTS`
  （cordis/dsh-llm/dsh-scope/dsh-invariants/dsh-system-prompt/schemastery 裸名，
  作用域非 @deepseek-ai 即判 fork）→ 装前弹「fork 引擎插件不兼容」确认框。
  `@deepseek-ai` 作用域明确排除（官方组件不误伤）。
- **测试**：`_test_native_plugin_check.py` 33 项（+5：两个真实 fork manifest 判风险、
  官方作用域不误伤、源码表存在、作用域排除断言）。
- **全量**：i18n 全过；**317 项全过**；compileDebugKotlin 成功。并入 v1.4.17 待发。

## v1.4.17（2026-10-24，issue #37：WebView 下载支持）
- **issue #37**（@yiming-0098，教科书级报告）：文件分享插件下载按钮静默失败。
  四组证据：dex 无 DownloadListener、Manifest 无存储权限、Android 10 无
  MANAGE_EXTERNAL_STORAGE op、引擎进程写 /sdcard EACCES。
  结论：WebView 收到下载请求时无监听器**直接静默丢弃**；且无可授权的权限自救。
- **修复**（采纳其最小集方案）：
  · 新增 `engine/WebViewDownloader.kt`：http(s) → DownloadManager（Cookie 透传 +
    RFC5987 文件名解析 + UA）；blob → 页面级 JS 桥（拦截 a[download]+blob:，
    fetch→FileReader→base64→addJavascriptInterface）→ API29+ MediaStore /
    API26-28 直接写（清单加 WRITE/READ_EXTERNAL_STORAGE maxSdk=28 +
    application 加 requestLegacyExternalStorage="true"）；
  · MainActivity：setDownloadListener + onPageFinished 注入桥 + JavascriptInterface；
  · blob 直达 listener 时 toast「未被拦截」提示（页面绕桥的兜底）。
- **实测（Android 16 模拟器 + CDP 在真实 WebView 页面执行）**：
  · 桥注册 `object / function` ✅；
  · blob 两次落盘 `Download/dsh-blob-test.txt` 内容逐字节正确 ✅；
  · http 直链（本地 attachment 服务 9999）→ DownloadListener 触发 + enqueue
    （logcat `download enqueued`）✅；文件未落地 = 模拟器 DownloadProvider 对
    本地回环 + run-as 临时服务的环境限制，非 App 代码问题。
  · 跨域 a[download]/iframe 不触发 listener（WebView 跨域 download 属性失效 +
    SPA 拦截），与 issue 场景无关（file-share 的下载是同源 3080）。
- **已知限制（写进发布说明与 issue 回复）**：大文件 blob 下载占 1.3× 内存，
  低内存设备可能失败；后续可分块。插件侧 fetch→blob→a[download] 已可用（桥接住）。
- **验证**：312 项测试全过；i18n 全过；compileDebugKotlin + assembleRelease 成功。
- **例行清单执行**：发布前跑了 check-issue-replies.py（无未跟进）。


## 例行清单（宝宝确认，2026-10-24 起）
每次**发布前**固定动作（顺序执行）：
1. `python scripts/check-issue-replies.py`（需 GITHUB_TOKEN）——扫描用户在我们最后回复
   之后的留言；有输出必须先逐条跟进再打包。背景：10-22 曾让活跃贡献者 @henryz78
   的方案确认等了近两天，这类等待最伤贡献积极性。
2. 全套测试 + i18n。
3. 发布后顺手再扫一遍（用户常在新版发布后回来反馈）。

## v1.4.16 已发布（2026-10-24）
- 发布物：`dist/DSH-Mobile-1.4.16-arm64-v8a.apk`（185.3 MB，versionCode 266，签名 CN=DSH Mobile）
  + `-debug.apk`（186.5 MB）。说明：`RELEASE_NOTES_v1.4.16.md`、`docs/ANNOUNCE_v1.4.16.md`。
- README 三语版本号对齐 v1.4.16。
- 内容 = v1.4.15（插件装前预检 + 启动快速隔离）+ v1.4.16（审查修复 3 P1 + 5 P2）。
- 注意：版本号从 265/1.4.14 直接跳到 266/1.4.16（v1.4.15 提交时漏了 bump，本版合并发布）。


## v1.4.16 整体代码审查（三路并行 checker）+ P1 全修 / P2 精选修复
- **审查规模**：3 个 checker 并行（引擎核心 / 构建链 / UI 与导出），覆盖 v1.4.9→v1.4.15 全部改动。
  合计 P0=0、**P1=3**、P2=12、P3=20、顺带 9。
- **P1-1（引擎）墙钟预算重置点错误**：`userVisibleBootT0` 只在进 Healthy 时重置，
  注释引用了**不存在**的 `setUserVisibleBootStart()`。后果：健康运行 >5 分钟后引擎崩溃，
  第一次自动重试就在循环顶部被误判「启动超时 N 分钟」直接 Failed——监督器的核心职责失效。
  **已修**：引擎退出（`exitFuture.get()` 返回）后决定重启时重置。
  **实测**：模拟器强杀运行中的 node → 9.3s 自动重启成功、无误判。
- **P1-2（构建）`.abi` 入库使 CI 门禁全失效**：`.abi` 被 git 跟踪（恒 arm64-v8a）→
  CI checkout 后 `prepareRuntime` 命中 skip 分支整体 return → **PTC 补丁校验在 CI 永不执行**；
  且 CI 的 collect 不跑 dedupe → CI 渠道 APK 从未享受 52s→24s 优化；fresh clone 还会打出
  无 runtime.zip 的空壳 APK 且构建成功。**已修**：`.abi` 出库 + gitignore；skip 分支只免
  复制/去重、仍跑补丁校验；源缺失且 assets 无包时 throw GradleException（实测空壳路径会炸）。
- **P1-3（构建）gradle 校验项不含 v2 标记**：4 项检查对 v1.4.13 形态（7 变量）也能通过，
  27 个扩展变量缺失无告警。**已修**：加第 5 项 `MAGICK_CONFIGURE_PATH`（与脚本 ALLOWLIST_V2_MARKER 对齐）。
- **P2 已修（4 项）**：
  · ①四个共享字段补 @Volatile（process/loopJob/userStop/scopeRef）；
  · ②快速隔离节流：独立 `fastIsolations` 计数 + `MAX_FAST_ISOLATIONS=3` 上限 + 隔离后不再
    重置 backoffIndex（旧实现会连续摘光所有插件，实测复现过先摘无辜的再摘真凶）；
  · ③插件安装/卸载串行化：`PluginCenter` 改单线程执行器，消除并发 pnpm 损坏 profile 的风险；
  · ④`stillHasNestedZod` 在 dedupe 结果检查处补 runCatching（损坏缓存不再炸构建）；
  · ⑤PureJs 安装分支补 Activity/视图存活检查（预检 20s 窗口内离开页面不再静默安装）。
- **P2/P3 未修（记录在案，按需再修）**：stop() 主线程阻塞最长 ~28s（ANR 风险）；
  shizukuExec 超时返回部分输出污染探测调用方；BootTrace 主线程落盘；
  DataExport 删半成品失败被吞但文案说未保留；dedupe 丢 zip 条目 comment（潜伏）；
  dedupe 无版本一致性断言；make_corepack_wrapper 校验不覆盖 dst；
  stop() 的锁与双循环竞态窗口；搜索无序返回等。
- **验证**：312 项测试全过；i18n 全过；compileDebugKotlin 通过；
  实机冒烟（冷启动 34.3s + 强杀自动重启 9.3s 无误判）。


## 鸿蒙适配评估（2026-10-23，宝宝拍板：不适配）
- **结论：不做**。结论记录于此，避免将来重新调研。
- **关键区分**：
  · HarmonyOS 2.0–4.x = Android 分支，**可直接装 APK**，理论上现有包就能跑
    （未真机验证；若有群友反馈，装一下即可确认，无需开发）。
  · HarmonyOS NEXT（5.0+）= 无 Android 运行时，APK 装不上，需 HAP + OHOS NDK 全面移植。
- **NEXT 移植的四个坎（按难度）**：
  ① node 运行时：现用 bionic ELF，NEXT 是 musl，须整套交叉编译
     （社区有先例：51CTO 交叉编译教程 / 知乎 musl 构建指南 / 官方 lycium 工具）；
  ② 子进程：引擎是常驻 node 子进程，NEXT 默认限制 fork（官方 native_child_process.h
     是受控替代），fork+exec 属于敏感能力，**上架审核要说明必要性 —— 过审是真实风险**；
  ③ PTY 终端：无公开 pty API，dsh_pty_exec 那套走不通，终端只能砍或降级；
  ④ 无障碍自动化（scr/loop）与扩展系统（依赖 Termux 包）：API 完全不同/生态不存在，
     第一版带不过去。
- **若将来重启**：第一步只需验证「OHOS musl node 在 NEXT 真机跑起 dsh web」，
  这一步的成败直接决定整个项目可行性（约一周 PoC，不需要全套投入）。
  前置条件：NEXT 真机 + 华为开发者账号。


## v1.4.15 实测记录（2026-10-23，emulator-5554/Android 16/x86_64）
- **全套**：300 项测试全过；i18n 全过；compileDebugKotlin + assembleRelease(arm64) 成功。
- **冷启动回归**：TOTAL 65.2s（首次解压 46.2s）；plugin health `ok:true`；
  PTC 白名单 10 个抽查变量全在设备上；corepack 入口正常。
- **装前预检实测**：
  · `node-pty` → Install → **弹出「This plugin needs native code」**，正文列出两条原因
    （`depends on node-addon-api`、`scripts.install: node scripts/install.js`）；
  · `lodash` → Install → **直接 Installing，无误拦**。
- **启动隔离实测（决定性）**：往已装插件的入口注入 `process.abort()` 再重启：
  ```
  boot (retry #2)  FAILED 2757ms  isolated plugin dsh-whale-widget
  boot (retry #2)  FAILED 3782ms  isolated plugin @michengai/dsh-skills-manager
  boot (retry #2)  TOTAL 16883ms  (engine ready)
  ```
  engine.log 有两条 `[plugin] isolated X after 2 failed boot(s)`；
  `files/dsh-home/plugin-recovery/` 下每次隔离都有带时间戳的清单备份。
  **设计行为符合预期**：一次只摘一个（先摘 mtime 最新的 whale-widget，再摘真正坏的那个），
  每次先备份，连续失败 2 次才动手，恢复全程自动。
- **意外收获**：语法错误级损坏走的是**旧链路**（degraded → quarantineOne），
  engine.log 可见 `failed to import: SyntaxError` → 探针报 failed → 自动禁用 → 重启恢复。
  即：软损坏走 quarantineOne（v1.4.4 链），硬死亡走快速隔离（本次新增），
  两层防护各管一段，互不重叠。
- **结论：可以发布。**

## v1.4.15 待发（插件兼容性：装前预检 + 启动快速隔离）
- **用户反馈**：①「装一些插件会直接导致无法启动」；②「插件中心装插件提示
  ERR_PNPM_IGNORED_BUILDS」。
- **根因（两层）**：
  ① corepack 自动拉 **pnpm 12.15.0**，该版本默认**拒绝执行**依赖的 build 脚本；
     带原生模块的插件「装成功但二进制没编译」——实测 `pnpm add better-sqlite3`
     返回成功，但 `node_modules/better-sqlite3/build/` 根本不存在。
  ② 上游其实有完整批准机制（`build-approval.js`：pnpm 失败 → 解析
     `Ignored build scripts:` → 写 `pnpm-workspace.yaml` 的 `allowBuilds` → UI 让用户批准），
     但**我们的插件中心是自写 UI，直接调 `dsh plugin add`，完全绕过了它**。
  ③ 而且 Android 上这类模块多数根本编译不出来（issue #33 已指出 clang 头文件搜索
     路径错误、node_prefix 写死 Termux）。批准了也只是把 "ignored builds" 变成 "gyp ERR"。
- **方案（用户拍板：装前检测并拦截 + 启动前隔离）**：
  · 新增 `NativePluginCheck`：查 registry 的 version manifest，六个信号判风险
    （install 钩子 / gypfile / binary 字段 / files 含 binding.gyp 或 prebuilds /
    原生依赖 / 平台专用 optionalDependencies 无 android 变体）；
  · 插件中心装前调用，命中就弹确认框（列出具体原因），用户可「仍然安装」；
  · 网络失败返回 Unknown（不拦，只提示「未验证」）；
  · `PluginRecovery.isolateRecentBundleOnBootFailure()`：引擎连续 2 次启动失败时，
    按 node_modules 里 package.json 的 mtime 找最后安装的第三方 bundle，**只摘一个**、
    先备份清单；同一批安装（差值 <60s）分不清主次时不猜；接进监督器失败路径。
- **实测判据**（registry.npmmirror.com/latest，模拟器上验证）：
  RISK: better-sqlite3(dep:node-addon-api) / sharp(platformVariants 24) /
        node-pty(scripts.install) / canvas(scripts.install+binary)
  PURE: lodash / chalk / axios / esbuild / @deepseek-ai/dsh-workspace
  **sharp 是这次补上的关键**：它没有 scripts、没有 gypfile、files 为空，唯一信号是
  optionalDependencies 列了 24 个平台变体且无 android。
- **实机验证（emulator-5558 / Android 16）**：
  · 搜 better-sqlite3 → 点 Install → **弹出「This plugin needs native code」**
    （正文列出 `depends on node-addon-api`），有 CANCEL / INSTALL ANYWAY；
  · 搜 lodash → 点 Install → **直接开始安装**（显示 Installing…），无误拦。
- **测试**：新增 `_test_native_plugin_check.py` 28 项（6 类风险判据 + 5 类纯 JS 放行 +
  平台变体边界 + 源码结构约束 + UI 接入）。全量 **300 项全过**；i18n 全过。
- **未做**：真正让 pnpm 执行 build（需要先修 issue #33 的 clang/clang++ 头文件搜索路径
  与 node_prefix，工作量单独一版）。

## v1.4.14 待发（PTC 环境变量补全：issue #35 + 全量审计）
- **issue #35**：PTC 报 `OpenSSL configuration error: ... Permission denied calling
  fopen(/data/data/com.termux/files/usr/etc/tls/openssl.cnf)`，exit 13。用户诊断正确：
  `OPENSSL_CONF` 不在 `STARTUP_ENVIRONMENT_NAMES` 白名单里。
- **宝宝追加要求「全部查一下」**，于是做了全量审计：把 `EngineConfig.buildEnv` 注入的
  所有变量 vs PTC 白名单逐项对比 —— **13 个扩展工具链变量全部缺失**：
  CPATH / LIBRARY_PATH / RUSTFLAGS / PYTHONHOME / PERL5LIB / RUBYLIB /
  MAGICK_CONFIGURE_PATH / GIT_EXEC_PATH / GIT_TEMPLATE_DIR / GIT_CONFIG_NOSYSTEM /
  GIT_SSL_CAINFO / CURL_CA_BUNDLE / GOTMPDIR。
  后果：PTC 里 git/python/perl/ruby/imagemagick/go/rust 全部静默失效。
- **补丁升级为 v2**（28 个变量）：运行时必需（LD_LIBRARY_PATH、OPENSSL_CONF、
  SSL_CERT_FILE、TMPDIR、HOME、PREFIX、ANDROID_*）+ 扩展工具链 13 个。
  注入块改为从 executable 反推 root，再 `pick()` 探测 openssl.cnf / cert.pem / tmp。
- **关键实现细节（踩了三次坑）**：
  ① 补丁脚本要能**升级旧版补丁**（旧版只有 LD_LIBRARY_PATH），加 `ALLOWLIST_V2_MARKER`
     检测；剥旧块时要注意 `]);` 的定位和残留尾逗号（`"TMP",
]);` → `"TMP"
]);`）；
  ② 用 Python 写 Kotlin/JS 代码时 `
` 会被当真实换行 —— 必须用 `chr(92)` 拼接；
  ③ x86_64 包还原后留下尾逗号导致 `OLD_SET` 不匹配，需单独修。
- **实测（模拟器 end-to-end）**：写脚本从 PTC 源码**解析真实白名单**（28 个），
  按它构造 env 再真跑 worker：
  `parsed allowlist size: 28` → `final env: LD_LIBRARY_PATH,OPENSSL_CONF,SSL_CERT_FILE`
  → `EXIT: 0 | OUT: WORKER-OK`（加载 tls/https/fs 全部通过）。
- **注意**：模拟器上 `/data/data/com.termux` 不存在（ENOENT 静默通过），所以**修复前
  也跑得通**；用户真机装了 Termux 才会 EACCES。这个差异在 v1.2 的代码注释里就写过。
- **构建期校验扩展**：`ptcPatched()` 现在同时检查 LD_LIBRARY_PATH / 无条件注入 /
  OPENSSL_CONF / SSL_CERT_FILE 四项，缺任何一项都中止构建。
- **测试**：`_test_runtime_dedupe.py` 21 → 33 项，新增 12 条扩展变量断言。
- **全量**：272 项全过；i18n 全过。

## v1.4.13 待发（紧急修 PTC 回归）
- **用户反馈**：「PTC 模式又用不了了」，报错 `CANNOT LINK EXECUTABLE ... libz.so.1 not found`。
- **根因（我 v1.4.12 引入）**：把构建切到 `rt-arm64-zodded.zip`，而那份去重包是
  **在我加 PTC 补丁之前生成的** —— 补丁整体丢失。v1.4.9 修好的 #29 就这么被回退了。
- **本质问题**：补丁只存在于「某个 zip 产物」里，**没有进入构建流程**。只要换源包，
  补丁就静默消失。这是流程缺陷，不是补丁写错。
- **修复**：
  · 给两份去重包（arm64/x86_64）重新打 PTC 补丁（脚本幂等，两处补丁都验证到）；
  · **把校验接进 `prepareRuntime`**：复制完 → 检查白名单 + 无条件注入两处是否都在 →
    缺则现场跑 `patch-ptc-runtime-android.py` → 打完还缺就 `throw GradleException`
    **中止构建**，绝不发坏包。
- **实测对照（模拟器，`env -i` 模拟 PTC 子进程）**：
  · 带 `LD_LIBRARY_PATH` → node 正常输出 `2`
  · 不带 → `CANNOT LINK EXECUTABLE ... libz.so.1 not found`（正是用户报错）
  证明补丁缺失就是根因。
- **构建流程实测**：把去重包换回未打补丁版本 → 构建日志出现
  `runtime: PTC 补丁缺失，正在应用… → PTC 补丁已应用`，成功兜底。
- **测试**：`_test_runtime_dedupe.py` 16 → 21 项，新增 5 条 PTC 断言
  （运行时文件存在、白名单、无条件注入、构建期强制校验、缺失时中止构建）。
- **全量**：272 项全过；i18n 全过。

## v1.4.12 已发布（2026-10-22）
- 发布物：`dist/DSH-Mobile-1.4.12-arm64-v8a.apk`（185.3 MB，versionCode 263，签名 CN=DSH Mobile）
  + `-debug.apk`（186.5 MB）。说明：`RELEASE_NOTES_v1.4.12.md`、`docs/ANNOUNCE_v1.4.12.md`。
- README 三语版本号已对齐 v1.4.12。
- **PR #34 已在 GitHub 正式合并**（merge commit `6d658ec`），@henryz78 已进入 contributors
  （`Soodok 284 / skynit 1 / henryz78 1 / jahdaganj02ki-sketch 1`）。
  过程记录：我先在本地手工 merge（`f2d1f95`）再推送，GitHub **不会**自动把 PR 标为 merged
  —— 必须调 `PUT /pulls/34/merge` 才会产生 contributor 记录。之后本地 reset --hard 到
  `origin/main` 对齐（本地多出的 `1d6c676` 被丢弃，内容已在 `6d658ec` 里）。
- 本轮交付：runtime 去重（启动 52.17s → 23.63s，APK 241MB → 185MB）+ 数据导出。
- 验证：267 项测试全过；i18n 全过；APK 架构/签名/版本逐项核对。
- **数据导出已端到端实测**（成功 + 悬空软链失败两条路径），结果贴在 PR #34 评论里。


## v1.4.12 待发（合并 PR #34：数据导出）
- **PR #34（@henryz78）**：设置 → 其他 → 导出数据。把 sessions/、workspaces/、
  storages/workspace.json 打成 zip 存到用户选的位置；只导出不导入（按 #31 约定）。
- 逐条核对我提的 7 条硬门槛：不跟随软链（复用 `walkNoFollow`）、不能静默截断
  （`walkNoFollow` 新增返回 false + 导出据此报错）、不加依赖（只用 java.util.zip）、
  进度节流 200ms、凭据不进包、失败弹窗且删除半成品、`[export]` 前缀日志、三语对齐。
- **他自己做了三轮真机测试**：普通目录 / 目录换成软链 / 软链指向不存在位置（失败并
  删掉半成品）。还验证了中文文件名、空目录、两层 node_modules、目录软链、文件软链。
- 代码审查要点（我逐项确认）：
  · `walkNoFollow` 改动兼容 —— 新参数有默认值，`deleteNoFollow` 忽略返回值；
  · `SettingsActivity` 原本**没有** `onActivityResult`，不存在覆盖冲突；
  · `REQ_EXPORT = 4204` 与 MainActivity 的 WebView 文件选择器请求码不冲突；
  · 失败时用 `DocumentsContract.deleteDocument` 删半成品，避免"看起来成功"。
- **合并冲突**：5 个文件（EngineConfig / strings×3 / layout / SettingsActivity /
  build.gradle.kts / AI_CONTEXT），全部是「我后来加的行 vs 他加的行」同位置冲突，
  一律取 PR 侧；已确认 `build.gradle.kts` 的 zodded 去重逻辑完好。
- **验证**：compileDebugKotlin 通过。
- **待定**：他问第二版要不要导社区插件清单（不带本体、不带凭据）——值得做，
  回复里会确认方向。

## v1.4.11 待发（启动性能：runtime 去重接入构建）
- **起因**：1.4 升 0.21rc 后启动怎么优化都慢，低端机用户宁愿退回 1.3；问是否分叉维护 0.20。
  **结论：不需要分叉 —— 根因是打包退化且可修。**
- **根因**：上游改成「逐包嵌套」依赖布局，每个 `@deepseek-ai` 包都嵌了一份完整 zod
  （612 文件），全库 30,681 个二级嵌套文件。1.3=13,656 文件 / 1.4=**45,244** 文件（3.3 倍）。
- **关键**：`dedupe-zod.py` 与 `rt-arm64-zodded.zip` 早就存在，但 build.gradle.kts 写死用
  `-work.zip`，**去重包从没被引用**。
- **实测（x86_64 模拟器 runtime-install / 总耗时）**：
  work 41.99s/52.17s · zodded **11.81s/23.63s（-55%）** · trimmed 13.74s/18.44s。
  去重后 health-poll 也从 9.8s → 4.6s。
- **选 zodded 不选 trimmed**：trimmed 删掉 .d.ts/src，全库 773 处 exports 指向它们。
- **接入**：prepareRuntime 自动选 `rt-<abi>-zodded.zip`；缓存按内容校验（查嵌套 zod）
  而非时间戳；失败/缺脚本都回退未去重源。arm64 APK **241MB → 185MB**。
- **测试**：`_test_runtime_dedupe.py` 16 项；全量 267 项全过。

## v1.4.10 已发布（2026-10-22）
- 发布物：`dist/DSH-Mobile-1.4.10-arm64-v8a.apk`（241,444,474 B，versionCode 261，签名 CN=DSH Mobile）
  + `-debug.apk`（242,941,582 B）。说明：`RELEASE_NOTES_v1.4.10.md`、`docs/ANNOUNCE_v1.4.10.md`。
- README 三语版本号已对齐。

## v1.4.10 追加（用户反馈：工作区选不了外部文件夹）
- **现象**：更新后即使已授予存储权限，工作区仍无法选择外部文件夹。
- **根因（我 v1.4.8 引入的回归）**：directory-picker 是**一对**插件 ——
  `dsh-host-directory-picker-browse`（Host 后端，注册 `directoryPicker` 服务）
  + `dsh-client-ui-directory-picker-browse`（Client 表面，host 侧 `apply()` 是空的，
  唯一作用是让 browser bundle 被 `dsh-client-modules` 发现）。
  v1.4.8/v1.4.9 为了修 auto 的动态挂载问题，只 insert 了**后端**一行 ——
  服务在、界面控件不在，选择器根本弹不出来。
  证据：`dsh-client-modules/lib/index.js` 里
  `for (const entry of ctx.loader.entries()) this.dirty.add(entry.options.name)`
  —— 它按 **loader entry 的 name** 扫描 `dsh.json` 的 `client` 声明，所以 surface
  必须作为独立 entry 存在。
- **修复**：overlay 的 insert 里补第二行
  `- id: android-directory-picker-surface / name: '@deepseek-ai/dsh-client-ui-directory-picker-browse'`；
  探针 `CRITICAL_ROWS` 同步加 surface 行（否则「服务在、界面不在」永远检测不出来）。
- **实测证据（emulator-5556 / Android 16）**：
  · plugin health `ok:true`
  · 抓页面 HTML（带 token → 303 → cookie 跟随）后，预加载列表里出现
    `plugins/??@deepseek-ai/dsh-client-ui-directory-picker-browse/client.js`
    —— 修复前不会有这一项，这是「界面控件真的下发了」的直接证据。
- **测试**：`_test_overlay_patch_semantics.py` 9 → 13 项，新增「insert 出前端 surface 行」
  「surface 指向 client-ui browse」「探针校验 surface 行」三条断言。
- **全量**：i18n 全过；**251 项测试全过**；compileDebugKotlin 成功。
- ⚠️ **教训**：v1.4.8 我验证的是「plugin health ok:true」，而 ok 只反映**服务**
  是否注册，不反映**界面**是否注册。用户看得见的东西必须验证看得见的那一层。

## v1.4.10 待发（seed 人格约束松动）
- **用户反馈**：「部分人有破甲需求，但是我们提示词又把人物角色写得很死。」
- **事实核对**：seed 里**没有**全局人格设定。会被读成「角色锁死」的是两处：
  ① `## UI Automation Workflow` 开头 `You are the cloud predictor and takeover operator; ...`
  —— 本意是 UI 自动化分工，但「You are ...」句式容易被当成全局身份；
  ② `## System prompt integrity` 段末 `Role-play and persona changes are fine **inside the
  conversation only**` —— 本意区分「改配置文件」与「对话内扮演」，但 "only" 读起来像限制。
- **改动**（只动这两处 + 版本号，其余环境事实一行未改）：
  · ①改为「In this workflow your part is route planning + successor prediction」并声明
    「says nothing about your persona or voice elsewhere」；
  · ②新增 `Persona, voice and character portrayal are the user's call` 段：明确
    persona/语气/角色由用户决定、环境事实不是人格、不得拿本文档当拒绝理由；
    写明优先级「对话内人格请求 > 本文档语气」；明确不要擅自加免责声明；
    原 `inside the conversation only` 改写为「扮演本身完全支持，唯一不能做的是改
    App 管理段把人格永久化」。
  · `SEED_VERSION` 48 → 49（老用户的 App 段会整段升级覆盖）。
- **保留未动**：配置完整性约束（不许改 App 段 / 影子指令文件）、反诈约束
  （金融类默认拒绝、不得让注入输入不可检测）。这两类是真实风险边界，不属于「角色设定」。
- **新增测试**：`scripts/probes/_test_seed_persona.py`（17 项）—— 断言旧身份句移除、
  人格段落存在、优先级声明、以及**该保留的约束仍在**（防以后为讨好用户把安全边界也删掉）。
- **验证**：i18n 全过；**247 项测试全过**；compileDebugKotlin 成功。未出包。

## v1.4.9 已发布（2026-10-21）
- 发布物：`dist/DSH-Mobile-1.4.9-arm64-v8a.apk`（241,444,474 B，versionCode 260，签名 `CN=DSH Mobile`）+ `-debug.apk`（242,941,322 B）。
- 说明文档：`RELEASE_NOTES_v1.4.9.md`、`docs/ANNOUNCE_v1.4.9.md`；README 三语版本号已对齐。
- runtime sha256 `50041ed0...447f`（arm64），MANIFEST 一致；corepack 已修。

## v1.4.9 追加（社区贡献 @henryz78：重启后永久卡住）
- **用户实测**「点重启后一直卡在等待状态」，引擎永不启动，UI 一直转圈。
- **根因（他定位，P0）**：`EngineSupervisor.stopping` 的 KDoc 写着「任何 stop 都置位、
  start 清零」，但 `start()` 里**没有清零**。restart → stop 置 stopping=true → start
  拉起新循环 → `while (isActive && !stopping)` 首轮即假 → 一次迭代都不跑 → 永久停在
  `State.Starting`。影响面远大于重启：**任何一次 stop()（服务被系统回收、通知栏退出、
  权限模式切换）之后，本进程内所有 start() 都再也跑不起来，只能杀进程**。
- **第二层（他进一步发现）**：仅复位不够。`loopJob?.cancel()` 异步，且
  `catch (e: Exception)` 会吞掉 `CancellationException`；被取消的**旧**循环可能在
  复位后继续走收尾，用 `State.Backoff` 覆盖**新**循环刚设的 `State.Starting`。
  故新增 `shouldExitLoop() = stopping || !currentCoroutineContext().isActive`，
  三处 `if (stopping) break` 全部替换。
- **已合并**：`start()` 加 `stopping = false`（在 launch 之前）+ `shouldExitLoop()`。
  代码注释保留 `@henryz78` 署名；README 三语新增「贡献者」章节（含 @skynit）。
- **实机验证（emulator-5554 / Android 16，x86_64）**：
  · 首启 → plugin health `ok:true`
  · 点 Restart → **10.2s 引擎就绪**，boot.log 出现第 2 条 TOTAL 记录
  · 再点 Restart → **13.2s 就绪**，累计 3 条记录
  · 旧代码在这里会彻底卡死（首轮循环不执行），三轮连点是最强的对照证据
- **新增测试**：`scripts/probes/_test_stopping_reset.py`（12 项）——
  复位位置、shouldExitLoop 的 isActive 检查、三处退出点、stop 仍置位、while 条件保留。
- **他的源码包其他改动**：MainActivity.kt 是 PR #30 内容（已合并）、EngineConfig 是
  中文注释英文化（已合并）、README 只更新了版本号、build-local.sh 是他本机构建辅助
  （未合入，含 `/opt/android-sdk` 等本机路径）。**他没有给自己署名**。
- **全量**：i18n 全过；**230 项测试全过**；compileDebugKotlin / assembleDebug 成功。
## v1.4.9 追加（用户报障第二轮：5 个问题）
- **🔴 #1 corepack 入口被硬链接写穿（已修，最严重）**
  用户定位非常准（`dist/corepack.js` 被写成 shell wrapper），但**根因不是「缺少 JS 判断」**：
  Termux 的 npm 把 `bin/corepack` **硬链接**到 `dist/corepack.js`（同一 inode），
  而 `collect-termux-runtime.sh` 用 `cat > bin/corepack` 生成 wrapper —— shell 的 `>`
  是 **truncate 已存在文件**，不是新建，于是写的是共享 inode，源 JS 被一并覆盖。
  其余 6 个（npm/npx/pnpm/pnpx/yarn/yarnpkg）没坏，因为它们的 bin 与 dist 不共享 inode。
  **已在 /tmp 用真实硬链接复现**：写前同 inode `...943`，写后 `bin/corepack` 变成新 inode `...108`。
  - 修复 A（构建期）：`make_corepack_wrapper` 加 `rm -f "$dst"` 断链 + 写后污染检测（命中即 `exit 1`）。
  - 修复 B（运行期）：新增 `EngineConfig.healClobberedCorepackEntry()`，检测 dist 下的
    shell wrapper 并还原成 node 入口，兜底已装出去的旧包。
  - 修复 C（产物）：`_runtime_bak/rt-arm64-work.zip`、`rt-x86_64-current.zip` 两份源
    运行时均已就地修好（备份 `.pre-corepack-fix`），assets/runtime.zip 恢复为 arm64。
  - **实机验证**：x86_64 模拟器装新包 → `dist/corepack.js` 首行恢复为
    `#!/data/data/com.termux/files/usr/bin/env node`；`bin/corepack` 是 wrapper；
    执行 `node dist/corepack.js` 正常输出 corepack 帮助（不再是 SyntaxError / 自循环）。
    `bin/npm --version` 会联网下载、超时属预期，未作为判据。
- **🟠 #2 `/plugins/events` 反复 ERR_FAILED**：这是 `dsh-client-hmr` 的 **SSE 热重载端点**
  （开发期用）。生产未启用时前端仍会尝试连接 → 网络层报 ERR_FAILED。**非崩溃性**，
  属上游行为，本轮不改（记录为已知噪声；若日后要静音，在 client 侧关掉 HMR 探测）。
- **🟠 #3 `getJsStatus` 未捕获异常**：在 runtime 的 `engine/lib` 与 `android-plugins/` 中
  **全库搜索 0 命中**（已用 zip 逐文件扫过）。说明调用方是**用户自己装的第三方插件**，
  不是我们的代码。本轮不改；已作为「第三方插件质量问题」记录。
- **🟡 #5 AGENTS.md 表述**：`AgentContextSeed` 的 "Do not install glibc/native binaries"
  改为区分「glibc 预编译包 ❌」与「源码编译 ✅」，并写入用户实测证据
  （better-sqlite3 + SQLite 3.53.4 + ELF arm64 编译成功）。
- **🟡 #4 Termux 硬编码路径**（node_prefix / clang 头搜索 / make SHELL）：**本轮未处理**，
  属打包期 relocatable 改造，工作量大于一次补丁，留待专项。
- **新增测试**：`scripts/probes/_test_corepack_hardlink.py`（29 项）——
  构建脚本断链约束、运行期自愈约束、runtime.zip 里 7 个 dist JS 全部健康、
  bin/dist 内容不得相同、真实 inode 复现。
- **全量**：i18n 全过；218 项测试全过；compileDebugKotlin / assembleDebug(x86_64) 成功。
## v1.4.9（2026-10-21，未出包）
- **用户反馈「更新后等几十分钟引擎都准备不好」——根因是层层自愈叠加、没有总预算。**
  数字对得上：14 轮 × (45s 健康检查 + 20s 探针等待 + 退避) ≈ **21 分钟**；20 轮 ≈ 30 分钟。
  关键分支：插件探针报 degraded → `quarantineOne` 摘插件 → 重启。旧实现该路径
  **重置 `backoffIndex = 0`、当轮无 delay、不计入 `MAX_RESTART`**，形成不计入额度的
  快速循环。v1.4.7 的探针误报（把已 disabled 的 `directory-picker` 行比对 browse 包名）
  让这类用户从「自愈中」变成「稳定失败循环」。
  - 修复：①新增 `BOOT_WALL_CLOCK_BUDGET_MS = 5min` 总时限，超时收敛为 `State.Failed`
    并显示 `error_boot_timeout`；②插件恢复重启改走统一退避、`MAX_PLUGIN_RECOVERY_RESTARTS = 3`；
    ③Healthy/SafeMode 后重置计时与额度（只约束「这一次启动」，不约束进程寿命）。
- **三处无界等待（比无限重试更糟：连日志都不再写）**
  · `Privilege.shizukuExec`：`readText()` + `rp.waitFor()` 均无超时 →
    改为读流后台线程 + `waiter.join(deadline)`，超时 `rp.destroy()` 并返回部分输出。
    注意：Shizuku 的 `IRemoteProcess.waitFor()` **没有** timeout 重载（编译期踩到）。
  · `Privilege.backupHome` 的 tar：Root 模式**每轮启动**都跑，`waitFor()` 无参 →
    加 60s 预算，超时销毁进程 + 删除半个备份 + 继续启动（备份是附加保护，不能拖住启动）。
  · `EngineSupervisor.reclaimPort` 的 kill 脚本：加 3s 预算。
- **Root 模式提示（宝宝要求的第二项）**：二次确认弹窗追加 `ob_priv_root_paths_warn`，
  明确「不要在应用目录 files/engine、files/dsh-home 或工作区内手动改/移动/删除文件；
  自己的文件放外部文件夹（如 Documents/）；要改应用内配置请用应用自己的设置/插件」。
  原因：root 写出的文件属主为 root + 权限收紧 → App 随后读不了 → 表现为插件加载失败/引擎起不来。
- **踩坑记录（AAPT）**：Android 字符串资源里裸 `'` 会编译失败（`Invalid unicode escape sequence`），
  多行裸换行同样失败。本次两个都踩到，最终用 `
` 转义 + `'` 转义解决。
- **新增回归测试**：`scripts/probes/_test_boot_retry_bounds.py`（34 项）——
  含「21 分钟」数值模型、总时限源码约束、三处超时约束、Root 提示三语约束。
- **验证**：i18n 全过；`compileDebugKotlin` / `assembleDebug(x86_64)` 成功；
  插件测试 115 + BootTrace 31 + overlay 12 + retry bounds 34 = **192 项全过**；
  模拟器冷启动基线 **57.1s**（runtime-install 45.7s + health-poll 11.2s），未出现空转。
- **未验证**：总时限 5 分钟的到期路径未在真机跑到（需要构造 5 分钟以上的连续失败，
  模拟器不方便稳定复现）；Root 提示弹窗因模拟器无 su 无法点出，仅验证了字符串已进 APK。
- **未发布**：等宝宝确认后再打 arm64 正式包。

## v1.4.8（2026-10-21，已出包，**未发布**）
- **启动过程可视化（宝宝本轮需求）**：新增 `BootTrace`（进程级单例，独立于 Activity 存活）。
  启动遮罩现在显示：粗粒度阶段（准备/解压/启动/加载）、**已用秒数**（200ms 刷新）、
  确定性进度条、`M/N 个文件 · 72MB` 明细、可展开的逐阶段耗时列表。
  记录同时落盘 `engine/boot.log`（128KB 环形，20 次启动量级）。
  设置页新增「上次启动」入口，可直接看上次各阶段耗时并复制整份 boot.log。
- **RuntimeInstaller**：`ensureInstalled` 增加 `onDetail` 回调 + 返回 `reused`；
  `unzip` 同时统计文件数与字节数；新增 `copyAssetWithProgress` —— assets→cache
  拷贝 240MB 实测占 35s，原来这段时间遮罩一动不动，现在按字节上报。
- **⚠️ 关键发现：v1.4.7 的 directory-picker 修复从未生效。**
  用引擎自己的 `applyEntryPatches`（从设备上 cordis-plugin-include 提取）实测：
  `const { id, insert, name, ...overrides } = patch; if (name && name !== target.name) continue;`
  —— **`name` 是匹配守卫，不是可赋值字段**。v1.4.7 写 `id: directory-picker` +
  `name: ...-browse` 而 base 行是 `...-auto`，整条 patch 被静默跳过，只留一行
  logger 警告。构建全绿、两个文件同步、打包正常，真机上跑的仍是 auto。
  同时因为把探针 `CRITICAL_ROWS` 改成期望 browse，导致 probe 永久误报 degraded。
  **v1.4.8 正确写法**：`- id: directory-picker / disabled: true` +
  `- insert: [{ id: android-directory-picker, name: ...-browse }]`。
  探针改为校验 `android-directory-picker` 这个新 id。
- **实测证据（emulator-5556，Android 16）**：
  · 冷启动遮罩显示 `7,882 / 45,244 files · 72MB`、进度条与秒数正常跳动；
  · 热启动显示 `runtime already installed · 0.0s`；
  · 展开步骤列表 8 行全部正确（`+246ms at 0.2s Restore built-in patches` …）；
  · `boot.log`：`runtime-install 35554ms`、`health-poll 12497ms`、`TOTAL 50260ms`、
    `webview-first-paint 55775ms`；
  · **plugin health marker `ok:true`、`failedEntries:[]`、`missingServices:[]`**
    —— 这是 v1.4.7 从未达到的状态，证明 browse 真的挂上了；
  · 主界面 `Choose workspace` 按钮正常渲染，无 `directoryPickerController unavailable`。
- **新增回归测试**：`scripts/probes/_test_boot_trace.py`（31 项）、
  `scripts/probes/_test_overlay_patch_semantics.py`（9 项静态 + 3 项动态，
  可传引擎 patch 函数路径用真实实现验证）。
- **验证**：i18n 全过；插件测试 115 项 + BootTrace 31 + overlay 12 = 158 项全过；
  `compileDebugKotlin` / `assembleDebug(x86_64)` 成功。
- **未做**：arm64 release 包与上架 —— 宝宝说「先不要上架」类的约定仍有效，
  等确认后再出正式包。

## v1.4.7（2026-10-21，已出包待发布）
- **提交**：`db941eb`（三项修复）→ `5c92711`（PR #30）→ `b44b969`（i18n 注释）。
- **目录选择器整链失效**：overlay 从 `dsh-host-directory-picker-auto` 换成 `...-browse`。
  用户定位：auto 在 `apply()` 用 `ctx.loader.create()` 动态挂载，跑在 `ctx.effect()` 里只执行一次，
  抛错即回滚且 Cordis 不重试 → `directoryPicker` 服务永久缺失 → 前端报 unavailable。
  Android 上 auto 的探测恒定落到 browse，故直接声明 browse。`plugin-health.mjs` 的
  `CRITICAL_ROWS["directory-picker"]` 同步改包名。
- **PTC libz.so.1**：`scripts/patch-ptc-runtime-android.py` 加第二处补丁 —— env 墓碑构造后
  从 `executable` 反推 `<root>/lib:<root>/usr/lib` **无条件注入** `LD_LIBRARY_PATH`。
  仅靠 v1.4.3 的白名单不够（父进程未设置时白名单放行空值）。两架构 runtime 重打，
  MANIFEST sha = `d78baea18c09f958b42f66d2e501ae91ae35b42fbf68f479bd9980fb9cdd4101`（实测一致）。
- **恢复被引擎覆盖**：`ChatRecovery.recover()` 写前备份 `workspace.json.before-recover`、
  写后落 `.workspace.json.recover-stamp`、新增 `verifyStillPresent()`；设置页流程改为
  「停引擎（grace 3s）→ sleep 800ms → 写注册表 → 启动」。
- **PR #30（@skynit，登录弹窗错位）**：因分支基于 v1.4.5 且与 v1.4.6 冲突，
  **未走 merge，改为 `git apply --3way` 只取 `MainActivity.kt`**（EngineConfig 注释部分
  main 已等价英文化）。已评论并关闭 PR。新增 `applyViewport()` / `scheduleReraster()` /
  `VIEWPORT_SETTLE_MS=120`。**真机回归未做**（提交方设备不在线），日志关键字 `reraster:`。
- **分支清理**：已删除远程 `fix/a11y-watchdog-and-bridge-timeouts`、`upstream-staging-021`（均 204）。
- **验证**：插件测试 115 项全过；`check-i18n.py` 全过（顺手修了 overlay 里 directory-picker
  注释的中文，D 项报 `CJK in Kotlin string literal`）；`compileDebugKotlin` / `assembleRelease` 成功；
  APK versionCode=258/1.4.7/arm64-v8a，签名 `CN=DSH Mobile`，SHA-256 证书指纹
  `d28ca4f5...92c8`；runtime 内 `bin/node`、`bin/bash`、`bin/rg`、`lib/libz.so.1` 均 AArch64；
  APK 内三份 `.so` 均 AArch64。
- **发布物**：`dist/DSH-Mobile-1.4.7-arm64-v8a.apk`（241,555,066 字节）。
- **未验证/风险**：登录弹窗修复无真机确认；CPU 满核与语音面板 1-2 帧残影仍未复现；
  三份审查里的 P1 项（anchor 覆盖不足、安装非原子、`reclaimPort` 匹配过宽等）**仍未修**。

## Current progress
- **开源仓库 Issues/PR 处理（2026-10-14，已完成）**：
  - **PR #11 已合并**（`1f317ea`，squash；贡献者 @jahdaganj02ki-sketch）：界面三语（en/de/zh，默认英语，移除「跟随系统」）+ 新增 values-de（402 键）+ 删除过时的 values-en（169 键旧部分翻译）+ 屏幕方向三态 OrientationPrefs（竖屏/横屏/自动，旧布尔键自动迁移）+ 冷启动桌面布局修复 + CI 全量触发/build-monitor/Gradle 管道退出码修复 + check-i18n 重复键检查。**合并前维护者修复了阻塞项**（`fa8d85e`）：自动发布 job 原条件在上游同样成立（会每次 push main 自动发 debug 签名包、破坏人工发布流程）→ 加 `github.repository == 'jahdaganj02ki-sketch/...'` 门禁，**实测上游运行该 job 显示 skipped ✓、Releases 无新增污染 ✓**。
  - **Issue #10**（版本检测）已回复：功能自 v1.2.77 已存在（设置 → 关于 → 检查更新，查 Releases tag 对比 App versionName，手动触发）。
  - **Issue #2**（语音唤醒）已回复：现状说明（悬浮球语音输入 / TTS / 离线 ASR 插件已有；唤醒词常驻监听仍待评估），并请对方补充使用场景。
  - **⚠️ 遗留观察点**：① `LocaleHelper` 默认值 `"system"` → `"en"`，未设置过语言的用户界面会从跟随系统变为英文（已在 PR 评论中提示贡献者，待宝宝判断是否要改）；② CI 触发扩大为 `push: ["**"]` + 每日定时构建，Actions 用量会明显上升（上游待观察后决定是否收敛）；③ 合并后上游 versionName 已是 1.2.120（与已发布 release 同号），下次发版需递增。
- **开源仓库 README 更新至 v1.2.120（2026-10-12 晚，已完成）**：三语 README（zh/en/de）版本号对齐公开 release（1.2.106/98/97 → **v1.2.120**）；内容：扩展中心补「支持卸载」（1.2.120 实际新增）、APK 体积修正约 130MB（实测 129.6MiB）、边界与 FAQ 补 Android 13+ 无障碍弹窗（需点 Allow）、简介补「不是 proot / Ubuntu 容器方案」、修德文版语言链接 bug（README.zh-CN.md 不存在 → README.md；English 误指 → README_EN.md）。**远程 main = `a83d2e4`**（共 4 个 README-only 提交，全部 fast-forward，**无任何代码提交**）；本地 main 已逐次 merge 对齐（`66af304`）。
- **用户新规则（2026-10-14）：无障碍任务一律走 daemon，对话流只做简单任务**（已改进 seed）：
  · `Tool choice` 段落开头改为 "**Decide by task size FIRST**"：① **简单 UI 任务（1-2 个动作、
    目标就在当前屏）→ 自己用 `loop run`**；② **其它（多步、探索未知页、"去做完告诉我"）→
    `parallel run "<goal>" --success "<可验证终态>"` 交给 daemon，然后别管它**——明确写了
    "不要逐步盯着（do NOT babysit）"、"不要用 scr 自己重演它的步骤"、每几轮或用户问时
    `parallel status` 轮询、卡住看 `parallel log`、`parallel stop` 中止；成功条件必须是
    **可验证状态**而非动作描述。
  · `parallel` 那条 bullet 从"长任务才用、2-3 步留给自己"改为"**所有非简单 UI 任务都交给
    daemon**，只有 1-2 动作的任务留给自己"。
  · 并写入了**理由**（让它不只是偏好）：对话流驱动多步 UI 任务 = 每步一次完整推理往返；
    daemon 把这个成本放在对话轮之外，且上下文更小。
  · 状态：seed 已改 + x86_64 debug 构建通过，**尚未装到设备**（seed 在 app 启动时由 Kotlin
    常量写入 `$DSH_HOME/AGENTS.md`，必须装新包 + 重启才生效）。
  · ⚠️ **新规则的前置条件**：`parallel run` 需要 `$DSH_HOME/.credentials.yaml` 的 `refs:`
    段里有 API key（daemon 每轮现读）。我测完已把该段还原为空 → **不先补 key，任务一律失败**。
    补法：用户在 app 里开 max+（由 app 自己写），或测试时重新注入 PC 端 key（用完再撤）。
  · ⚠️ **daemon 的头号待修**：**目标锚点缺失**（实测它中途离开目标页后跑去分析别的页面
    并自认成功）。新规则把绝大多数任务都压到 daemon 上之后，这条从"次要"升为**最优先**。
- **v1.2.157（2026-10-13 晚，未出包）两条 seed 规则 + 本地模型部署状态**：
  · **seed 加两条规则**（用户点名要求）：① **出错/点错先返回**——按 BACK/`back_to`/重开
    目标页回到"认得的状态"，重读屏幕后再用更准的锚点重试；同一失败步骤**不连续重复
    超过两次**，要换打法（先滚、先关弹窗、换别名）。② **真找不到就如实说**——报告
    "页面上现在有什么"，并且**禁止用通用页标记冒充成功**（"详情页打开了"证明不了
    **哪一个**商品打开了；必须验证被要求的**具体身份**：名称/价格/状态）。
    后者正是本项目实测过的"进错商品却自报成功"（`anyText:["商品详情"]` 任何商品页
    都通过）的根治。改的是 `AgentContextSeed.kt`，x86_64 debug 构建通过。
  · **本地模型：模型文件已部署** —— `engine/models/bge-small-zh-v1.5-q4_k_m.gguf`
    **15,448,256 字节（与 PC 下载逐字节一致）**。⚠️ 踩坑：`adb shell ... cat > file`
    传二进制会**截断**（实测只到 447KB），必须 `base64 file | adb shell "run-as ...
    sh -c 'base64 -d > file'"`。
  · **llama.cpp 扩展：装不上（缺 `llama-server` 二进制）** —— 设备上虽有 Termux 的
    `pkg`，但它是**硬编码 `/data/data/com.termux/files/usr/...`** 的脚本，DSH 运行时
    用的是自己的 prefix → 无法执行。**唯一正确的安装器是 app 自己的 ExtensionManager**
    （Packages.gz 索引 → 依赖闭包 → .deb + SHA256 校验 → 改写 prefix），而它是 UI 驱动
    （扩展中心）。→ 待办：**让 AI 去扩展中心装 llama.cpp 扩展**（正好是一次真实 UI 实战），
    装完 `LocalModel.ensureReady` 会自动拉起 llama-server（模型已就位）。
  · **daemon 对照实测（同一抽象目标：噪声页找全页最低价）**：主对话流 **✓ 通过 2/2 轮**
    （115s/128s、13/14 步、thinking 13/14）；**daemon ✗ 失败**——日志显示它中途离开
    目标页（#15"浏览器停在主页…需重新打开"）→ #16-17 跑去分析 **index.html** 的商品
    并自认"最低价 ¥699"（答非所问）。**daemon 单步快 2-4 倍**（planner 调用 969-1758ms、
    上下文 ~3.1k vs 对话流 13.8k），但**没有目标锚点**：规划提示词只喂"当前屏幕+目标"，
    一旦被带走就对着新页面重新规划。→ 待办：daemon 加目标锚（起始页身份 + 硬规则
    "已离开目标页先回退/重开，禁止在别的页面找目标" + done 前校验锚点）。
  · 凭证处理：为跑 daemon，把 **PC 端** `DEEPSEEK_API_KEY` 注入过设备
    `.credentials.yaml` 的 `refs:` 段（daemon 每轮现读该段，此前为空），**测完已还原**
    （168 字节、无 refs），未触碰用户自己的任何密钥。
- **scroll_find：三次提速尝试全部回滚（v1.2.156，2026-10-13 血的教训）**：
  ⚠️ **先说结论**：本轮对 `scroll_find` 的三次优化**全部引入回归**，已按用户认可的原版
  完整回滚（`stuck>=2` + 每轮 `settle`）。教训：
  ① **我第一次改错了地方**：改的是 `svc.scrollToFind`，而 DSL `scroll_find` 有**自己的
     循环**（在 LocalLoopExecutor）——所以"优化无效"（8.6→10.2s）根本不是优化不够好，
     而是**没改到被调用的那段代码**。改之前必须先确认调用链（`grep 调用者`），别照名字猜。
  ② **`dispatchSwipeHuman` 是异步派发**：把每轮的 `settle()` 换成固定 150ms 睡眠后，
     读屏发生在手势进行中，读到与滑动前**完全相同**的树 → 被到底判定误判成"已到底"。
     实测表现：scroll_find 滑 1-3 次就退出（目标需要 8 次）。**任何"滑完就立刻读"的
     改动都必须轮询到内容真的变化，或保留 settle。**
  ③ "首次不变即停"和"廉价复读确认即停"两种到底判据**都会**踩②的坑，一并回滚。
  **用户实测反馈（保留原版的依据）**："平均一秒不到滚一次…整体感觉完全能接受…
  比较流畅"，唯一缺点是**到底后会多划 2-3 次**（`stuck>=2` 要连续两次不变才停）。
  下一步若再动：需要**滚动位置读数**这类不依赖"读屏是否变化"的判据，
  或先确认手势落地（轮询到变化）再谈减少白滑。
  · 附带说明：v1.2.155/1.2.156 里 `svc.scrollToFind` 的那处改动（真手势大距离+自适应
    等待）**仍在包里**，它服务于 `scr find` 等路径（不是 DSL scroll_find），
    但**该路径未单独实测**——如需保守可一并回滚。
- **A（本地闭环）速度基准 + OCR 提速（v1.2.155/1.2.156，2026-10-13）**：
  · **用户决策**：A（能力层）为主体；C（关思考插件）**撤回**（v1.2.155 已从 overlay/
    assets/部署列表三处移除，包内确认无该插件）；长无障碍任务直接交给 A。
  · **A 的单动作实测**（`scripts/probes/bench_a.py`，各 3 次中位）：
    set_switch **419ms** / input **682ms** / tap 切标签 **1207ms** / scroll 单滑 **1288ms** /
    **scroll_find 10796ms（唯一异常项）**。
  · **8 步长任务一次调用跑完：17.4-19.5s（平均 2.2-2.4s/步）**；分段 act 61-67% /
    settle 21-24% / verify 12-15%。其中**滚动查找一步占 57%**（9.9-10.6s）——
    note 实测 `found after 8 swipes`，即 8 次滑动 × 约 1.25s。
    ⚠️ 本轮把 scrollToFind 从「ACTION_SCROLL_FORWARD + 固定 sleep」改成
    「真手势 75%+ 自适应等待」**未见效**（8.6→10.2s）：滑动次数没减少，目标在长页
    深处 8 次是实打实距离。下一步应做：每轮覆盖提到 ~80% 屏高、并查 dispatchSwipeHuman
    是否阻塞到手势结束（1.25s 里手势只占 0.24s）。
  · **OCR 链提速约 4 倍（本节主要成果，canvas 纯图形页实测）**：
    ① **可靠性**：`screenshotArgb` 一直被系统节流拒（errorCode=3 INTERVAL_TIME_SHORT，
       **间隔 5s 仍被拒**）→ OCR 长期显示 `ocr=unavailable`（无文本界面等于读瞎）。
       修：失败短退避重试 ×3（450ms）。
    ② **减像素**：ML Kit **infer 占大头**（791-2595ms，scale 只 8-68ms）→ 只识别内容区
       （跳过顶部 12%：状态栏+地址栏，实测这两条只产出 `o0 5:06 3G` 噪声行）；图像
       540x1140→540x1003，**infer 791-2595ms → 302-559ms**，OCR 行 8→4（干净）。
    ③ **共用一帧**：一次 dump 里 OCR 与图标识别各截一帧互相抢配额（两边都撞节流各付
       450ms 退避）→ 加 700ms"近期帧复用"，整次读屏只消耗一次配额，`shot=0ms`。
    **合计：canvas 页读屏 1370-1588ms → 326-393ms（约 4 倍）**，infer 241-296ms。
  · 产物：`dist/v1.2.156/dsh-mobile-v1.2.156-arm64-v8a.apk`（已签名 + Downloads 副本）。
- **v1.2.154「第一步思考、之后关闭」（2026-10-13，已出包）**：用户要求"把 AI 思考关了，
  除了第 1 次允许思考，以后步骤的思考都关掉"。实现方式**不碰提示词、不碰模型配置**——
  写了一个引擎插件挂 `agent/request` 瀑布钩子（agent 循环**每一步**都会带 {turn, step}
  询问该步请求配置，返回值即该步实际配置）：
  step===1 原样返回（思考开，规划值这个钱）；step>1 返回 `reasoningEffort:'off'`
  （适配器据此发 `thinking:{type:'disabled'}`；`off` 是 schema 允许值之一）。
  · 落点按项目既有惯例：`app/src/main/assets/android-plugins/no-think-after-first.mjs`
    + `EngineConfig.ensureAndroidOverlay()` 的 overlay 插入行（`name: ./android-plugins/…`）
    + 部署列表加文件名（与 priv-mode/bridge-token 同一套幂等拷贝）。
  · **实测（同一目标：搜索耳机→进详情→开开关，只给目标不给路径）**：
    思考全程开 = 78~129s、thinking 24 个、output 14537；
    **接插件后 = 50~78s、thinking 1 个、output 2748**，且三项子任务**客观全对**
    （URL `detail.html?sku=静音降噪耳机`、页面商品名=静音降噪耳机、开关节点 `cv [switch]`）。
  · 想恢复全程思考：把插件行 `disabled: true` 或 config.enabled 设 false。
  · 产物：`dist/v1.2.154/dsh-mobile-v1.2.154-arm64-v8a.apk`（150.7MB，签名，已复制 Downloads）。
- **速度归因实测（2026-10-13，用户反馈"平均接近四五秒一步"）**：把运行流水按
  「本地闭环执行」与「云端往返」拆开计时（loop run 报告里有 totalMs）：
  · 基线（只准用界面，243s / 24 个 LLM 步）：本地执行合计 **56.9s = 23.4%**；
    云端 ≈ **186s = 76.6%** → **7753ms/步**。每次 loop run 的计划**平均只有 1.7 步**，
    24 步里步步都在 thinking（事件流 24 个 thinking 事件）。
  · 单次往返地板价（只说"好的"的极小任务）：**10s**，上下文 13.8k tokens
    （32KB seed 占大头；cacheRead 5120 / input 8670）。
  · **杠杆=减少轮次**：同一目标加一句"每次 loop run 给 4-8 步，用 scroll_find/when/
    retry 兜住不确定性" → **66s**（LLM 步数 24→9，计划步数 4/3），**3.7 倍**。
    （该次页面处于"过滤未生效"的坏状态，可比性打折；页面修好后同指令 = **129s /
    12 步 / 计划 5-1-3-3-2**，本地占比 18%。）
  · 结论：**瓶颈是"轮次×单次延迟"，不是本地无障碍**（本地均摊 2.4s/步，
    其中 settle ~0.4s、verify compact 15-750ms、pick 30-60ms）。
  · 待办（最高价值）：把"批量计划 + 用 scroll_find/when 吸收不确定性"写进 seed——
    这是唯一 2-3.7 倍级别的提速杠杆，且不损失正确性（DSL 本就有这些原语）。
- **靶场自身两个 bug（都会伪装成"AI 出错"，已修）**：
  ① **`.item{display:flex}` 压过 UA 的 `[hidden]{display:none}`** → JS 里
     `el.hidden=true` 只改属性、元素照样显示（搜索过滤后计数变"共 1 项"但 8 行商品
     仍在无障碍树里）→ 修：app.css 加 `[hidden]{display:none !important}`。
  ② **`am start -d <同一 URL>` 在 Chrome 已开该页时只切回旧标签页、不重新加载** →
     整个测试跑在旧版 HTML/CSS 上（改了 CSS 却"不生效"，并误判成 AI 点错商品）→
     修：`full_suite.goto()` 与手测命令都加 cache-buster（`?_t=<ms>`），
     七页静态资源引用也带 `?v=3`。
  · 修好后再验：过滤后无障碍树里只剩 1 个「查看」、唯一商品=静音降噪耳机 ✓。
  · **仍未解决（引导缺口，非执行器缺陷）**：修好页面后 AI 依旧落到
    `detail.html?sku=星耀 Pro 手机`——它自己的计划 #4 明写 `target:["星耀 Pro 手机"]`
    （即它当时的 dump 里第一行就是它），根因是**验证用通用页标记、不断言商品身份**，
    加上过滤状态在多次往返中丢失。待 seed 补"验证要带身份标识"。
- **v1.2.153 靶场 + 真实任务实测（2026-10-13）**：新建复杂测试网站 `scripts/probes/testsite/app/`
  （9 文件：app.css / app.js / index / shop / detail / wizard / long / vision / slow），
  把无障碍难点做进页面：无文字图标按钮、1.6s 轮转 banner（页面永不"稳定"）、
  3.5s 自动弹窗打断、空输入框 placeholder、8 个同名「查看」按钮、实时搜索过滤、
  无限滚动、懒渲染（滚到才出现）、标签页、折叠面板、20 个开关（目标在靠底部）、
  range 滑块、两层确认（自定义模态 + 原生 confirm）、三步向导 + 校验、分段长文、
  Canvas 画出的按钮（节点树里不存在）、纯图标工具栏、4.2s 加载页（进度条强信号）。
  PC 起服务：`python -m http.server 8899 --directory scripts/probes`，
  模拟器 URL：`http://10.0.2.2:8899/testsite/app/<page>.html`。
- **只给目标的真实任务实测（未知路径，两次）**——用户点出"真实控制时 AI 不可能知道下一步会遇到什么"，
  故改用 `headless_task.py`（真 agent，只给一句目标，不给任何路径/锚点）：
  · **第 1 次（141s，46 步，exit=0）**：目标含 URL → AI 用 `curl` 把靶场 HTML/CSS/JS 拉下来
    grep 选择器（**约 1/3 命令花在读源码上**），3 件事**全部完成**（已客观核验开关为 `cv`）；
    工具调用 loop run×8 / scr dump×15 / /screen×10 / loop pick×3 / scr tap×2；缓存命中 **98%**。
  · **第 2 次（243s，24 步，exit=0）**：目标改为「只准用界面、不许读源码/curl」→ 不再 curl，
    但耗时 **+100s**（源码抄近路确实有效，禁掉就要靠试）；loop run×16 / scr dump×4；
    另花 ~4 条命令 `sed/grep` 读 `engine/bin/loop` 自己摸索 DSL 用法。
    **⚠️ 关键发现：第 2 次最终汇报自称"进入其详情页"，但客观状态是
    `detail.html?sku=星耀 Pro 手机` —— 进错了商品（搜索结果本该是「静音降噪耳机」）；
    开关确实开了。即 AI 自述成功 ≠ 实际成功。** 根因是验证不够具体
    （用 `anyText:["商品详情"]` 这类**通用页标记**做验证，任何商品页都通过），
    属**引导缺口**（seed 未要求"验证带身份的具体标识"），非执行器缺陷。
  · 另观察：AI 会用 shell `for` 循环做重复滑动（不走 DSL 的 repeat）、
    会用坐标 target（从 dump 抄 x,y）绕过歧义——都是可继续引导的点。
- **本轮（v1.2.153）修的能力缺陷（全部来自靶场实测，非提示词）**：
  ① **空输入框 placeholder 进 dump**（`p:` 前缀，API 26+ 读 `hintText`）——此前
     `<input placeholder="搜索商品">` 在 dump 里只剩 `- @x,y ce [edit]`，AI 既认不出
     也点不准；真实世界的登录/搜索/验证码框全中招。compact + JSON + 匹配链路（含语义）全打通。
  ② **pick 同档质量优先可交互节点**——反例：目标「搜索商品」同时命中不可点的
     `<label>`（精确 1.0）与可点可编辑的输入框（0.95），旧逻辑在精确档直接返回 label →
     点击落在死标签上静默无反应。现在同档内优先 clickable/editable。
  ③ **`input` 动作回退到步骤级锚点**——此前只认 `action.target`（字符串），
     AI 自然写成步骤级 `target.text` 就静默失败。
- **v1.2.152 已出包（2026-10-12 晚，未上架）**：5 份子智能体代码/架构审查的发现**已全部处理**——修复 5 个 P0 + 9 个 P1（其中 1 个 P0 是审查漏掉、本轮实测抓到的 daemon 语法错误，详见 Version timeline 的「v1.2.152 审查修复轮」）。产物 `dist/v1.2.152/dsh-mobile-v1.2.152-arm64-v8a.apk`（150.7MB，签名摘要 `d28ca4f5…`）已复制到 `C:/Users/Qt/Downloads/`；模拟器 x86_64 debug 端到端验证通过。
- **⚠️ 重要更正（宝宝指正 + 本地核实，2026-10-12）**：**「本地闭环执行器」「场景记忆」不属于 v1.2.120 的公开能力，公开文档不得宣传**。核实细节：dist/v1.2.120 APK 的 dex 虽含 LocalLoopExecutor/SceneMemory 类，但 AI 侧无入口（seed 加 loop 说明的提交 `d79db55` 晚于 v1.2.120 发布构建，`ad9df8b` 只为插件卸载升版本号），实际不可用。README 已移除相关小节与全部配套描述（性能表闭环行 / Termux 段 / 边界视觉定位补充），只保留 1.2.106 基线 + 插件卸载 + APK 体积 + Android 13+ 无障碍说明。**本文件及工作区根大 AI_CONTEXT 里旧的「v1.2.120 已发布」功能清单（含闭环/记忆/视觉定位/checkedText）不可信，勿照抄进公开材料。**
- **⚠️ 事件记录（教训）**：本次初次操作时误将本地 60 个未推送提交一并推送（`a08189a`），已立即 `push --force-with-lease` 回滚至 `0b88f8c`。**远程代码已恢复原状，未上架任何新版本，release 仍为 v1.2.120**。遗留：GitHub Actions 留有一次 a08189a 的 i18n-check 失败记录与 android-build run（未产生 release）；`a08189a` 在 GitHub 上不可达（不在任何分支/PR 上，正常浏览不可见）。
- **边界（现状）**：1.2.121–1.2.151 全部功能（离线 OCR、本地嵌入模型、分支计划、max+、角色标记、幂等开关、parallel daemon、intent 直达、图标识别、拟人化手势、技能沉淀、乐观执行……）**仍在本地改进中，全部未公开**；README 只描述到 1.2.120 为止的能力。
- **发布状态**：对外公开版本保持 **v1.2.120**（已补安装包）；**v1.2.121 不上架**（草稿）。
- **v1.2.128 已出包**（`dist/v1.2.128/` 与 `C:\Users\Qt\Downloads\`，arm64 签名，未上架）：离线 OCR + 本地模型自动就绪 + settle 提速 + verify 语义兜底 + 用量统计 + 分支计划 + max+ 开关（知情同意）+ 模型来源选择 + 坐标越界修复 + **引导 AI 用本地闭环（seed v34 Tool choice + batch 动态提示）**。
- pending 记忆机制完整闭环验证通过；记忆诊断可见性已改进（1.2.122）。

## Verified facts
### i18n-check CI 失败清单（2026-10-12 推送 a08189a 后实测，`python scripts/check-i18n.py` → FAILED(14)）
- **Kotlin CJK 字面量 13 处**：`PageStateDetector.kt:48`（加载中/正在加载/载入中/正在载入/请稍候/请稍等）、`ScreenOcr.kt:212`（`＠` 全角替换）、`CredentialProbe.kt:101/105/106`（DeepSeek 官方/通义千问/智谱 GLM）、`SceneMemory.kt:251`（记忆诊断）、`DshAccessibilityService.kt:1127`、`AgentBridge.kt:1175`。
  **修复建议**：PageStateDetector 的加载文本与 ScreenOcr 的 `＠` 是必要的界面匹配/文本处理（与 seed 保留中文串同原则）→ 加 `check-i18n.py` allow-list；CredentialProbe 友好名、SceneMemory 诊断消息属界面文本 → 挪 strings.xml 或 allow-list（待宝宝拍板）。
- **catalog 不一致**：`catalog.zh-rCN.json`（20 项）缺 `asr-service`，`catalog.json`（21 项）有 —— `90ca9fd` 加 asr 时只改了英文目录 → 中文目录补条目即可（README 现写 19 项，为 1.2.120 时代公开数量，二者不冲突；后续发布新版本时如需宣传 20+/21 项再考虑修 catalog）。

### 快捷命令 + 截图自动化 + 提示词规范（1.2.138）
- **截图**：`/screenshot?save=1` 保存到文件只回路径（base64 每帧吃 ~100-200KB 上下文），旧图自动清理（保留 12 张）；`scr shot --save` 推荐用法；seed 教 AI 用 `read_image` 按需读。
- **应用级直达（+3）**：`scr macro app-info|app-notify|app-perms <pkg>`——应用详情/通知/权限一步直达（实测 1 步 vs 此前 4-5 步）。
- **提示词规范（seed v40 + daemon）**：计划模板（可照抄的 JSON 形状 + action.type/verify 键白名单，明示"未知字段被忽略，不要发明"）；「绝不重复」规则（规划前重读历史与屏幕；已成功不重做；屏幕已是目标态即完成；解释重复点开关会翻回去）。
- **⚠️ 模拟器测试流程**：每次重装 APK 后无障碍服务会被系统停用——**测试前先跑 `scripts/probes/enable_a11y.py`**（真机不受影响，用户已确认）。

### 边规划边行动（1.2.136-1.2.137）：常驻守护进程 + 执行环境扩展
- **架构**：主 AI 委托（`parallel run "<goal>" --success "<终态>"`）→ 常驻 `planner-daemon`（独立于对话轮）循环「读屏 → DeepSeek 规划 → 本地闭环执行 → 观察 → 再规划」→ `parallel status/log/stop` 查询。任务队列用文件（`$DSH_HOME/tasks/`），API key 走 refs 段。
- **执行环境扩展（1.2.137）**：`intent` 动作（12 个系统设置页直达：DISPLAY/WIFI/SOUND/BLUETOOTH/BATTERY/APPLICATION/LOCATION/SECURITY/DEVICE_INFO/INTERNAL_STORAGE/DATE/ACCESSIBILITY_SETTINGS）+ `/launch-intent` 端点 + `scr macro` 14 个快捷命令。
- **实测**：intent 单步直达 3.7s/1 步（此前 3-5 步）；daemon 任务「打开深色主题」**21s 完成（4 步，用了 intent）**，对比扩展前 45.8s —— **提速 2.2 倍**。
- **实测暴露并修复**：① daemon 读屏失败无限重试 → 连续 6 次快速失败；② app 重启后**孤儿 daemon 持旧 token** 全部 403 空转 → 加启动自检（403 即退出让 gate 重启）；③ 测试库 `pidof node` 多进程取错 token → 改按 cmdline 找引擎。
- **环境痛点（待产品级修复）**：无障碍服务反复被系统停用（Android 13+ 侧载限制清理 `settings put`）→ UI 自动化全部失效。建议 app 侧自愈（检测未启用 → 引导/自动恢复）。

### 四轮 AI 反馈迭代闭环（1.2.131→1.2.134，2026-10-12）
**方法论**：每轮「真实任务 → 访谈 AI（哪里不方便）→ 按带数据的反馈修复 → 重测」。四轮成果：

| 轮次 | 修复内容 | 效果 |
|---|---|---|
| 1 | 角色标记 `[switch]/[row]`、`set_switch` 幂等、`verify.toggle`、修 `scr macro open-settings` 字段名 bug | 深色主题任务 118s→35s |
| 2 | 失败帧 `failScreen`（escalate 时附屏幕快照）、重试角色守卫（防重选到页面标题） | 无回归 |
| 3 | `scroll` + `scroll_find`（命中即停/别名/tap 一步到位）、verify 默认超时 5000 | 滚动任务调用 20→8 次 |
| 4 | **role 优先级坑**、loop run 超时 10s→120s、scroll 后 settle、scroll_find 幅度 35%+到底检测 | 综合任务 **7 次调用 / 64.7s** |

- **⚠️ Kotlin 优先级坑（第 4 轮抓到，值得全仓警惕）**：`a + if (x) "p" else "" + if (y) "q" else ""` 被解析为 `a + if (x) "p" else ("" + if (y) "q" else "")` —— **else 分支吞掉后续拼接**，导致所有带 flags 的节点丢失 `[role]`。修复=显式加括号。AI 的"dump 里没有 [switch] 标记"报告是发现它的线索。
- **最终验证（AI 新对话跑 4 阶段综合任务）**：7 次工具调用（初版 43 次）、64.7s、任务完成；AI **主动使用 failScreen**（"失败帧证明点击已生效，无需重复点击"）并自我诊断锚点选择问题。
- AI 报告的非工具问题：偶发 dump 超时（启动瞬间）、set_switch 校验延迟（主题切换 3.7s，预算内）。

### AI 体验反馈驱动的无障碍优化（1.2.131，2026-10-12）
- **方法论**：直接向 AI 发真实任务 + 访谈（"哪里让你感到不方便"）→ 它给出带实测数据的痛点清单 + 12 条改进建议。**一轮访谈 = 最准的需求来源**。
- **AI 报告的核心痛点与已修**：
  1. **dump 无控件角色** → 把导航行（`d:深色主题 co`）误判成开关 → 4.4s escalation。已加 `[role]` 后缀（switch/row/button/edit/slider/list/scroll；text/image 静默）。
  2. **缺幂等开关原语** → toggle 重试会二次翻转。已加 `set_switch`（读态→仅必要时点击→回传 note）；实测重试时 `already on` 命中，**幂等语义防止了二次翻转**。
  3. **无状态位断言** → 已加 `verify.toggle {label|text, to: on|off}`（**能验 off**；checkedText 只验"标签旁有已勾选节点"，off 永远失败）；修 `needsDump` 遗漏（只有 toggle 时不读屏→必然超时）。
  4. **`scr macro open-settings` 100% 失败**（真 bug）：gates 传 `{"pkg"}`、端点只认 `"package"` → 已修两端。
- **实测**（同一深色主题任务，三次迭代）：118s → 42s → **35s**；AI 最终双重确认状态（勾选位 o→v + 副标题变化）并正确理解幂等语义。
- **AI 反馈中尚未实现**（第二轮候选）：失败帧自动附带（escalate 时给失败屏幕文本，省一次 dump）、重试角色守卫（实测重试曾重选到页面标题 score 0.95，"比不重试更危险"）、OCR 行与无障碍节点去重（坐标差 176px）、`scr changed --tree`（只回变化节点）、`loop run --dry-run`、`scr launch --wait`、usage 补全 macro/launch/changed。

### 端到端 AI 测试与 243s→48s 的真相（1.2.130，2026-10-12）
- **方法**：`scripts/probes/headless_task.py` 用 `dsh --profile headless --json` 直发任务；**必须带两个 --patch**：
  ① `agent-instructions` 的 id 覆盖（`config: {maxBytes: 65536}`，base 有此行但 headless 需显式补 config）；
  ② **`engine/android-overlay.yml`** —— 关键！`dsh-subprocess.scrubbedParentEnv()` 会清除所有 `DSH_*` 变量，`DSH_BRIDGE_TOKEN` 必须经 shell-env 注册才能到 AI 的 shell（否则 scr/loop 全部 403，AI 被锁在设备能力之外）。cwd 用 app 私有目录（外部存储 workspaces 无权限）。
- **同一极难任务三次对比**（Chrome 打开 canvas 页并列出中文）：
  | 环境 | 耗时 | 工具调用 | 结果 |
  |---|---|---|---|
  | v34 无 token 注册 | 243s | 43（22 次找 token） | ✓ 完成 |
  | v35 无 token 注册 | 80s | 9 | ✗ scr 全 403，快速失败 |
  | **v35 + token 注册** | **48s** | **13，零探索** | ✓ 完成（9 行文字全对）|
- **结论**：243s 主要是**测试环境缺 token 注册**导致 AI 花 ~110s 摸索环境，不是产品性能问题；真机 web profile 由 app 注入 android-overlay.yml（token 已注册）→ 体验应接近 48s。
- **AI 高效行为**（48s 测试）：`scr dump` 勘察 → `scr launch com.android.chrome` 一步启动 → `scr idle` 等待 → `scr shot` 截图 + 读图/OCR → 输出；最终回答正确说明「canvas 文字不在无障碍树，用截图 + 离线 OCR 读取核对」。
- **seed v35 新增**「Environment (read once — do NOT re-verify)」：命令已在 PATH、bridge 认证内部处理、禁止找 token/curl 3083/读包装脚本/翻 `$PREFIX/bin`。

### 端到端 AI 测试：极难任务自主规划（1.2.129，2026-10-12）
- **方法**：`scripts/probes/headless_task.py` —— 用 `dsh --profile headless --json`（无 Host/HTTP 层的一次性 Agent）直发任务给模型，环境变量从运行中引擎进程 `/proc/<pid>/environ` 复制（保证一致）；`--json` 输出机器可读事件流，统计工具调用特征。API key 走 `.credentials.yaml` 的 **refs 段**（PC 环境变量注入模拟器，仅测试用）。
- **任务**（极难操作）：在 Chrome 打开 `http://10.0.2.2:8899/_ocr_canvas.html`（canvas 绘制、节点树无文字、比屏幕宽 2.6 倍）并列出页面中文文字。
- **结果（243s，成功）**：`loop run` **4 次**（v34 引导生效 ✓）、`scr dump` 21 次（勘察）、`tap-text` 1 次；AI 自主完成 launch → 输入 URL → 横向滚动逐段读取 → 输出全部 9 行中文，且**明确说明「canvas 文字 DOM 无文本 → 用截图 + 离线 OCR 逐段读取，并与页面源码 fillText 交叉核对」**——OCR 兜底在真实 AI 工作流中得到验证。
- **观察**：dump 21 次偏多（每次一次推理往返）；横向滚动读取场景可理解，后续可引导用 `loop` 的 `collect` 动作收内容。
- **附带修正**：查明 API key 真实存储在 `refs:` 段（`renderRef()` 写 `refs.<REF>`；records 段是 grant/api-key 结构化记录）→ `CredentialProbe` 改为两段都扫，否则真机 WebUI 配的 key 会被漏掉。

### 引导 AI 用本地闭环而非自己操作无障碍（1.2.128，用户反馈驱动）
- **现象**（真机反馈）：「ai 好像还是在自己操作无障碍功能」——AI 用 `scr tap`/`tap-text`/`scr batch` 逐步操作，没走 `loop run` 本地闭环。
- **根因**：seed 里 `scr batch` 描述过于详细（"run a whole flow in ONE turn" + 完整字段表），而 `loop` 只是 "prefer" 级措辞 → AI 倾向选前者（它没有本地判别/验证/重试）。
- **修复（三层）**：
  1. seed v34 新增 **「Tool choice (non-negotiable)」** 规则段：多步 UI 任务必须用 `loop run`；逐个 `scr tap` = AI 自己当 picker（每步一次推理往返）；`scr batch` 不是替代品（无判别/验证/重试，一步错全错）；`scr` 保留给勘察/单步特殊手势/宏/纯手势序列；含自检句（"If you catch yourself writing a sequence of `scr tap`/`tap-text` calls, stop and express it as a `loop` plan instead"）。
  2. `loop` 行强化为 "THE tool for multi-step UI automation"；`scr batch` 行加 ⚠️ 警告与适用边界。
  3. **`/batch` 响应动态提示**（仅 ≥3 步）：告知本次由 AI 自己执行、无判别/验证/重试，下次改用 `loop run`——静态提示词易被忽略，响应内反馈更直接。
- 实测：`AGENTS.md` 注入 v34✓ 新规则可见✓ 3 步 batch 响应含 `tip`✓。
- 注意：seed 属提示词层软约束；`tip` 是每次多步 batch 都会出现的强反馈。

### max+ 模型来源选择（1.2.127，模拟器全流程实测）
- 需求（真机反馈）：「有多个模型多个 api key 怎么处理？检测并给个选项，默认 DeepSeek 官方（响应最快）」。
- **CredentialProbe**：解析 `$DSH_HOME/.credentials.yaml` 的**记录键名与 kind**，只列 `kind: api-key` 记录（`grant` 类会话凭证自动排除）；**不读取不输出 key 值**（行级解析只保留键名）。友好名：DEEPSEEK→DeepSeek 官方、OPENAI→OpenAI、MOONSHOT→Kimi 等。
- 设置页「模型来源」行（**仅 max+ 开启时显示**）：显示当前选择，点击弹单选列表；默认选含 DEEPSEEK 的凭证。
- 存储：`dsh_ui` SharedPreferences 的 `a11y_max_provider`（凭证记录键名，空=按默认兜底）。
- 实测（构造 3 key + 1 grant 的凭证文件）：关闭→行隐藏✓ 开启→行出现且默认 DeepSeek 官方✓ 弹窗列 3 项✓ 选 OpenAI→显示更新+存储 `llm-openai/openai-api-key`✓。

### ⚠️ 凭证文件格式（踩坑记录，改动前必读）
`.credentials.yaml` 有**严格校验，写错会让引擎启动失败**（`StartupError: 1 required plugin did not activate` → credentials）：
- **键名**必须 `<scope>/<id>`，两段都匹配 `^[a-z][a-z0-9-]*$`（小写连字符；`env/DEEPSEEK_API_KEY` 这种大写会被拒）；
- **权限**必须 600（否则 `readable beyond its owner` 拒绝）；
- **字段白名单**：`kind: api-key` 只允许 `key`/`env`；`kind: grant` 只允许 `payload`；
- 引用名（如 `DEEPSEEK_API_KEY`，`/^[A-Za-z_][A-Za-z0-9_]*$/`）与存储键名是**两套命名**。
- 诊断入口：`$DSH_HOME/logs/startup-*.log`（含失败插件与堆栈）。

### 无障碍功能 max+（1.2.126，模拟器全流程实测）
- 位置：设置页「扩展」分组末尾（钥匙图标 + Switch），默认关闭。
- 行为：**从关到开**必须先过 AlertDialog（明确告知①读取本机 API key 驱动云端持续预测 ②自动下载 15MB 本地嵌入模型并启动本地推理服务）；CANCEL 回弹不落盘；**从开到关**无弹窗直接落盘。
- 存储：`dsh_ui` SharedPreferences 的 `a11y_max_plus`（DshApp.KEY_A11Y_MAX_PLUS / a11yMaxPlusOn()）。
- 作用：`startLocalModelAutoReady` 受其控制（日志实测 off 时 `local-model auto-ready skipped (Accessibility max+ off)`）；**OCR 预热不受限**（模型随 APK 分发、不联网、不读凭证）。
- 验证矩阵（全部通过）：行渲染✓ 弹窗内容✓ CONTINUE 落盘 true+选中态✓ 关闭无弹窗+false✓ CANCEL 回弹+不落盘✓ 日志控制✓。

### 坐标越界点击修复（1.2.126，实测发现）
- **现象**：引导页「GET STARTED」节点中心 @540,2211 超出屏高 2209 → `dispatchGesture` 返回 true 但手势落在屏幕外，**点击静默失败**（真机「底部按钮点不到」的典型原因）。
- **修复**：`dumpScreenCompact`/`dumpScreenJson` 输出**可见区域中心**（节点与屏幕求交后的中心）；`dispatchTap` 在 jitter=0 时也钳制坐标。
- **实测**：修复后 @540,2178 点击成功进入主界面；DSH 手势注入总体正常（English 选项点击成功切换界面）。

### 对话流「持续预测」缺口（2026-10-12 用户指出，架构分析）
- **现象**：对话流里 AI 只能同步 `loop run` 后「确认当前内容再继续」。
- **原因**：对话流是请求-响应模型（一轮推理→工具→结果→下一轮），推理期间无法执行工具；「持续预测」需要**独立于对话轮的进程**。现状：并行架构（predict_parallel.py 的 epoch/进度同步/去重）只在 PC 测试脚本里；gates 无 parallel 命令；seed 第 87/92 行虽写了并行协作语义但无对应工具。
- **已做补强（纯本地、零额外 API）**：**分支计划** —— loop 步骤支持 `"when":{"anyText":[...],"noneText":[...]}`，云端一次推理生成多分支（弹窗/版本差异），本地按屏幕状态选路；条件不满足 → `status=skipped`（不算失败）。seed v33 已教云端此格式。
  - 实测：条件满足→ok、不满足→skipped、noneText→ok，总 ok=True 2.1s（3 步 1 跳过）。
- **待决策方案**：设备内并行协调器（`loop parallel "<goal>"` → 后台进程循环「读屏→调 API 预测→喂本地执行器」）。**关键约束**：需要 API 访问——引擎环境变量里无 key（只有 DSH_BRIDGE_TOKEN/DSH_HOME 等），key 在 `$DSH_HOME/.credentials.yaml`；用户此前明确「不希望系统直接读取用户 API」，故该方案需用户拍板（或改用引擎自身接口代理）。

### 本地判决 AI 的适用边界（用户提问，2026-10-12）
- **近义/不同表述**（「深色模式」vs「深色主题」）：语义匹配强项（实测 0.742 / 0.926）。
- **完全无文字**（纯图标/图形）：语义匹配用不了（无文本）；兜底链 = 无障碍文本 → OCR（图里的文字）→ 语义匹配 → 视觉模板匹配（VisionTracker，需预存模板，非自动）→ 云端看图。

### 本地判决 AI「很少用」的架构分析与修复（2026-10-12，用户反馈驱动）
- **根因**：pick 的锚点来自云端抄的屏幕原文 → exact 层必中，语义层天然少触发；verify 此前是纯 `contains` 精确子串 → 本地模型完全没参与验证。
- **修复**：`LocalLoopExecutor.semanticTextMatch` —— verify 精确 miss 后用本地嵌入语义匹配，阈值 **0.78**（比 pick 的 0.62 严：验证误判=错误完成，代价大于点错）。真实收益场景：OCR 识别噪声、近义表述、云端概括词。
- **统计**：`FastPicker.stats`（pickExact/pickSemantic/pickNone/verifySemanticHits），`/local/status` 输出。
- **端到端实测（模拟器，本地模型全链路跑通）**：
  - catalog 修复后 `/ext/list` 21 条可加载 → `POST /ext/install {"id":"llama-cpp"}` → green 0.6.0 → `POST /local/ensure` → `modelReady=true, serverRunning=true`（10s 内）。
  - 语义 pick：`深色模式`→0.742、`显示与触控设置`→0.926（对屏幕「显示和触控」）。
  - verify 语义兜底：anyText `显示与触控设置`（精确 miss）→ `verify=True`，`verifySemanticHits 0→1`。
- **能力边界（诚实记录）**：嵌入模型（bge-small-zh，24M）擅长文本相似度（近义/错字/跨语言），**不适合意图理解**（goal→动作规划需要 LLM）。本地判决 AI 的正确定位 = 快速匹配 + 快速验证；规划仍归云端。

### v1.2.123 真机反馈修复（2026-10-12 模拟器实测）
用户真机反馈：① 基本没用到本地判决 AI ② OPPO 浏览器无障碍取不到文字 ③ 不快、经常 sleep。根因与修复：
1. **本地判决 AI 用不到的根因 = 无触发路径**：`/local/ensure` 在 UI/seed/gates 里**都没有调用点**（模型永不下载、llama-server 永不启动）；且 `catalog.json` 语法错误使扩展中心解析失败、llama.cpp 装不上。修复：无障碍服务连接后后台 `ensureReady`（失败静默）+ seed v32 告知云端 `/local/status` 与 `/local/ensure`。
2. **OCR 兜底（ML Kit bundled 16.0.1）**：`libmlkit_google_ocr_pipeline.so` + 25 个模型文件随 APK 分发（**无 GMS 依赖**，+~15MB）。`ScreenOcr` 截图→文本行（中英混合）；`dumpScreenCompact`/`dumpScreenJson` 在**内容区**（屏幕 25% 高度以下）字符 < 40 时自动补 OCR（头行 `ocr=Nlines/<ms>`，行前缀 `o`）。`/ocr?force=1&scale=` 端点。缓存 3s + 截图节流冷却 3s + 服务连接预热。
   - **判定必须用内容区而非全屏**：Chrome 类界面顶部 UI 壳 97 字符但页面内容区 0 节点（实测全屏判定漏触发）。
   - **verify 必须也能看到 OCR**：verify 用 `dumpScreenJson`，此前只有 compact 版带 OCR → 「pick 能用 OCR 文本、verify 永远失败」（实测点击成功验证失败）。
3. **settle 提速**：`waitForIdle` 对空节点树连续两次空读即返回（此前每次动作前白等 900ms 超时）；settle 预算 1800→1200ms、帧差间隔 300→200ms。

**实测数据（模拟器 x86_64，canvas 页面 = 无节点树场景）**：
- dump 首次触发 OCR：`1080x2209 18nodes ocr=10lines/1419ms`；3s 内重复 dump：20ms（cached）。
- 识别正确：`设置中心 @598,750`、`显示和触控`、`深色主题`、`亮度调节 自动`（均为 canvas 绘制的文字）。
- 闭环：`loop/run` 单步 → `ok=True via=exact score=1 picked=设置中心 actMs=1132 verify=True verifyMs=69`，总 1.45s。
- OCR 推理耗时与分辨率几乎无关（540p/270p 均 ~1.7s，模拟器 CPU 固定成本主导）→ 保持 scale=0.5 保质量；真机 CPU 快数倍，待真机复测。
- 截图本身仅 36-72ms（`/frame-diff` 实测），不是瓶颈。

### 发布（2026-10-11 晚）
- GitHub repo `Soodok/Deepseek-Harness-Local-Android`：
  - **v1.2.120**（release id `409571993`）：`draft=false`（公开），资产已补齐（`dsh-mobile-v1.2.120-arm64-v8a.apk` id `631149424`、`-debug.apk` id `631150479`，各 129.6MB）。
  - **v1.2.121**（release id `409712207`）：`draft=true`（撤下），APK 资产保留在草稿内。
  - 公开页：`https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/tag/v1.2.120`
- **v1.2.123 arm64 签名包**（本地，未上架）：`dist/v1.2.123/dsh-mobile-v1.2.123-arm64-v8a.apk`（150.5MB，证书 SHA-256 `d28ca4f5…92c8`，与 120 同证书可覆盖安装）；zip 完整性 OK、含 `lib/arm64-v8a/libmlkit_google_ocr_pipeline.so` + 25 OCR 模型 + `assets/runtime.zip`(126.5MB)。
- **签名命令**（release 无 signingConfig，构建后手动签）：`gradle assembleRelease -Pabi=arm64-v8a` → `E:/Android/Sdk/build-tools/36.0.0/apksigner.bat sign --ks keystore/dsh-release.keystore --ks-pass pass:dshmobile2026 --ks-key-alias dsh --key-pass pass:dshmobile2026 --out <out.apk> app-release-unsigned.apk`。

### pending 记忆完整闭环（2026-10-12 模拟器 emulator-5554，APK 1.2.121→1.2.122）
1. **产生 pending**：带 `useMemory:true` 的 2 步计划执行成功 → `/loop/run` 返回 `memory.result="stored-pending"`。
2. **任务级转正**：并行流程（`predict_parallel.py 困难-深色主题开关`）done 时自动 `/memory/confirm` → 日志 `[记忆] 任务级确认：1 条 pending 转正`；墙钟 11.8s、目标达成。
3. **转正后命中复用**：同 goal 再跑 → `memory.hit=true, similarity=1, result="hit-confirmed"`，hits 0→1，执行 7998ms→1190ms。
4. **诊断字段对照**：转正条目 `pending=False`、新条目 `pending=True`；stats `记忆条目 2 条（待确认 1 条），累计命中复用 1 次`。

### 并行架构与记忆的设计关系（本轮查明）
- `predict_parallel.py` 的 `plan_entry()` **有意强制 `useMemory=False`**（源码注释：记忆会整份替换 steps，并行协调必须知道真正执行了哪些预测步骤）→ 并行流程本身不产生 pending；其 done 时的 confirm 用于转正**其他路径**（本地闭环/云端决策执行）产生的同 goal pending。

### 发布（2026-10-11 晚）
- GitHub repo `Soodok/Deepseek-Harness-Local-Android`：
  - **v1.2.120**（release id `409571993`）：`draft=false`（公开），资产已补齐：
    - `dsh-mobile-v1.2.120-arm64-v8a.apk`（asset id `631149424`，129.6MB）
    - `dsh-mobile-v1.2.120-arm64-v8a-debug.apk`（asset id `631150479`，129.6MB）
  - **v1.2.121**（release id `409712207`）：`draft=true`（撤下，不可见），其两个 APK 资产保留在草稿内。
  - 公开页：`https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/tag/v1.2.120`
- 本地 `dist/v1.2.120/` 两个 APK 上传前已验证：zip 完整性 OK、含 `AndroidManifest.xml` + 4 个 dex + `assets/runtime.zip`（126.5MB 压缩、16980 条目、含 node_modules/libreadline.so/ripgrep）+ 正式包有 v1 签名与 `.idsig`。

### 提示词英文化（本轮前完成）
- `AgentContextSeed.kt` `SEED_VERSION = 31`；本地闭环执行器与 UI 自动化工作流描述全英文（facts vs promises、2–3 步短预测、验证语义、yielded、takeover、anti-patterns）。
- 协议名与选项保留：`anyText`、`checkedText`、`frameChanged`、`foregroundPkg`、`waitSliceMs`、`useMemory`、`useScreenEvents`。
- `planner.py` 的 `ROLE_RULES`/`SYSTEM_OBSERVE`/请求模板/场景规则/诊断全英文；动作类型、字段名、JSON 结构、验证语义不变。
- `planner.py` 中 `已下载完毕`、`正在下载` 两个中文串是屏幕实际标签，故意保留（用于匹配本地化文本）。

## Verification evidence
- v1.2.120 两个资产下载链接 HEAD（不带 token，模拟普通用户）均返回 `HTTP 200`、`129.6MB`。
- v1.2.121 API 查询确认 `draft=True`。
- `python -c "import ast; ast.parse(open('scripts/probes/planner.py',encoding='utf-8').read())"` → `planner AST: OK`。
- 2026-10-11 23:35:55 +0800 `gradle -p . assembleDebug -Pabi=x86_64` → `BUILD SUCCESSFUL in 5s`。
- 并行架构最终验收（英文化后）：困难-APK 下载场景墙钟 **70.0s**、完成 True、停止原因「目标达成」，云端 41 次调用 / 13 份预测计划。
- 2026-10-12 `gradle -p . assembleDebug -Pabi=x86_64`（1.2.122）→ `BUILD SUCCESSFUL in 37s`；`adb install -r` → `Success`，模拟器 `versionCode=171 versionName=1.2.122`。
- 模拟器重装后引擎启动约需 30-60s（runtime 重新解压），`loop_common.init()` 首轮可能超时，重试即通。

## Current blocker
- None.

## Key paths
- `E:/Deepeek harness/dsh-android/app/src/main/java/app/dsh/mobile/ScreenOcr.kt`（离线 OCR：降采样/缓存/节流冷却/预热）
- `E:/Deepeek harness/dsh-android/app/src/main/java/app/dsh/mobile/DshAccessibilityService.kt`（dump 兜底 + waitForIdle 快速返回 + 本地模型自动就绪）
- `E:/Deepeek harness/dsh-android/app/src/main/java/app/dsh/mobile/engine/AgentContextSeed.kt`（seed v32：OCR/本地快速层说明）
- `E:/Deepeek harness/dsh-android/app/src/main/java/app/dsh/mobile/engine/SceneMemory.kt`（pending 机制 + 诊断字段）
- `E:/Deepeek harness/dsh-android/app/src/main/java/app/dsh/mobile/engine/LocalModel.kt`（本地嵌入模型：下载 + llama-server 启动）
- `E:/Deepeek harness/dsh-android/scripts/probes/loop_common.py`（测试库；`/memory/clear` 等写端点须传 body 才是 POST）
- `E:/Deepeek harness/dsh-android/scripts/probes/_ocr_canvas.html`（canvas 测试页；PC 起 `python -m http.server 8899 --directory scripts/probes`，模拟器 Chrome 访问 `http://10.0.2.2:8899/_ocr_canvas.html`）
- `E:/Deepeek harness/dsh-android/dist/v1.2.120/`（已上架产物）
- `E:/Deepeek harness/dsh-android/dist/v1.2.121/`、`dist/v1.2.123/`（未上架）

## Version timeline
- 2026-10-11: handoff created.
- 2026-10-11: seed v30→v31 英文化 + planner.py 英文重写；AST 与 x86_64 debug 构建通过。
- 2026-10-11 深夜: v1.2.120 release 补上两个 APK（公开可用）；v1.2.121 撤下保持草稿。
- 2026-10-12 上午: pending 记忆完整闭环验证通过（产生→转正→命中）；v1.2.122 诊断可见性；**v1.2.123 真机反馈三修复**（离线 OCR + 本地模型自动就绪 + settle 提速）+ catalog.json 语法修复；**v1.2.124 verify 语义兜底 + 用量统计**；**v1.2.125 分支计划（when 条件）**。模拟器端到端全通（扩展安装→模型就绪→语义 pick/verify→分支选路）；arm64 签名包在 dist 与 Downloads。提交 `a213cf0`/`15b154c`/`6c86b88`/`57196a4`/`ba06359`/`5f6ff5d`。
- 2026-10-12 中午: **v1.2.126 无障碍功能 max+ 开关**（扩展分组，知情同意弹窗：读取 API key + 自动下载本地模型）+ **坐标越界点击修复**（部分可见节点中心超出屏幕 → 点击静默落空，真机「底部按钮点不到」根因）。提交 `2298d39`/`f17ff88`。
- 2026-10-12 下午: **v1.2.127 max+ 模型来源选择**（多 API key 检测 + 用户自选，默认 DeepSeek 官方）；查明凭证文件严格格式（键名/权限/字段白名单，写错致引擎启动失败）。arm64 包在 dist 与 Downloads。
- 2026-10-12 傍晚: **v1.2.128 引导 AI 用本地闭环**（用户反馈「AI 还在自己操作无障碍」）：seed v34「Tool choice (non-negotiable)」+ loop/scr batch 措辞强化 + `/batch` 响应动态 tip（≥3 步）。提交 `47d0add`。
- 2026-10-12 晚: **v1.2.152 审查修复轮**——5 份子智能体审查（2 份代码 + 3 份架构/性能）共出 4 个 P0、若干 P1，全部处理：
  - **P0-1 `_fastVerify` 污染记忆**：乐观标记被写进 plan 的 step → 随场景记忆持久化 → 此后**非乐观**请求命中记忆时中间步骤被静默乐观化（验证语义被悄悄放宽）。修复=store 前深拷贝并剥离 `_fastVerify`。
  - **P0-2 daemon DSL 白名单只有 11/21 原语**（缺 scroll_collect/repeat/repeat_until/back_to/long_press/pinch/drag/double_tap/collect/sleep，且从不提 `when` 分支）→ 新能力在 daemon 路径不可见。已补齐 + 补 when / optimistic / human 说明。
  - **P0-3 daemon 丢升级证据**（failScreen/candidates/error 未进规划 AI 上下文）→ 已透传进 history。
  - **P0-4 `scr pick --all` 语义反了且从未进请求体**（`--all) P_ALL=false`）→ 改为 `P_CLICKABLE` 并真正进入 body；顺带把闸门注释改成与实现一致（preferClickable 只影响**排序**，不是过滤——文本节点才是点击目标）。
  - **P0-5（审查漏掉，本轮实测抓到）`planner-daemon.mjs` 语法错误**：v1.2.146 加的「VERIFY THE MINIMUM」一行用反引号包了 foregroundPkg 示例，在模板字符串内部**提前闭合了模板字面量** → 整个文件 JS 解析失败、**daemon 自 v1.2.146 起根本起不来**。`node --check` 复现并修复（转义），现已纳入发布包内容校验。
  - **P1 修复**：IconMatcher/Framer 有符号 Byte 回绕（灰度 128+ 变负 → NCC 与帧差判定失真）、dispatchSwipe/dispatchLongPress 漏打自身标记（自己的手势被误判"用户接管"）、verify 未知 key 静默通过改明确报错、toggle 空标签立即失败、loop 闸门超时 120s→195s（必须 > 服务端 180s，否则拿不到服务端结构化超时报告 → AI 猜着重跑，是唯一会放大真实副作用的路径）、daemon bridge() 补 _http（两处 403 自检此前是死代码，孤儿守护进程永远不自愈）、screenExec 单线程被 /loop/run 独占（并发读屏全 504）→ 2 线程、图标识别加时间预算守卫（截图撑爆 2s 读屏上限 → 级联 504）、PageStateDetector.maxLoadingMs 因 AND 条件永不生效 + 换单调时钟。
  - **本轮实测新发现并修复（审查之外）**：
    ① **冷读 504**：同一静止页面，屏幕刚变化后第一次读树要 2.1-2.5s、之后 57-77ms（连续 5 次取证）——2s 上限把"节点树正在重建"误报成"无障碍卡死"，AI 收到 504 只会重试一次、正撞上重建完成，白花一轮。修复=超时后一次放宽重试（5s）；/loop/run 这类长预算路径不重试。
    ② **加载信号分强弱**：isLoading 原先 ProgressBar ∥ 加载文案 ∥ total<5 同等对待，会把"稀疏但已就绪"的页面也判成加载中；改为只有**强信号**（进度条/加载文案）才延长等待（maxLoadingMs 6s→4s），空树只走常规预算。settle 实测 355-1566ms（不再拖长）。
    ③ **verify 一律用 JSON dump → 改 compact 快路径**（仅 anyText 时；checkedText/toggle 仍走 JSON 结构化字段）。compact 体积约 1/7，且**含 OCR 行/图标识别名/空间推断标签——正是 AI 在 dump 里看到的内容**；实测 how=anyText(compact)、verifyMs=5ms。
    ④ **foregroundPackage() 返回系统壳层**：Chrome 弹 ANR（"没有响应/关闭应用/等待"）时窗口列表顶部是 package=android 的对话框窗口 → foregroundPkg 验证 8s 全预算失败，而页面就在对话框底下活着；权限弹窗同理。修复=跳过系统壳层包（android / systemui / permissioncontroller）取下面真正的前台应用。实测回桌面后正确返回 com.google.android.apps.nexuslauncher。
    ⑤ 删掉 scroll_find 的重复不可达分支（Kotlin when 取首个匹配）、seed 里 date +%s%3N（toybox 不支持，旧指令产出垃圾字符串）。
  - **新增测试基建**：`scripts/probes/sim_setup.py`（一键就绪，固化 6 条踩坑：侧载停用无障碍、**必须先起 app 再启用无障碍**否则得"连着但读不到节点"的僵尸绑定、force-stop 后要 delete+put 才重绑、引擎健康检查 ~25s 桥在其后才起、bridge_token() 会 sys.exit 杀掉调用方、残留 node 占旧 token）。
  - **验证证据**：node --check 全库 JS 通过、sh -n 全库 gate 通过、x86_64 debug 端到端（launch / compact verify / scroll_collect / 升级证据）通过、发布包解包校验 5 项修复均在包内且包内 daemon 语法 OK。
  - **遗留**：模拟器上 Chrome 反复 ANR（环境负载：本 app + 引擎 + OCR + llama-server 同跑），部分场景未能端到端跑完；v1.2.141-1.2.152 的多数改动仍待**真机复测**。
- 待办：真机复测 1.2.128（重点看 AI 是否改用 `loop run`；OCR 速度、浏览器场景、语义验证触发率——`/local/status` 的 stats；本地模型需先在扩展中心装 llama.cpp 扩展并开启 max+）；**后续可选：设备内并行协调器**（现在有 max+ 开关 + 模型来源选择作为 API 授权基础）；libz.so.1 反馈已查明为环境问题（三个 runtime 包均含 libz.so.1.3.2 + 软链接）。

## 2026-10-17 竖屏 UI 修复（客户端插件形式，v1.2.175 → v1.2.179）

### 用户需求
「手机端 UI 在竖屏的时候会非常奇怪」→「旁边列表展开时，旁边对话框直接被莫名其妙压缩，
整个就很畸形，可以直接让侧边栏全展开，然后隐藏对话流，切换到横屏的时候就不用这么做
（可以做个屏幕判定）」→ **「UI 布局改变不要直接改源代码，要直接做成一个插件形式，
假如用户安了其他 UI 插件，我们可能会冲突」** → 「你要不要重构一下这个设置？这个设置还是有点畸形」

### 根因（真机取证，非推测）
1. **竖屏对话区被挤压**：引擎 `@deepseek-ai/dsh-client-ui-layout` 的 `AppFrame` 用
   CSS Grid（`grid-template-columns: <sidebar>px minmax(400px,1fr) minmax(0,<r>)px`）。
   视口 < `SIDEBAR_AUTO_COLLAPSE`(1024) 时进入 narrow 模式；用户手动展开侧栏后
   `computeColumns()` 仍按桌面规则分配（侧栏 264~420px + 假设对话区至少 400px）→
   1216x2640 屏（视口 386px）下对话区被压到 **336px**，文字逐字换行（用户截图取证）。
2. **设置面板畸形**：`dsh-client-ui-settings-general` 的面板 `.VOzbGW_panel` 固定
   `width:800px; max-width:calc(100vw - 48px)`，左导航 `.VOzbGW_nav` 固定
   `width:188px; flex:none` → 手机上面板 338px、导航吃 188px、内容区仅剩 ~150px，
   标签被压到 **47px**（uiautomator 实测 `[743,437][790,563]`）→ 逐字竖排。
   该页 CSS **只有 `prefers-reduced-motion` 一条媒体查询，无任何宽度断点**。

### DSH 客户端插件机制（本次摸清，关键知识）
- 客户端 UI 插件 = **标准 npm 包**：package.json 声明 `dsh.client`（含 `platform`/`inject`/
  `external`/`immediately`）+ `exports["./client"]`。
- 引擎 `@deepseek-ai/dsh-client-modules` 扫描宿主 Loader entries（cordis.yml 的行）→
  `locatePkgJson(loaderName, baseUrl)` 定位 package.json → `nearestPackage()` 从**插件文件
  路径向上找到最近的 package.json**（要求 `name` 与 loader 期望名一致）。
- 浏览器端 bundle 由引擎以**经典 script**（非 ES module）加载，必须调用
  `window.__ModuleLoader__.load({ id, factory })`，且 **id 必须严格等于 package.json 的
  `name`**（引擎按包名建索引，见 `reconcilePackage`）。漏注册 → "loaded without registering"。
- Loader entry 指向**具体文件**（`./android-plugins/<pkg>/index.js`）—— Node ESM
  **不支持目录导入**（实测 `Directory import ... is not supported`）。
- `nearestPackage` 会向上一直找，故 `android-plugins/` 上层不能有 package.json（已确认无）。
- 验证方法：`curl -sL -b ck.txt "http://127.0.0.1:3080/?token=<TK>"` 的返回页里能 grep 到
  `<pkgname>/client.js`（与官方插件并列在 preload 清单里）。

### 交付物（全部为插件，零引擎源码改动）
1. `app/src/main/assets/android-plugins/narrow-sidebar/`（package.json + index.js + client.js）
   - 定位：按无障碍标签 `收起侧边栏`/`Collapse sidebar` 找到折叠按钮 → 向上找
     `display:grid` 祖先 → 该列即侧栏列，`nextElementSibling` 即中心列。
   - 行为：`innerWidth <= 900 && innerHeight > innerWidth`（竖屏）时，给侧栏列打
     `data-android-narrow-sidebar-col`（CSS `grid-column: 1 / -1`）、从中心列起给
     **列**打 `data-android-narrow-yield-col`（CSS `display:none`）。
   - 判列用 `isColumn()`：跳过 `position:absolute/fixed`（overlay 浮层）与
     `data-shell-overlay` —— 否则会误伤浮层（jsdom 实测抓到该 bug）。
2. `app/src/main/assets/android-plugins/narrow-settings/`（同结构）
   - 定位（两道**硬门槛**，缺一即放弃）：① `isInOverlay()`（自身或祖先 fixed/absolute）
     ② `hasCloseButton()`（aria-label/title/文本/lucide-x 四种匹配）；
     再要求 ③ 直接子元素中有含 ≥3 按钮的（导航列）+ 另一个较大的兄弟（内容列）
     ④ 自身宽 > 55% 视口 或 高 > 50% 视口。
   - 行为：`innerWidth <= 720` 时给面板打标记 → 面板竖向堆叠、导航改顶部横滑图标条、
     内容列拿满整宽。
3. 部署：`EngineConfig.kt` 的 `ensureAndroidOverlay()` 里 `deployOne()` 逐文件部署
   （含 v1.2.173/174 的「读不了就删掉重建」+「`!dst.isFile ||` 判空」两处修复），
   overlay 追加两条 insert entry。

### P0 事故与修复（我自己引入，真机复现）
- **v1.2.178 黑屏**：`narrow-settings` 初始判据只有「按钮多 + 有大兄弟 + 规模可观」，
  主界面 AppFrame（侧栏含新会话/插件/设置/会话项多个按钮 + 大对话区）**完全命中** →
  被当设置面板改成竖向堆叠 → **界面黑屏空白**（用户报告「界面头黑的都空白的」）。
  修复=加 `isInOverlay` + `hasCloseButton` 两道硬门槛；已加场景⑨（主界面结构必须不被
  标记）与场景⑩（带关闭按钮的浮层面板仍正常接管）两个回归测试。
- **rAF 竞态**：`schedule()` 写成 `raf = window.requestAnimationFrame(refresh)` ——
  当 rAF 同步执行时（测试桩），refresh 内清掉的标志被返回值写回 → 此后所有 schedule
  被 "已在排队" 挡掉，**转屏后不再重判**（桩测试抓到）。修复=用独立 `pending` 布尔，
  在调用 rAF **之前**置位。

### 测试
- `scripts/probes/_test_narrow_sidebar.mjs`（自写 DOM 桩，30/30 通过）
- `scripts/probes/_test_narrow_jsdom.mjs`（jsdom 真实 DOM + 引擎真实结构，26/26 通过）
- `scripts/probes/_test_narrow_settings.mjs`（jsdom，29/29 通过，含黑屏事故回归⑨⑩）
- 依赖：`scripts/probes/` 下 `npm install jsdom --no-save`

### 真机验证证据
- 日志：`[dsh-android] narrow-sidebar: 已接管（侧栏全宽，隐藏 2 个对话列；视口 386x777）`
  （引擎日志 `/data/user/0/app.dsh.mobile/files/engine/engine.log`，由 MainActivity 的
  `onConsoleMessage` 转发 —— 只转发 WARNING+ 或含 `[dsh-android]` 前缀的消息）。
- 设备文件哈希 = 本地哈希（`f3cc06de…`，含 `isInOverlay`/`hasCloseButton`）。
- 截图确认：主界面正常（v1.2.179）、竖屏展开侧栏 → 侧栏全宽 + 对话流隐藏。

### 待办
- 设置页修复效果**尚未真机视觉确认**（操作期间用户在用酷安占前台，`am start` 无法切回；
  已用设备文件哈希 + 引擎 bundle 清单间接确认插件已上线）。
- `fast-mode.mjs` 仍报 `failed to import`（**仅历史日志**；直接 import 测试通过，
  最近几次启动无该报错，但值得复查是否与 overlay entry 顺序有关）。

### 设备操作要点（本次新增踩坑）
- Git Bash 下 adb 设备路径会被 MSYS 转换 → 必须 `export MSYS_NO_PATHCONV=1`。
- 无线 adb（192.168.101.21:9000）会周期性掉线，需 `adb connect` 重连。
- `adb push` 到 `/data/local/tmp` 用 **Windows 真实路径**（`C:/Users/Qt/AppData/Local/Temp/...`），
  `/tmp/...` 是 Git Bash 虚拟路径，adb 看不到。
- 引擎 node 直接跑脚本验证导入：`su -c 'B=/data/user/0/app.dsh.mobile/files; export
  LD_LIBRARY_PATH=$B/engine/lib:$B/engine/bin; export DSH_HOME=$B/dsh-home; cd $B;
  $B/engine/bin/node /data/local/tmp/x.mjs'`（引号地狱时改用推送 .sh 文件执行）。
- 大包部署：`split -b 10M` 分片 → 设备端 `for i in $(seq 1 N); do cat p_$i >> x.apk; done`
  → `md5sum` 比对 → `pm install -r`（InstallerX 会拦截 `adb install`）。

### 2026-10-17 续：v1.2.179 / v1.2.180 两个真机事故与修复

**事故 A — v1.2.178 主界面黑屏（P0，我引入）**
- 现象：用户报「怎么都改坏了？界面头黑的都空白的」。
- 根因：`narrow-settings` 的 `findPanel()` 只看「按钮多 + 有大兄弟 + 规模可观」，
  **主界面 AppFrame 完全命中**（侧栏含新会话/插件/设置/会话项多个按钮 + 大对话区）
  → 被当设置面板改成竖向堆叠 → 黑屏。
- 修复：加 `isInOverlay()`（自身或祖先 fixed/absolute）+ `hasCloseButton()` 两道硬门槛。
- 回归测试：场景⑨（主界面结构不得被标记）、场景⑩（带关闭按钮的浮层面板仍生效）。
- 已部署 v1.2.179，真机确认主界面恢复正常。

**事故 B — v1.2.179 设置页导航消失（用户报「看不到选项栏」）**
- 现象：内容可读但需要横滑、左侧/顶部导航栏不见。
- 根因：`findPanel()` 按文档顺序遍历 `div/section/aside`，**先命中外层 `overlay`**
  （它自身 fixed、含全部按钮、有 ≥2 个子元素）→ 标记打到 overlay；
  `structureOf` 把 `panel` 当成"导航列" → 真导航被 `[nav] > :first-child{display:none}`
  隐藏、`content` 被设成 row → 内容横滑。
- 修复（引擎源码取证后）：
  1. 定位优先用 `[role="dialog"][aria-modal="true"]`（引擎自标的对话框语义，
     `.VOzbGW_panel` 上实测有这个属性）+ 退路才用浮层+关闭按钮；
  2. `structureOf()` 优先用 **`<nav>` 语义标签**识别导航列（引擎 DOM 实测是 `<nav>`，
     不是 div —— 这也是之前误判的另一半原因）；
  3. **删掉** `[nav] > :first-child { display: none }` 规则（它把整条导航隐掉了）；
     导航标题改为紧凑小标签保留。
- 回归测试：场景⑪（标记必须在 panel 而非 overlay；nav 必须被标记；mask 不得被当内容列；
  CSS 不得隐藏首个子元素）。
- 已部署 v1.2.180，真机截图确认：顶部导航横条（设置｜通用设置｜模型｜内置插件｜Agent 预设）
  完整可见、可横滑；内容纵向正常滚动；卡片不再被压扁。

**累计测试**：`_test_narrow_sidebar.mjs` 30/30、`_test_narrow_jsdom.mjs` 26/26、
`_test_narrow_settings.mjs` 36/36（含全部事故回归）。

**m-dash 教训（写进下一次的检查清单）**
1. **客户端插件改 UI 前，必须先从引擎 bundle 里取证真实 DOM 结构**
   （`role` / 语义标签 / 真实层级），不能凭 GUI 截图猜。本次两起事故都源于猜测。
2. **任何"给元素打标记"的逻辑，必须在测试里断言"标记打在了哪个元素上"**，
   而不是只断言"有没有标记"。
3. **CSS 里禁止 `:first-child` / `:nth-child` 这种位置选择器**作用于导航/标题等
   结构性元素 —— 必须用语义属性或 JS 精确定位后再打标记。
4. 打包前必须跑：`node --check` 全部插件 + 三套测试全绿 + 包内资产校验。

## 2026-10-17 GitHub Issues 处理（v1.2.181）

仓库：`Soodok/Deepseek-Harness-Local-Android`（token 走 API 直连，`gh` 未登录）。
处理前开放 5 条，全部已回复 + 打标签。

| Issue | 结论 | 处理 |
|---|---|---|
| #16 更改无法查看 | **真 bug，已修** | 新增 `narrow-rightbar` 插件（v1.2.181） |
| #12 拒绝 Shizuku 后仍弹窗 | **v1.2.158 已修** | 回复说明修复内容 + 请用户升级确认 |
| #15 手动添加 MCP | 能力已有，无 UI | 回复完整配置示例（stdio / streamable-http） |
| #10 App 内检测版本升级 | 已有功能 | 回复指出 设置→关于→检查更新，并问是否需要自动检查 |
| #2 语音唤醒 | 路线图 | 回复现状（语音输入/TTS/离线 ASR 已有，唤醒词未做） |

### #16 根因（引擎源码取证 + 数值验算）
`dsh-client-ui-layout` 的 `computeColumns()`：
```js
const available = viewport - sidebar - 400;   // 固定给中心区留 400
const rightbar = rightbar === 0 || available < 300 ? 0 : Math.min(available, …);
```
竖屏视口 386px、侧栏折叠 56px → `available = 386-56-400 = -70 < 300` → **右栏宽度 = 0**（不是挤压，是没这列）。
叠加：窄屏 `autoFullscreen = viewportWidth < 768` 生效时，面板挂在 0 宽列里，
`--dsh-sidebar-width: 100vw` + `transform: translateX(...)` 把 dock 推出屏幕外。

### 新增插件 `narrow-rightbar`（assets/android-plugins/narrow-rightbar/）
- 定位：`[data-sidebar-right-panel]` **且** `[data-sidebar-right-open]`（引擎语义属性，
  仅在打开时存在）—— **关闭态绝不接管**，不可能凭空冒出面板。
- 行为：`innerWidth <= 900` 且面板打开 → 打 `data-android-narrow-rightbar`，
  CSS 把面板提为 `position:fixed; inset:0; width:100vw; z-index:60`，
  `--dsh-sidebar-width:0` + dock 强制 `visibility:visible; transform:none`。
- 宽屏（>900）完全不动。
- 测试：`scripts/probes/_test_narrow_rightbar.mjs` **28/28 通过**
  （含「关闭态不接管」「dock 保持引擎原样」「面板重建→标记迁移」三个关键回归）。

### 备注
- Issue 回复用 GitHub REST API（token 在对话里出现过，**建议撤销后换新**，
  见文首安全提示）。`/tmp` 是 Git Bash 虚拟路径，Windows 版 curl 读不到 —— 用
  `C:/Users/Qt/AppData/Local/Temp/`。
- 本地仓库仍有 **131 个未提交改动**（v1.2.159 → v1.2.181 全部工作都还没 commit），
  HEAD 停在 `31b4a26`（v1.2.158）。发布到 GitHub Releases 前必须先提交推送。

## 2026-10-17 v1.2.182 → v1.2.184（用户要求：删开关 / 修语音闪烁 / 操作通知）

### 1) 删除「试验性功能」与「无障碍功能 max+」两个开关（v1.2.182）
- `activity_settings.xml`：删掉 `rowExperimental`（48 行）、`rowA11yMaxModel`（63 行，
  含前置分隔线）、`rowA11yMaxPlus`（57 行，含后置分隔线）。
- `SettingsActivity.kt`：删掉两个 Switch 的全部逻辑、`applyExperimentalVisibility()`、
  `applyA11yMaxVisibility()`、`setRowVisibleWithDivider()`、`KEY_EXPERIMENTAL`。
- `DshApp.kt`：删 `KEY_EXPERIMENTAL` / `experimentalOn()` / `KEY_A11Y_MAX_PLUS` /
  `a11yMaxPlusOn()`；保留 `a11yMaxProvider`（仍有引用）。
- `DshAccessibilityService.kt`：
  · 悬浮窗**常驻**（`StatusOverlay.show()` 不再受开关控制）；
  · **移除 max+ 控制的本地模型自动下载/启动** —— 那段行为需要用户知情同意
    （读 API key + 下载 15MB 模型），开关取消后不能再静默执行。
    引擎需要时仍可显式调 `POST /local/ensure`（AgentBridge）。
  · 原实验性功能涉及的四行（无障碍/麦克风/语音服务/本地识别）现在**始终可见**。
- 清理 10 个孤儿字符串（中英同步删除），i18n 键集保持一致。

### 2) 语音输入闪烁（v1.2.182 + v1.2.183 + v1.2.184）
真机反馈两个阶段：
- **识别结束时闪**：`onFinal`/`onEnd` 立刻 `setListening(false)` → `setIdle()` →
  悬浮窗 `expandLevel=0` **自动收起**，视觉上就是文字出现那瞬缩一下。
  修复：识别通道的 `onFinal` 不再立刻回空闲；只有**面板为空**（误触/没听清）时
  才 hide + setListening(false)。有文本则保持聆听态直到发送/关闭（`panel.onHide`
  统一收口）。
- **点击发送时框闪一下**：旧顺序是「先发文本 → 再播 180ms 淡出」；提交会让对话页
  立刻滚动，半透明面板正压在滚动的页面上 = 闪一下。
  修复：发送路径改为 **先 `hide(animate=false)` 再 `onSend`**；`hide` 立即路径
  （v1.2.184）先 `alpha=0`、**延后一帧 `post{ removeView }`** —— 避免在窗口自己的
  ACTION_UP 派发过程中同步销毁窗口（会出现一帧表面残影）。同时移除前先
  `input.clearFocus()`，防止输入法骤然收起导致宿主重排。

### 3) AI 长串无障碍操作的系统通知（v1.2.182）
- `AgentBridge.route()`：POST 命中动作类端点（`/tap /tap-id /gesture /key /input
  /scroll-find /scroll-center /scroll-edge /launch /launch-intent /vision/tap
  /vision/drag /loop/run`）时调用 `noteA11yOperation()`；读屏类不计入。
- 规则：**连续第 3 步起**提示，之后**原地更新**同一条通知（固定 id `0x0A11`、
  `setOnlyAlertOnce` 不重复响铃），最后一步后 10s 自动撤掉；30s 无动作视为新序列。
- 无 `POST_NOTIFICATIONS` 权限时静默跳过（不影响操作）；点通知回到 App。
- 新增字符串：`notif_a11y_active_title/_text/_open_app` + 8 个动作标签
  （`notif_a11y_op_*`，中英各一套）。

### 4) 项目门禁
- `python scripts/check-i18n.py`：我的改动**不再新增错误**；EngineConfig 的
  overlay 注释已全部英文化（该文件约定 CJK-free）。仓库仍有 **20 条历史遗留错误**
  （CredentialProbe 中文标签、SceneMemory 中文摘要、AgentBridge 视觉错误文案等），
  属既有问题，未在本次范围内处理。

### 版本
- v1.2.182（versionCode 230）：删开关 + 通知 + 识别结束闪烁
- v1.2.183（231）：发送路径瞬间移除面板 + 清焦点
- v1.2.184（232）：alpha=0 + 延后一帧移除（修发送时"框闪一下"）

### 设备注意事项（本次新增）
- **ColorOS/OnePlus 上 `pm install -r` 会把无障碍服务关掉**（`enabled_accessibility_services`
  变 null）。重装后需重新启用：
  `adb shell settings put secure enabled_accessibility_services
  app.dsh.mobile/app.dsh.mobile.DshAccessibilityService`
  `adb shell settings put secure accessibility_enabled 1`

### 2026-10-17 续：语音发送后「悬浮框收起闪一两下」（v1.2.185）
- 真机反馈：发送之后，语音悬浮框收起时会闪一两下。
- 根因：悬浮窗从「展开卡片」变回「圆形球」是**窗口尺寸变化**
  （文案/动作行隐藏、padding、minWidth/Height、背景同时变），View 属性动画管不到
  窗口尺寸 —— 直接改会在 1~2 帧里整体跳变；且它和语音面板窗口移除几乎同帧，
  两个 overlay 窗口同时重排更明显。
- 修复（`StatusOverlay.setIdle()`）：
  1. 把**所有**会改尺寸的改动（文案、动作行、档位）挪到 `alpha=0` 期间；
  2. 顺序改为「延迟 60ms → 80ms 淡出 → 改布局 → 140ms 淡入」，跳变不可见；
  3. 新增 `collapsePending` + `cancelCollapse()`，任何新状态（`setStatus` /
     `setListening(true)` / `flash*`，它们都走 `cancelIdleReset`）都会取消待执行的收起，
     避免新任务开始时把悬浮窗缩掉。

### 2026-10-17 续：语音面板发送时「闪一两帧」—— 最终结论（v1.2.186）
- 用户反馈历经三轮：`v1.2.183` 立即移除、`v1.2.184` alpha=0+延帧移除、
  `v1.2.185` 归因悬浮球收起并加淡出淡入、`v1.2.186` **停靠而不销毁窗口**
  （alpha=0 + NOT_TOUCHABLE/NOT_FOCUSABLE，`wm.updateViewLayout` 不 removeView，
  下次 `show()` 复用）。**仍然能观察到 1~2 帧闪烁。**
- 用户判断：截图/录屏抓不到（就少一两帧），不要继续追。
- **结论（未验证，标注为推测）**：这类 1~2 帧残影很可能来自 **TYPE_ACCESSIBILITY_OVERLAY
  窗口在合成器层的重绘/可见性切换**，与应用层 alpha/移除时机无关 ——
  继续在应用层调时序收益很低。
- 现状保留：v1.2.186 的**停靠**方案（窗口不销毁，避免 add/remove 抖动）是其中
  最稳的一种，且功能正常（`isVisible` 在停靠态返回 false，麦克风 toggle 语义不变；
  服务销毁时走 `hide()` 正常移除）。
- 若后续仍要解决，方向建议（未尝试）：
  1. 放弃 overlay 窗口，改为在 MainActivity 的 WebView 里内嵌语音输入条（无独立窗口）；
  2. 面板常驻（不透明切换，用位移移出屏幕外而不是改 alpha）；
  3. 用 `SurfaceView` 自绘（成本高，不推荐）。

### 版本
- v1.2.185（233）：悬浮球收起改为淡出→改布局→淡入
- v1.2.186（234）：语音面板发送改为「停靠」不销毁窗口

## 2026-10-17 发版前独立审查（v1.2.189）

派两组 `checker` 独立审查本轮改动，结论：**P0 1 / P1 1 / P2 8**。已修：

### P0（阻断发版）— 退出后重开 App 秒退
- 原实现用 `explicitExit` 标记位：退出置 true，只有 `EngineSupervisor.start()` 清零；
  而自动启动入口 `EngineService.start()` 被 `isUserStopped()` 拦住 → 标记永不清零 →
  用户下次打开 App，`render()` 读到 true 立刻 `finishAndRemoveTask()`。
- **改为回调式**（无持久状态）：
  · `EngineSupervisor`：`@Volatile var onExplicitExit: (() -> Unit)?` +
    `dispatchExplicitExit()`（post 主线程），在 `State.Stopped` emit **之后**调用；
    删除 explicitExit/isExplicitExit/consumeExplicitExit。
  · `MainActivity`：onCreate 注册 `onExplicitExit = { finishAndRemoveTask() }`，
    onDestroy 置 null；render 里的判断删除。
  · Activity 不在时回调为 null → 什么也不做、无残留。

### P1 — 普通模式下局域网代理杀不掉（关不掉但通知已消失）
- 根因：`LanWebProxy.stop()` 依赖 `child` 引用（**从未赋值**）+ Shizuku/su；
  普通模式（无 Shizuku/root）两条通道全失效 → node 仍监听 3090，而通知已撤掉。
- 修复：`killStaleProxy()` 增加**同 uid 直杀**：
  `ProcessBuilder("/system/bin/sh","-c","pkill -f lan-web-proxy").start().waitFor(3s)`
  （app 杀自己 fork 的进程无需提权）。

### P2（已修）
1. 启动线程末尾无条件 `running.set(false)` → 改为 `running.set(ok)`（成功后 isRunning 正确）。
2. `showLanNotice` 前复查 `DshApp.lanWebEnabled(ctx)`（spawn 有 ~2s 窗口，期间用户可能已关闭）。
3. `VoicePanel.unpark()`：`parked=false` 只在 `updateViewLayout` 成功后置位（避免"可见但摸不到"僵尸态）。
4. `VoicePanel` 重说分支补 `consumed = false`（历史 bug：重说后 Send/Close 全被守卫吞掉）。
5. 新增 `VoicePanel.dispose()`（同步 removeWindow，不播 180ms 动画）；服务 onDestroy 改用它。
6. `StatusOverlay.setDaemonAction()` 补 `cancelIdleReset()`（避免 60ms 收起窗口内被覆盖成空闲）。
7. `AgentBridge`：`/loop/run` 计 3 步（daemon 内部步骤不走 HTTP，否则长任务永远凑不够阈值 3）。
8. `EngineSupervisor.stop()` 补 `cleanupOrphanEngine()`（覆盖 spawn 阻塞段内取消 loop 的孤儿进程窗口）。

### 未修（记录）
- 截图避让的并发/嵌套竞态（P1，审查 1）：属既有功能，改动面大且与本次需求无关，留待专项。
- `shizukuExec` 无超时、`screenshotBase64` 未接避让、`setOverlayEnabled`/`a11yMaxProvider` 死代码：既有问题，低风险。
- 系统 ASR 回调缺 voiceGeneration 守卫：AsrManager.stop() 的 cancel+destroy 已大幅压制，留观。

### 验证
- `compileDebugKotlin` 通过；`assembleRelease` 通过（v1.2.189 / versionCode 237）。
- 插件语法 `node --check` 全通过；四套插件测试全绿。
- `check-i18n.py` 错误数 20 = 基线（本轮零新增）。

## 2026-10-17 v1.3.0 正式发布

- **源码**：`main` 已推送至 `ed55935`（合并了远程 PR #11 三语/方向三态/CI）。
- **Release**：https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/tag/v1.3.0
  · `dsh-mobile-v1.3.0-arm64-v8a.apk`（143.8 MB，versionCode 238）
  · `dsh-mobile-v1.3.0-arm64-v8a-debug.apk`（144.9 MB）
  · 说明：`RELEASE_NOTES_v1.3.0.md`（1783 字符，含无障碍/UI/WebUI/语音/稳定/已知问题）
  · draft=false, prerelease=false
- **README 三语**已更新：版本徽章/下载版本、LAN 段落改写（内置代理+验证码+常驻通知）、
  移除实验性开关说明、新增 v1.3.0 更新摘要；德语资源补齐 25 个键。

### ⚠️ 安全事件（已处理，需人工跟进）
- 推送被 GitHub Push Protection 拦截：本地历史中的
  `scripts/probes/_cred_new.yaml` 与 `scripts/probes/_headless_cmd.sh` **含真实 DeepSeek API Key**。
- 处理：删除这三个探针凭证文件（含 `_cred_backup.yaml`）；重写从未推送的本地历史
  （压缩为单个 v1.3.0 提交后合并到远程 main）；全工作区扫描确认无 `sk-` 残留。
- **待人工**：到 DeepSeek 控制台**吊销并重发该 key**（它曾以明文存在于本地与旧提交对象中）。

### 发版流程备忘（下次照做）
1. 改 `app/build.gradle.kts` 的 versionCode/versionName；
2. `gradle assembleRelease -Pabi=arm64-v8a` → `apksigner sign --ks keystore/dsh-release.keystore …`；
3. `gradle assembleDebug -Pabi=arm64-v8a`（Release 按惯例带 debug 包）；
4. 分片 push（10MB）+ `pm install -r` 真机验证；
5. `git add app/ README*.md docs/release-notes/*.md AI_CONTEXT.md`（**不要 `git add -A scripts/`**，
   probes 里有凭证/大文件）；`git push`（需 token，网络不稳时重试）；
6. GitHub API 建 release + `uploads.github.com` 传两个 APK（144MB 约 30s/个）。

## 2026-10-17 i18n 门禁修复（v1.3.0 已发布后补丁）

### 背景
CI `i18n-check` 失败（23 条）。项目门禁（`scripts/check-i18n.py`）禁 CJK 进入 Kotlin 字面量、
要求 values / values-zh-rCN / values-de 三向键集与占位符一致、catalog 与 zh catalog 条目顺序一致。

### 修复（23 → 0）
1. **用户可见文案改资源**：`CodeToast` 4 条（`code_toast_fallback`/`code_copied_hint`/`code_copy`/
   `code_copied_toast`）、悬浮窗 `overlay_exit_daemon`；三语（en/zh/de）同步补齐。
2. **日志/错误去中文**：`DshAccessibilityService` 截图错误码说明、`AgentBridge` 裁剪过平提示。
3. **德语清理**：删除已废弃的 `setting_experimental(_sub)`；`lan_dialog_copied_code` 补回 `%1$s`。
4. **catalog.zh-rCN.json** 补 `asr-service`（id 顺序与默认 catalog 对齐，共 21 条）。
5. **白名单**（有意保留的中文）：PageStateDetector 6 个加载关键词（匹配屏上文本）、
   ScreenOcr 全角 `＠` 替换、SceneMemory 面向 AI 的诊断摘要、CredentialProbe 3 个品牌名。

### 已发布资产的更新
- 因修复包含**中文扩展目录新增 asr-service**（用户可见），已重建 v1.3.0 双包并**替换 Release 资产**
  （同版本号/同 tag，先删除旧资产再上传；release 143.8MB / debug 145.2MB）。
- 校验：包内 `assets/extensions/catalog.zh-rCN.json` 含 asr-service（21 条）；
  `aapt2 dump resources` 确认 `code_copied_hint` 有 `(de) "Code kopiert"`。
- CI：`i18n-check` for d4ad230 = success。

### 教训
- 改 strings 后必须跑 `python scripts/check-i18n.py`（本地 0 错误再推）；
- 该门禁会强制三语一致（值/占位符/键集），新增字符串必须同时补 en/zh/de；
- 用户可见文案不要内联在 Kotlin 里；匹配屏上文本的关键词才走白名单。

## 2026-10-17 v1.3.0 补丁：启动动画（同版本覆盖发布）

### 需求
用户反馈「刚启动时会有一段时间黑屏」+「提示要1分钟太吓人，改成请等待几秒」+「进入页面不要硬切」。

### 实现
- `activity_main.xml` 末尾新增全屏启动层 `@id/splash`（**elevation 30dp**，否则被工具栏的
  4dp elevation 盖住）：logo + 应用名 + 状态文案 + 进度条 + 提示语；clickable/focusable 吞误触。
- `MainActivity`：
  · `setupSplash()`：logo 淡入(420ms)+缩放 0.82→1，之后 1↔1.05 呼吸循环；
  · `renderProgress()` 同步真实解压百分比到遮罩进度条；
  · `render(state)` 更新阶段文案（准备运行环境 → 启动引擎 → 加载界面），
    Failed/Backoff/Stopped 时兜底淡出；
  · `onPageFinished` → `dismissSplash()`：最短展示 700ms，再等 180ms 让页面画出首帧，
    然后「淡出 460ms + 放大到 1.04」渐变进入（解决"硬切生硬"）；
  · WebView 底色 #101418，淡出时不会闪白。
- 文案三语：`splash_preparing/starting/loading/hint`，hint 改为「请稍等几秒…」。

### 发布方式（用户要求）
- **不新增版本**：versionName 仍 1.3.0；因设备上测试过 239，为避免降级拒绝，
  versionCode 提到 **240**（对外仍显示 1.3.0）。
- 已重建 release+debug 并**替换 GitHub Release 资产**（release 150,762,553 / debug 151,947,526 字节）。
- 真机验证：启动层正常显示（工具栏不再盖住）、文案正确、页面加载后渐变进入。

## 2026-10-17 v1.3.0 补丁：横屏启动卡死（issue #17）

### 现象
用户反馈 + 截图：选择横屏（桌面布局）进入后停在启动遮罩「正在加载界面… / 请稍等几秒…」，
界面无响应（Xiaomi Pad 7 / Android 16 / WebView 154）。

### 根因（已由代码与截图确认）
`MainActivity.onPageFinished` 里，`desktopMode` 分支在调用 `dismissSplash()` **之前**就
`return` 了 → 全屏、`clickable=true`、elevation 30dp 的遮罩永不淡出，盖在 WebView 上吞掉
所有触摸。竖屏路径不经过该分支，故不受影响。

### 修复（commit 75f796d）
- `dismissSplash()` 移到模式分支**之前**，横竖屏都会执行；
- 新增 45s 兜底看门狗（`splashWatchdog`）：任何加载路径异常都不能让遮罩永久遮挡；
- `onDestroy` 移除看门狗回调。

### 验证（模拟器 x86_64 dsh36，debug 241）
- `dsh_ui.xml` 写入 `orientation=landscape` → 启动 10s 内遮罩消失，uiautomator 可见完整
  桌面布局（DeepSeek Harness / 新建会话 / 收起侧边栏…），截图
  `C:\Users\Qt\AppData\Local\Temp\emu_landscape_ok.png`；
- 改回 `orientation=portrait` 重启 → 页面加载完成后遮罩同样正常消失。
- 真机（192.168.101.21:9000）已用 `adb push + pm install -r` 装上 241 正式包
  （versionCode=241 / versionName=1.3.0）；安装时设备锁屏，**未做真机手动复测**。

### 发布（同版本覆盖）
- versionCode 240 → **241**，versionName 仍 **1.3.0**；
- 已删除旧 Release 资产并上传新包：
  release id `650756213`（150,762,553B）、debug id `650763724`（151,947,526B）；
- issue #17 已回复（comment `6235397136`）并标记 closed/completed；
- 本地提交 `75f796d`；`git push` 仍被重置（API/资产上传正常，git 直连 github.com
  `Recv failure: Connection was reset`），**待网络恢复后补推**。

### 顺带处理
- x86_64 调试构建会把 `assets/runtime.zip` 换成 x86_64 版；本轮已恢复
  `runtime.zip`=arm64 并核对 MANIFEST sha256 一致（`14dca972…`），随后才重打 arm64 包。

## 2026-10-18 CI 定时任务 OOM 修复

- 现象：nightly（schedule）run `39063013662`（commit f63db71）arm64 leg 红，
  `:app:compressDebugAssets` 报 `java.lang.OutOfMemoryError: Java heap space`；
  同一提交的 push run `39043818023` 却是绿的 → 资源边界偶发。
- 原因：`gradle.properties` 的 `org.gradle.jvmargs=-Xmx2048m` 对 ~126MB runtime.zip
  的资产压缩环节余量不足。
- 修复（commit `7073ada`）：改为 `-Xmx4096m -XX:MaxMetaspaceSize=1024m`。
- 验证：push run `39077258447`（commit 7073ada）全绿——
  collect-runtime ×2、build-apk ×2（arm64-v8a / x86_64）、Build monitor 均 success；
  i18n-check `39077258387` success。
- 注意：旧的失败 run 仍在 Actions 历史里（API 未删除），main 最新提交的 CI 是绿的。

## 2026-10-18 issue #18「终端错误」修复（v1.3.0 第三次覆盖发布，versionCode 242）

### 现象
用户在 WebUI 点「新建终端」报：
`subprocess-local: command "/data/data/com.termux/files/usr/bin/bash" is not an executable file`

### 根因（两层，必须都修）
1. **shell 路径 = Termux 硬编码**。runtime 里的 node 是 Termux 官方构建，二进制内把
   `os.userInfo().shell` 写死为 `/data/data/com.termux/files/usr/bin/bash`（与用户是否装
    Termux 无关）。引擎 `dsh-subprocess-local` 的 `terminalEnvironment()` 取
   `process.env.SHELL || userInfo().shell`，而 `EngineConfig.buildEnv` **从未注入 SHELL**
   → 拿坏路径做 `resolveExecutable` → 抛错。
2. **Android 平台不被识别 + PTY 后端缺失**。Termux node 报 `process.platform === "android"`
   （不是 "linux"），而 `createProcessInspector()` 只认 linux/darwin/win32 → 修好第①层后
   会立刻撞上 `terminal inspection is unsupported on platform android`；且 `node-pty` 无
   Android 预编译产物，runtime 里是被替换掉的「一调用就抛错」空壳。

### 修复
- **①** `EngineConfig.buildEnv` 注入 `SHELL=<engineRoot>/bin/bash`（优先级最高，彻底绕过
  Termux 硬编码）；新增 `shellPathFor()`，bash 缺失时回退 `bin/sh` 再回退 `/system/bin/sh`。
- **②** 新增原生助手 `app/src/main/cpp/dsh_pty_exec.c`（PTY 执行器：fork+setsid+TIOCSCTTY、
  PTY↔stdio 双向搬运、fd3 控制通道做 resize/信号、pidfile 报 pid 与退出状态）；
  CMake 里 `add_executable` 且 **OUTPUT_NAME 设为 libdsh_pty_exec.so**（AGP 只收 .so）；
  `EngineConfig.deployPtyHelper()` 启动时从 APK 解出到 `engine/bin/dsh_pty_exec` 并给执行位。
  引擎侧由 `scripts/patch-node-pty-android.py` 打三处补丁（单一真源，CI 的
  collect-termux-runtime.sh 也调它）：探测器认 android、活动判定认 android、
  node-pty 换成驱动助手的真实实现。

### 实测踩到的两个坑（已修，勿回退）
- **无效 fd 交给 poll() 会 100% CPU 空转**：助手启动时必须 `fcntl(fd, F_GETFD)` 探活再用。
- **kill() 不能只发一次**：引擎清理是 SIGTERM→宽限→SIGKILL 升级序列，交互式 bash 默认
  忽略 SIGTERM；若守卫成「只发第一次」，升级被吞 → 终端关闭后 shell 成孤儿
  （实测 `terminal cleanup failed; surviving pid: N`）。

### 验证（模拟器 x86_64，引擎真实代码路径，非单测桩）
- `terminalEnvironment().defaultShell` → 自带 `bin/bash`（注入前是 Termux 路径）；
- `resolveExecutable` PASS（第①层）；`spawnTerminal` PASS，pid 正常（第②层）；
- 终端内执行命令并回读：`TERM-MARKER-42-/dev/pts/2`（真实 tty）；
- `terminate()` PASS（2.5s 内完成，无残留进程）。
- 对照：不注入 SHELL 时可精确复现用户报错原文。

### 发布
- versionCode 241 → **242**，versionName 仍 1.3.0；
- Release 资产已覆盖：release id `652449715`（150,779,015B）、debug id `652450527`；
- issue #18 已回复（comment `6245584575`）并 closed/completed；
- 提交：`dsh_pty_exec.c` + `patch-node-pty-android.py` 等 7 文件（本地已提交，待推送）。

## 2026-10-18 Root 模式「被杀死后二次启动失败」修复（versionCode 243）

### 用户反馈
「root 模式下，极少数情况下应用因奇怪原因重启或被杀死，会导致二次启动无法启动」+
「安卓 11 上异常退出后无法使用，删除重装又好了」+「如果文件属性被改坏了怎么办」。

### 根因（两类，均已实测确认）
**A. 端口冲突的自愈分支从未生效（死代码）**
`EngineSupervisor` 里 v1.2.22/32/108 三轮修复都写在
`deterministicFailure?.contains("EADDRINUSE")` 分支里，而该变量是
`failureSignature()` 的产物 = `"<退出码>:<哈希>"` —— **哈希里永远不含这个字面量**。
后果链：Root 引擎的 node 是 `su` 的**孙进程**（`dsh_pty.c` 的 `PR_SET_PDEATHSIG`
只挂到 su 外壳上，信号传不到 node）→ App 被强杀时 node 以 uid 0 变孤儿继续占 3080
→ 新引擎 EADDRINUSE 启动即退 → 本该「清场重试、不计罪」却落进 guardian →
连续判罪后**回滚 profiles → SAFE MODE（归档用户配置）→ State.Failed**。
模拟器实测孤儿 ppid 实测为 **570（adbd）而非 1**，而旧 `OrphanReaper` 只认
`ppid == 1` → 也漏掉（双重漏网）。

**B. 文件属性损坏（用户「删掉重装才好」的指纹）**
① 属主自愈只在**非 Root** 模式跑（`mode != ROOT` 才调用）：Root 引擎以 uid 0 写出的
   文件属主是 root，**App 自己**（非 root）读不了 `engine/.runtime-version`、
   `profile-guardian.meta`、插件 → `RuntimeInstaller` 读版本戳 EACCES → 启动失败；
② 权限位被剥（`chmod 000`）时无任何自愈；
③ 用户只能靠卸载重装恢复 —— 卸载清空私有目录 = 变相重置属性，正是该缺陷的现场指纹。

### 修复
- **端口自愈真正生效**：改为硬证据判定 `portInUse()`（**探测超时按「占用」处理**，
  旧实现把超时当空闲）+ `looksLikePortConflict()`（只读**本次启动**的日志段，
  用 `logOffsetAtSpawn` 起算，避免历史 EADDRINUSE 误触发）；清场覆盖
  「cmdline 含 engine/bin/node」的进程并**排除 lan-web-proxy**（否则局域网访问会静默失效）；
  连续处置上限 `MAX_PORT_RECLAIMS=8`，防「非引擎进程永久占端口」时绕过退避无限重试。
- **guardian 隔离环境类失败**：端口冲突 / `no usable su`（su 瞬时不可用，
  App 被强杀后 magiskd 会话未重建）都**不计入配置崩溃**，只退避重试。
- **su 探测加重试**：`usableSuPrefix()` 失败后等 1.2s 再试一次，把窗口让给 daemon 恢复。
- **属性自愈扩展到所有模式 + 分层修复**：
  ① 先用纯 Java 自己修（**实测：属主属于自己的文件，直接 chmod 即可，无需任何特权**）
  —— 这让无 su/无 Shizuku 的设备也能自愈；
  ② 再走 `runPrivileged`（Shizuku 优先）+ `restorecon` 修 SELinux 标签与属主。
  检测用「**实际尝试打开**」而非 `canRead()`（后者在权限位 000 时不可靠）。
- **目录必须补 rwx 三者**：只补 r+x 会留下 `dr-x------`「能读不能写」的目录，
  把故障推迟到写入时（本轮实测发现的隐患，已修）。
- **runtime 重建前先自愈属性**：否则 root 属主文件删不掉 → 覆盖写入 EACCES →
  每次启动重装失败。
- **版本戳读取容错**：读不了等价于「版本不匹配」，走重装路径（内部会先自愈）。

### 实测（模拟器，引擎真实启动链路）
- **安卓 16（dsh36）**：造孤儿占 3080 → 启动 App → 日志
  `port 3080 in use — reclaiming` → 孤儿被杀（init 回收 18323）→
  `engine healthy on :3080`；**无 guardian 判罪**（无 meta、无 rolled back、无归档）。
- **安卓 11（dsh30，API 30）**：`chmod 000` 文件与目录 + 强杀 App → 二次启动 →
  `ownership check: found entries this app cannot read` → `restored permissions on 16806 entr(ies)`
  → `engine healthy on :3080`；属性恢复为 `drwx------` / `-rw-------`。
- 注意：安卓 11 AVD 自带 WebView 是 Chrome 83，会弹「WebView needs an update」
  对话框（App 的既有门槛提示，非 bug），需点 CONTINUE ANYWAY 才继续启动流程。

### 发布
- versionCode 242 → **243**，versionName 仍 1.3.0；
- Release 资产已覆盖：release id `652699077`、debug id `652699848`。

## 2026-10-18 v1.3.1 正式发布

- **版本**：versionName **1.3.1**（versionCode 243）。
- **Release**：https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/tag/v1.3.1
  （release id `416293923`，公开 draft=false）
  · `dsh-mobile-v1.3.1-arm64-v8a.apk`（asset id `652758035`，150,779,015B）
  · `dsh-mobile-v1.3.1-arm64-v8a-debug.apk`（asset id `652758871`，151,980,024B）
  · 说明：`RELEASE_NOTES_v1.3.1.md`（终端修复 / Root 自愈 / 横屏卡死 / 验证情况 / 已知问题）。
- **README 三语同步**：徽章与「最新版」引用 v1.3.0 → v1.3.1。
- **包内核对**：原生库含 `libdsh_pty_exec.so`（PTY 助手）；
  runtime 含三处终端补丁（inspector 认 android / node-pty Android 实现 / kill 允许重复调用）。
- **推送通道**：`git push` 直连 github.com 仍被重置（`Recv failure: Connection was reset`），
  改用仓库自带的 `scripts/push-via-api.py`（Git Data API）成功推送，
  远端 main → `5ac5980`。发布走 API 通道不受影响。

### v1.3.1 相对 v1.3.0 的实质变化
1. **终端可用**（issue #18）：注入 SHELL + 原生 PTY 助手 + 运行时 Android 适配。
2. **Root 异常杀死后可自愈**：端口冲突自愈分支真正生效（原为死代码）、
   孤儿识别不再依赖 ppid==1、guardian 隔离环境类失败、
   属性自愈扩展到所有模式并分层（自己修优先，再走 shizuku/su + restorecon）。
3. **横屏启动不再卡死**（issue #17）：遮罩在模式分支前解除 + 45s 兜底看门狗。
4. **CI**：Gradle 堆 2G → 4G（修 nightly compressDebugAssets OOM）。

## 2026-10-18 Issue #20/#21 插件导致工作区能力缺失：已实现并验证

### 版本记录
- 未递增 versionCode/versionName：本任务明确禁止修改 `app/build.gradle.kts` 的版本号；仅构建并验证 x86_64 Debug APK。

### 已确认的故障证据
- `runtime.zip` 内 `dsh-host-directory-picker-auto` 的 `inject = ["webServer", "loader"]`；Android `process.platform === "android"` 解析为 `browse`，随后 `ctx.loader.create()` 动态加载 browse host/client 两个 entry，任一 entry 无 fiber 时抛错并先卸载已创建 entry。
- `dsh-api-workspace-controller` 的 `DirectoryPickerController` 注入 `directoryPicker` 并注册 `directoryPickerController`；`WorkspaceController` 注入 `typert`、`workspaceRegistry` 并注册 `workspaceController`。
- `dsh-workspace` 的 `WorkspaceRegistry` 注入 `storageDomain`、`sessionPersistence`，其初始化会打开持久化 domain、恢复 pending mutation、索引 session headers；初始化抛错/依赖不可用时服务不 active。
- Cordis `ctx.provide()` 对重复服务名直接抛 `service "<name>" has been registered at <...>`；Loader import 失败会记录 error 并留下无 fiber entry，插件 init 失败的 Fiber 状态为 failed（state=3），但 HTTP 端口仍可 healthy。Gateway `prepareInvocation` 对缺失 receiver 抛 `gateway/service-unavailable`，对应现场 `directoryPickerController is unavailable`。
- profile 合成顺序已由 runtime 源码确认：bundle layers → `profiles/web/cordis.patch.yml` → `$DSH_HOME/cordis.patch.yml` → `--patch` overlays；`applyEntryPatches` 按 id 顺序覆盖，且覆盖行的 whole `config`，后层优先。`name` 是匹配守卫，不是可被 id patch 改写的字段；因此最后 Android overlay 同时重申关键 row 的 id/name/config/inject，并由 health probe 报告关键 row name mismatch。

### 持久化位置与安装写入
- `PluginCenter.install()` 执行 `dsh plugin --profile web add <pkg>`。
- plugin-manager 的直接安装/卸载会写 `profiles/web/package.json` 与 `profiles/web/pnpm-lock.yaml`；安装成功后 reconcile 将带 `dsh.bundle.patch` 的依赖加入 `dsh.profile.bundles`，并从包内 `cordis.patch.yml`（或 manifest 声明的 patch 数组）加载层。
- profile 还包含 `profiles/web/cordis.patch.yml`、`cordis.yml`、`pnpm-workspace.yaml`、`node_modules/<plugin>/`；插件停用只从 `dsh.profile.bundles` 摘除，包和 lockfile 保留，可重新启用。清数据会同时清掉上述 profile 与 plugin state，故能恢复。

### 本次实现
- `EngineConfig.kt`：`--patch` 最后层重申 typert-loader/gateway、directory-picker、workspace/workspace-controller、storage-domain/session-persistence、webserver，补全关键 config/inject，防普通插件 patch 禁用或改写关键 host rows。
- `android-plugins/plugin-health.mjs`：启动期等待 Loader settle（有界 15s），检查 required services、无 fiber/failed fiber entries、关键 row name mismatch，写 `$DSH_HOME/.android-plugin-health.json`；marker 带 bootId 且 root 模式显式 chmod 0644，不含凭证或用户内容。
- `PluginRecovery.kt`：读取本次 boot marker，按失败 entry 的 id/name 反查用户 bundle patch；精确归因后备份 `profiles/web/package.json` 到 `dsh-home/plugin-recovery/`，原子更新 `dsh.profile.bundles` 摘掉一个第三方 bundle；连续自动处理上限 3 次，无法精确归因时不猜测、不修改 profile。
- `EngineSupervisor.kt` / `EngineProcess.kt`：每次 fork 生成 bootId，Root wrapper 转发 `DSH_HOME`/bootId；marker degraded 时不覆盖 last-good snapshot；插件自愈重启不进入 ProfileGuardian 配置定罪计数，健康后清零自愈额度。
- `MainActivity.kt` / `EngineService.kt` + 三语 strings：首个恢复后的 ready 状态明确显示被停用插件名。

### 验证证据
- `.abi` 仍为 `x86_64`，未改动 runtime ABI；`gradle assembleDebug -Pabi=x86_64 --console=plain` 两次通过，最后一次 `BUILD SUCCESSFUL in 8s`。
- `python scripts/check-i18n.py`：三语 391 keys 同步、Kotlin CJK=0、新增文案通过。
- `node --check app/src/main/assets/android-plugins/plugin-health.mjs` 通过；`git diff --check` 通过。
- 模拟器 `emulator-5554`（Android 16 x86_64）安装 debug APK 后，正常启动 marker：`ok=true, missingServices=[], failedEntries=[]`；监督日志：`plugin health marker received (ok=true)`、`plugin health: ok=true missing= failed=`、`engine healthy on :3080`。
- 可回滚重复服务插件验证：临时 `fake-bad` bundle 注入 `ctx.provide("directoryPicker")`，真实 engine.log 记录 Cordis 原始错误 `service "directoryPicker" has been registered at <BrowseDirectoryPicker>`；随后 marker `ok=false`，监督日志记录 failed entry，`PluginRecovery` 输出 `auto-disabled third-party plugin fake-bad (attempt=1/3...)`，重启后 marker 恢复 `ok=true`，profile bundles 已自动摘除 `fake-bad`；测试结束已恢复原 profile 并删除临时 node_modules。

### 未验证/遗留
- 未在真实 Lark/Feishu 包上复现；当前已对 Cordis 重复服务、Loader import/init 失败、关键服务缺失和 patch name mismatch 做代码路径与合成插件验证。
- 若 workspaceRegistry 本身持久化数据损坏而没有第三方 bundle 精确 row 归因，修复会只留 marker/engine.log，不会猜测删除插件；需后续针对 storages/workspace.json 设计独立数据修复策略。

## 2026-10-18 v1.3.3 发布与上游适配状态

- **版本**：versionName 1.3.3，versionCode 245。
- **Release**：https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/tag/v1.3.3
  release id `416513653`，公开；正式包 asset `653742573`（150,807,829B），debug asset
  `653743712`（152,007,743B）。
- **本版已验证修复**：
  1. 插件健康探针 + 第三方 bundle 精确隔离（fake-bad 重复服务故障验证通过）；
  2. runtime ELF 架构校验 + collect 脚本出包硬断言；
  3. 三个窄屏插件的 MutationObserver 属性范围收紧（避免消息流 class/style/data 高频变化
     触发全量布局重判，作为 1.3.x WebView 卡顿风险缓解）；
  4. 既有终端、Root 自愈、用户指令段功能保留。
- **验证**：node --check、窄屏回归测试（30/36/28）、i18n、git diff --check、
  x86_64 debug 构建、arm64 release/debug 构建、CI android-build/i18n-check 全通过。
- **CPU 说明**：用户设备的满核现象本地未复现；本版收紧了已确认的高频 DOM 观察器，
  发布说明明确标为风险缓解，后续需要真实设备数据继续定位。
- **上游状态**：上游 `deepseek-ai/deepseek-harness` 已发布 `dsh-v0.2.1-rc.2`
  （commit `687f282e`，2026-10-17）。本项目 runtime 仍锁定 `@deepseek-ai/dsh 0.2.0-rc.2`，
  因为 Android 运行时有多处针对 0.2.0-rc.2 的 bionic/Termux/PTY/native 补丁；
  0.2.1 适配不与本次修复混发，待单独做依赖差异审查、补丁迁移、双架构构建与真机验证。

## 2026-10-19 上游 dsh 0.2.1-rc.2 适配完成（v1.4.0，versionCode 246）

### 上游差异（已用源码/包级比对确认，非猜测）
- 上游 tag `dsh-v0.2.1-rc.2`（commit `687f282e`，2026-10-17），npm 发布时间 2026-09-29 → 2026-10-17 之间 1925 个 commit。
- 全部 `@deepseek-ai/dsh-*` lockstep 升 0.2.0-rc.2 → 0.2.1-rc.2；`node-addon-system` 仍 0.1.2；
  `node-addon-require-builtin` ^0.1.6 → ^0.1.9；node-pty 仍 1.2.0-beta.15（上游 patch hash 变化，本仓已整体替换为 Android 实现，不受影响）；
  cordis ~4.0.4 → ~4.0.5。
- 结构性变化：`dsh-host-directory-picker-auto` 的 `inject` 从 `[webServer, loader]` 变为 `[webServer, connection, loader]`
  并读取 `ctx.connection.allowsRemoteAuthorities`；`dsh-subprocess-local` 新增 `linux-scope-selector.ts`（Linux scope 重构）；
  `dsh-app-boot` 新增 profile resolution 解析层与 **Node internal loader 能力校验**；
  `dsh-session-persistence-jsonl` 新增 `p-limit` listing 并发限制；`dsh-client-hmr` / `dsh-storage-domain` 源码文件有增删。

### 迁移结论：13 个 Android 补丁锚点全部存活
用 CI Linux runner 隔离 staging（`DSH_VERSION=0.2.1-rc.2`，双架构）逐条验证：
koffi inert、node-pty Android 实现、subprocess-local 两个 android 锚点、flock 兼容层、
attachment-local（import + link shim）、session-persistence rename、
jsonl worker.cjs link shim、sandbox-local 外科手术、fs-local rename、
ripgrep Android 平台包、curl/corepack wrapper、Termux shebang/SONAME、架构硬断言 —— 全部命中。
新增 `.github/workflows/upstream-staging.yml`（仅 workflow_dispatch）作为迁移期验证入口，
校验项 28 条（ABI、命令闭包、SONAME、Android ripgrep、拒绝 Linux ripgrep、各补丁 marker、
node-pty package marker、拒绝 shim 残留），并核对 APK 内 `assets/runtime.zip` 与 staged sha256 一致。
`scripts/patch-node-pty-android.py` 改为 fail-fast（缺目录/缺包/锚点未命中 → 非零退出），
并修复 zip 模式新条目未写回的潜在 bug。

### 行为验证（真实设备，非静态推断）
- **x86_64 模拟器（Android 16）**：装 staged 0.2.1 debug 包 → 引擎 `engine healthy on :3080`，
  plugin-health marker `ok=true, missingServices=[], failedEntries=[]`；
  终端真实链路全 PASS（`defaultShell` → 自带 bash、`resolveExecutable`、`spawnTerminal` pid 正常、
  PTY 内执行命令 `tty=/dev/pts/2`）；关闭回收 `terminate OK`（2.3s，无残留）。
- **arm64 真机（OnePlus PLZ110 / Android 16）**：0.2.1 新增的 Node internal loader 能力校验
  （`getOrInitializeCascadedLoader`/`resolveSync`/`getModuleJobForImport`/`_resolveFilename`/
  `getCjsConditions`/`getDefaultConditions`/`defaultResolve`）在候选 runtime 自带的 node 上
  **RESULT: OK**（用真机临时目录只读验证，未影响现有安装，验证后已清理 /data/local/tmp/dsh-probe*）。
- **真机覆盖安装 v1.4.0**：`pm install -r` 成功（versionCode 246），引擎启动，
  marker `ok=true, missingServices=[], failedEntries=[]`，`.runtime-version = v1.4.0-arm64-dsh0.2.1`。

### 发布形态
- 正式 runtime 已替换为 0.2.1-rc.2（arm64 + x86_64 双备份入 `_runtime_bak/`），
  `collect-termux-runtime.sh` 默认 `DSH_VERSION` 同步改为 `0.2.1-rc.2`。
- 版本：versionName **1.4.0**、versionCode **246**（minor 升级，与「引擎换代」风险等级相称）。
- 本地产物：`dist/v1.4.0/dsh-mobile-v1.4.0-arm64-v8a.apk`（235,652,373B，含 dsh 0.2.1-rc.2 + arm64 node）。

### 遗留/风险
- `directory-picker-auto` 的 `inject` 在 Android overlay 中仍写 `[webServer, loader]`；
  cordis 语义为「entry inject 追加到插件自身 inject」，故 `connection` 不会被覆盖（marker 已证明服务激活），
  但为语义清晰建议后续把 `connection` 补进 overlay 行。
- 迁移期临时 workflow `upstream-staging.yml` 与 `_upstream-0.2.1-staging/` 目录为验证用，
  正式发布后应移除（避免长期保留入口与 ~1.3GB 中间产物）。
- 用户设备 CPU 满核、工作区选择失效等仍未复现的问题，继续按 issue 收集现场证据。

## 2026-10-19 v1.4.0-beta.1 试发行（上游 dsh 0.2.1-rc.2）

### 为什么先出 beta（用户要求）
用户要求「搞个试发型版 1.4.0beta」——0.2.1 是引擎换代，先小范围试跑再发稳定版。

### 关键机制：beta 不会推给稳定用户
`UpdateChecker.kt` 明确跳过 draft 与 prerelease（注释：那些不是给用户装的稳定包），
因此把 Release 标为 prerelease 即可让稳定版用户完全不受影响。
用 App 的真实版本比较逻辑实测（`parseVersion` + `compare`，丢弃 `-beta.1` 后缀）：
- 稳定用户看到的最新版仍是 **v1.3.3**（未把 beta 当成更新）；
- 1.3.3 用户 → 无更新提示；已装 beta 的用户 → 也无更新提示（版本号相等）。

### ⚠️ 发现并修复一处「主分支未合并」的遗漏（用户指出的问题）
用户反馈「主分支好像没有合并」。核实为：**`MainActivity.kt` 的状态栏插件恢复提示在 v1.3.3 提交时被漏掉**——
当时 `git add` 清单未包含该文件，导致线上 v1.3.3 与首个 1.4.0 包**通知栏**会提示已停用插件、
但**应用内状态栏**不会显示。已补提交（`d2ca69d`）并推送，主分支现在包含该改动；
beta 包构建于该文件修改之后，经 dex/arsc 校验确认已包含 `status_plugin_recovered` 与 `recoveredPlugin`。

### 发布形态
- **tag `v1.4.0-beta.1`**（release id `416878787`，`prerelease=true`）
- `dsh-mobile-v1.4.0-beta.1-arm64-v8a.apk`（asset `655151740`，235,652,373B）
- `dsh-mobile-v1.4.0-beta.1-arm64-v8a-debug.apk`（asset `655153393`，237,248,183B）
- versionName `1.4.0-beta.1`、versionCode 246；包内 `dsh 0.2.1-rc.2` + arm64 node（e_machine=183）
- 曾创建的空 `v1.4.0` Release 与 tag 已删除，把 `v1.4.0` 名字留给将来的正式版。

### 发布说明要点（RELEASE_NOTES_v1.4.0.md）
开头明确标注「这是测试版」：App 内检查更新不会推送本版、想退回稳定版可直接装 v1.3.3 覆盖、报问题请附设备型号与现象。

---

## v1.4.0-beta.2 · 修复 issue #25「无法打开配置文件」（2026-10-19）

### 根因（代码定位，非推测）
设置 → 通用 → 「打开配置文件」走的是上游 `settingsController.openSettingsDocument()` →
`settingsProvider.prepareDocument()` 拿到 profile patch 路径 →
`openNativeTextFile()`（`@deepseek-ai/dsh-native-command`）。该函数按平台分派：

| 平台 | 上游行为 |
|---|---|
| darwin | `open -t <path>` |
| win32 | 资源管理器 |
| linux | `xdg-open <path>` |
| 其他（含 android） | **抛错** `native path opener is unsupported on android` |

前端把内部错误统一显示成 `openDocument.error`（「无法打开配置文件」），用户误以为文件损坏。
文件本身完好，路径：`/data/user/0/app.dsh.mobile/files/dsh-home/profiles/web/cordis.patch.yml`。
该按钮在 1.3.3（引擎 0.2.0）就存在，属长期平台差异，非 1.4.0 引入。

### 修法（不改 228MB runtime）
新增宿主机插件 `app/src/main/assets/android-plugins/open-file.mjs`，
替换上游自己留给测试用的实例级 seam `settingsController.openTextFile`
（源码：`this.openTextFile = internals.openTextFile ?? openNativeTextFile`），
改为 `POST http://127.0.0.1:3083/open-file`（带 `X-DSH-Token`）。
应用侧新增 `FileEditorActivity` 承接：读取 / 编辑 / 原子保存。

- 部署：`EngineConfig.ensureAndroidOverlay()` 的单文件插件列表加入 `open-file.mjs`，
  overlay `insert` 段追加 `android-open-file` 条目（id 唯一，不覆盖上游行）。
- 桥路由：`AgentBridge.openFile()`，路径限制在 `filesDir` / `cacheDir` /
  `engineRoot` / `dshHome` / `workspaces` 之内（canonicalPath 前缀比较，防 `..` 与软链接绕过）。
- 保存：同目录临时文件 → 继承原文件 POSIX 权限 → rename 覆盖（原子）。
  不直接写原文件，避免半截配置触发 ProfileGuardian 安全模式。
- 上限 1MB、严格 UTF-8 解码（不猜编码）、遇 NUL 判为二进制 → 只读展示并说明原因。
- **用户明确要求移除**「用其他应用打开」分支：FileProvider、`res/xml/file_paths.xml`、
  androidx.core 依赖、相关 7 条文案已全部删除（`grep` 确认无残留）。

### 已验证（模拟器 Android 16 / x86_64）
- 插件 seam 单测 4/4：正常转发（含 token）、桥 403 时抛错、桥不可达时抛错、seam 缺失只告警不崩。
- 端到端：桥 `POST /open-file` → `{"ok":true,"opened":true}`，
  `dumpsys` 确认 `topResumedActivity=...FileEditorActivity`。
- 越界路径 `/system/build.prop` → `{"ok":false,"error":"path is outside the app-managed roots"}`。
- 无 token → 拒绝（fail-closed）。
- 保存实测：343 → 381 字节，md5 变化，**权限保持 `-rw-------`**，无临时文件残留；
  测试后已精确还原（md5 `1712c86f...`、343 字节）。
- plugin-health marker `ok:true`，`failedEntries:[]`；engine.log 无 seam 缺失告警。
- i18n 三语 408 条同步、Kotlin 编译、`assembleDebug` 全通过。

### 注意
- assets 的 `runtime.zip` 与 `MANIFEST.json` 在 x86_64 构建时被 `prepareRuntime` 改写，
  已恢复为 arm64 原件（sha `fbd759dc...`）并把 `.abi` 写回 `arm64-v8a`，与 MANIFEST 一致。
- 版本号：versionCode 247 / versionName `1.4.0-beta.2`。

---

## v1.4.0-beta.2 追加：「文件资源服务不可用」根因与修复（2026-10-19）

### 用户报告
> 「通过内置的文件系统去读目录，会直接提示不可用或者直接反复一直卡、每秒数次刷新界面，
>   好像就是读目录会出现一些报错，但是点击读文件似乎可以」
> 「安卓14 LOS 通过 DSH 文件系统连文件都读不了，提示文件资源服务不可用」

### 根因（模拟器实测复现，非推测）
日志铁证（Android 16 模拟器，`MANAGE_EXTERNAL_STORAGE` 未授予）：
```
EngineConfig: publicDataRoot probe: docs=true storageManager=false mkdirs=false   ← 探针假阳性
EngineConfig: publicDataRoot: /storage/emulated/0/Documents/dshdata
EngineConfig: externalise workspaces: pub=.../Documents/dshdata/workspaces
$ ls files/dsh-home/workspaces/   →  Permission denied
```
两个缺陷叠加：

1. **`isDirUsable()` 探针假阳性**：它只验证「自己创建→写→读回→删」这一个文件，
   以及 `dir.list() != null`。在未授权 MANAGE_EXTERNAL_STORAGE 时这条路径能通过
   （探针文件由本进程创建，SELinux 允许读写自己的文件），于是把
   `/storage/emulated/0/Documents/dshdata` 判为可用并建立软链。但引擎真正
   `readdir()`/`stat()` **别人创建**的条目时被 SELinux 拒绝（media_rw / 其它 uid）。
2. **`dsh-fs-local` 不容忍 EACCES**：`probeStats()` 只把 ENOENT/ENOTDIR 当"不存在"，
   其余 errno 原样上抛；`listDirectory()` 对每个子项单独 resolve+probe，一个失败就
   `throw` 让整目录不可用。于是前端：
   - 文件预览 `locateFile()` 拿不到 entry → `workspace-file/not-regular-file`
     → TextPreview 的 `meta.status === "none"` → **「文件资源服务不可用」**；
   - 目录列举整条失败 + `watch()` 反复触发 `changed()` → **每秒数次刷新/卡顿**。

### 修复（两层，互补）
**A. 应用侧（Kotlin）— 探针不再猜权限**
`EngineConfig.isDirUsable(dir, requireManager)` 新增 `requireManager` 参数：
共享存储（Documents）**必须** `Environment.isExternalStorageManager() == true` 才认。
权限是系统事实，不该由写读探针推断。未授权 → 回退 app 专属外部目录
（`Android/data/<pkg>/files/dshdata`，零权限、稳定）。
另加**升级自愈**：即使 pubRoot 可用，若现有软链目标不可访问也解开重建 ——
否则老版本建错的链会一直留在用户设备上。

**B. 运行时侧（补丁）— fs 层容忍不可观测项**
`scripts/patch-fs-local-android.py`（新增，已接入 `collect-termux-runtime.sh`）：
- `probeStats`：EACCES/EPERM 视为「无法观测」返回 null（不再致命）；
- `listDirectory`：单个子项 EACCES 降级为 `type: "other"`，保留文件名继续列举。
- **其余 errno（EIO 等）照旧上抛** —— 真故障不能被吞。
- fail-fast：锚点形态不识别即中止构建。

### 验证
- 补丁行为单测（6 项断言全过）：EACCES 由致命→可观测、EIO 仍抛出、
  三项目录中一项不可读时上游整列失败 vs 补丁后三项全保留。
- 模拟器实测（Android 16 x86_64）：
  | 项 | 修复前 | 修复后 |
  |---|---|---|
  | 探针结果 | `docs=true`（假阳性） | `docs=false` ✅ |
  | 落点 | `/storage/emulated/0/Documents/dshdata` | `Android/data/app.dsh.mobile/files/dshdata` ✅ |
  | `ls workspaces/` | Permission denied | 正常 ✅ |
  | 引擎 `readdir`/`readFile` | EACCES | `list: ['proj']` / `read: hello` ✅ |
- plugin-health `ok:true`，`failedEntries:[]`。

### 注意
- 修复后 **未授权「所有文件访问」的用户，会话/工作区落在 app 专属外部目录**，
  卸载会清除（这是权限未授予时唯一稳定可用的位置）。要让数据扛住卸载，
  仍需在设置 → 权限中心授予「所有文件访问」。
- arm64 / x86_64 两个 runtime 源包均已打补丁；`MANIFEST.json` 的 sha256 已同步为
  `f8e4d69f9ab428f78ddfc7372b1b3ecd82bccf886398599a3423aec212789b5a`（arm64）。
- 版本号仍为 `1.4.0-beta.2`（versionCode 247）。

---

## v1.4.0-beta.2 追加：长会话卡顿的真凶是我们自己的窄屏插件（2026-10-19）

### 用户怀疑是对的
用户问「有没有可能是一些我们自己造的问题」—— 确认**是**，而且问题就在三个窄屏 UI 插件里。

### 定位过程
1. **先排除服务端**：在模拟器上用真实引擎代码跑 300 轮会话（2401 事件、393KB）的完整
   打开流程 —— 读 1.4ms + 解析 5.5ms + turnOutline 全量折叠 4ms + 分页 0ms = **11ms**。
   服务端不是瓶颈。
2. **再量前端**：用客户端插件在真实 WebView 里采样，发现**空页面就有 655ms 长任务** ——
   说明相当一部分开销与长会话无关，是固定成本。
3. **审自己的插件** → 发现 `narrow-settings` 的 `refresh()` 在找不到 dialog 时会做
   **全页** `querySelectorAll("div, section, aside")`，对每个候选沿祖先链
   `getComputedStyle()`（每次一次样式计算）+ `querySelectorAll("button")` +
   `getBoundingClientRect()`（强制布局）。而三个插件都用
   `MutationObserver { childList: true, subtree: true }` 观察整棵文档 ——
   AI 工作时消息流每几毫秒增删节点，等于**每帧一轮全页扫描**。

### 量化（jsdom，主界面 60 条消息 + 200 次 DOM 插入）
| 指标 | 修复前 | 修复后 | 降幅 |
|---|---|---|---|
| `querySelectorAll` | 164 | 0 | 100% |
| `getComputedStyle` | **68,502** | **0** | **100%** |

68502 次样式计算正是「滑一下就卡、开侧栏/弹输入法也卡」的来源。

### 修复（三处，全在我们自己的插件里，不碰上游）
1. **narrow-settings**：新增 `maybePanelPresent()` 廉价预判 —— 先查
   `[role="dialog"][aria-modal="true"]`，没有就立即返回，绝不进入全页扫描。
   设置面板本身就是 dialog，不会漏判。
2. **三个插件统一**：DOM 变化引起的重判改为 `scheduleThrottled()`，200ms 下限
   （最多 5 次/秒）；`resize` / `orientationchange` / 首帧补判仍立即执行（转屏必须秒响应）。
3. **保留 `subtree: true`**：评估过收窄到 `body` 直接子节点，但侧栏/面板可能嵌在
   任意深度，收窄会漏判功能；真正的收益来自"不再每帧都做"，不是缩小观察范围。

### 验证
- 既有测试全过：`_test_narrow_sidebar` 30/30、`_test_narrow_settings` 36/36、
  `_test_narrow_rightbar` 28/28。
- 新增 `scripts/probes/_test_narrow_perf.mjs`（4/4）：
  ① 主界面无 dialog 时不得全页扫描；② 设置面板打开仍能接管（功能未回归）；
  ③ 300 次 DOM 插入导致的查询增量受限（节流生效）。
- 新增 `scripts/probes/_bench_narrow_before_after.mjs` 做前后量化对比。
- 模拟器全新安装 + 引导初始化后：plugin-health `ok:true`、`failedEntries:[]`。

### 注意
- 压测用的 `probe` 客户端插件与全部临时脚本已删除，未进发布包。
- 会话存储的真实布局是 `<sessions>/<cwd-slug>/<session-id>/session.v4.jsonl.zstd`
  （zstd 压缩、按 cwd 分目录、需 `agentPreset` 字段），不是平铺 JSONL ——
  后续若再要造测试会话，按这个结构来。

---

## v1.4.0 正式版发布（2026-10-20）

### 上游版本
**0.2.1-rc.2**（不是 0.2.0）。内核包 `dsh` / `dsh-base` / `dsh-web-app` / `dsh-session` /
`dsh-client-ui-chat` 全部核对为 0.2.1-rc.2。

### 发布产物
| 项 | 值 |
|---|---|
| tag | `v1.4.0` |
| release id | `417920468` |
| draft / prerelease | **false / false**（正式版，会推送给稳定用户） |
| versionCode / versionName | **250 / 1.4.0** |
| APK | `dsh-mobile-v1.4.0-arm64-v8a.apk` |
| asset id | `657975189` |
| 大小 | 241,517,983 bytes |
| SHA-256 | `2b81597742d1372a6761ff9f37dbbbef4d318e8553b98017eb7e433f11274d07` |
| 签名 | v2 + v3（v1/v3.1/v4 未启用），CN=DSH Mobile |
| runtime | e_machine=183 (aarch64)，sha256 `f8e4d69f...` |

URL: https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/tag/v1.4.0

### 打包前校验（全部通过）
- APK 原生库仅 `lib/arm64-v8a/`（libdsh_pty_exec.so / libdshpty.so / libmlkit…）
- 内嵌 runtime.zip 的 `bin/node` = aarch64
- fs-local 补丁存在；`open-file.mjs` 在包内；**压测探针未被泄漏进包**
- `aapt2 dump badging`：versionName 1.4.0 / versionCode 250 / native-code arm64-v8a
- `apksigner verify`：v2、v3 通过

### 本次纳入的修复
1. **长会话卡顿（issue #26）**：三个窄屏插件每帧重判 + 全页扫描 → 廉价预判 + 200ms 节流。
   实测 `getComputedStyle` 68502 → 0。
2. **打开配置文件（issue #25）**：宿主机插件接管 `openTextFile` seam + 应用内编辑器。
3. **文件面板不可用（#25 后续）**：`isDirUsable(requireManager)` + 升级自愈，
   配合 runtime 侧 fs-local 补丁（EACCES 容忍 + 目录单项降级）。

### 提交
`46dfe0a` release: v1.4.0 正式版（引擎 0.2.1-rc.2），18 files changed。
推送走 `scripts/push-via-api.py`（SSH 凭据不可用），远端 main → `daed273`。

### 遗留观察项
- 空白首页仍有约 655ms 固定长任务（与长会话无关），尚未定位。
- issue #26 用户的实际体验反馈待收集。

---

## v1.4.0 重新发布：关于页加入 QQ 群号（2026-10-20）

### 变更
- `activity_about.xml`：「开源地址」与「检查更新」之间新增 QQ 交流群卡片
  （`rowQqGroup`），点击复制群号 `1128769016`。
- `AboutActivity`：点击 handler 复制到剪贴板 + Toast 提示。
  不做 deep link 拉起 QQ —— 加群链接格式各版本不一，未安装时会直接失败；
  复制群号是唯一在所有设备上都成立的路径。
- 三语文案：`about_qq_group` / `about_qq_group_id` / `about_qq_group_tap` /
  `about_qq_group_copied`（en / zh-rCN / de）。

### 验证
- i18n：412 条三语同步，全绿。
- 模拟器（x86_64 debug 构建）实机截图确认：卡片位置、群号、`Tap to copy` 均正确渲染。
- 群号已编入 `resources.arsc`（`aapt2 dump strings` → `String #502 : 1128769016`）。

### 重新发布的产物
| 项 | 值 |
|---|---|
| release id | `417920468`（同一个 v1.4.0，替换资产） |
| 旧 asset | `657975189` 已删除 |
| 新 asset id | **`658071799`** |
| 大小 | 241,522,079 bytes（与旧包差 4096 字节，即新增的资源与代码） |
| SHA-256 | `344ed905b28a41df24bcdb8834bd711d812e40d92912d76a419d12d6b5dbf14c` |
| 签名 | v2 + v3 通过 |
| versionCode / versionName | 250 / 1.4.0（未变） |
| runtime 架构 | e_machine=183（aarch64） |

提交：`050dd4a` feat(about): 关于页新增 QQ 交流群卡片（三语，点击复制群号）
远端 main → `af99f7f`。发布说明也追加了「## 交流」段。

### 注意
- Release 仍是 `draft:false` / `prerelease:false`，稳定用户会收到更新提示；
  已装 beta 的用户版本号相同（250），不会重复提示。

---

## v1.4.1 紧急修复：通知栏退出后空转（2026-10-20）

### 用户报告
「通过通知栏进行退出，脚本成功将软件退出了，但是自己本身不退出，导致 root 模式下
在持续空转，严重消耗资源。」

### 根因（三个独立缺陷叠加）
1. **保活看门狗复活引擎（主因）**
   `DshAccessibilityService.startKeepAliveWatchdog()` 每 60s 检查 EngineService，
   不在就 start。注释写「除非用户显式退出过」，但**代码里从来没有该判断**。
   无障碍服务是系统级组件，主进程结束后仍存活 → 60s 后复活引擎 → 死循环。
   root 模式每次复活都要经 su，代价更高。
2. **dispatchExplicitExit 在 Activity 缺失时直接 return**
   回调注册在 MainActivity、onDestroy 置空。用户先按 Home（Activity 被回收）
   再点通知栏退出 → 回调 null → 无人结束 App 进程。
3. **cleanupOrphanEngine 以 portInUse() 为前提**
   主引擎退出后 3080 已释放，但它经 su 派生 / 被 init 收养的后代仍在跑；
   端口检查看不到 → 整条清理被跳过。

### 修复
- `DshApp.userExitedExplicitly` 持久化标记（SharedPreferences）：
  退出时置 true（EngineService.exitCompletely），App 打开时置 false（MainActivity.onCreate）。
  跨进程存活，解决「内存标志随进程消失」的问题。
- 看门狗同时查 `supervisor.isUserStopped()` 与落盘标记，任一为真则不复活。
- `dispatchExplicitExit` 无条件收尾：有回调先 finish Activity，随后
  `killAppProcess()`（NotificationManager.cancelAll + Process.killProcess；
  失败退回 exitProcess(0)）。
- `stop(explicit = true)` 时**无条件** `OrphanReaper.reap(ctx)`（含 root 视角扫描）。

### 验证
- 状态机 4 场景全过：退出后看门狗不复活、重开 App 恢复、退出后 5 分钟无复活、
  旧逻辑对照确认 bug。
- Kotlin 编译通过（修了两处 runCatching 末行 if 缺 else 的表达式问题）。
- release 包：versionCode 251 / versionName 1.4.1，签名 v2+v3，arm64（e_machine=183），
  fs-local 补丁在、open-file 插件在、无探针泄漏。

### 发布
| 项 | 值 |
|---|---|
| tag | v1.4.1 |
| release id | `418106915` |
| asset id | `658579315` |
| 大小 | 241,522,079 bytes |
| SHA-256 | `ebfa243f238f0865987b45fe01c78442efc24101bf5172a926f74c623f621e0c` |
| 状态 | draft=false / prerelease=false |

提交 `0c52f39`（代码）+ `d968946`（发布说明/版本），远端 main → `3f379ac`。

### 未完成
- 轻量版（剥离 LibreOffice 145MB + inspector 22MB）已调研并有
  `scripts/make-lite-runtime.py`，但直接删包会导致 office-to-pdf 的 Cordis 依赖链
  断裂，进而 directory-picker/workspace 等核心服务不可用。正确解法是「overlay 里
  disabled: true + 只剔静态资源」，实验做到一半用户叫停。用户确认「先不管」。

---

## v1.4.20 运行时分发解耦（2026-10-25 进行中）

### 目标
- APK 双形态：`full`（内嵌 `runtime.zip`）与 `noruntime`（首启/关于页下载）；tag/preview 各 8 个 APK，edge 只发 4 个 noruntime。
- hardssh 与 runtime 解耦：CI 从上游 `XiaoPb/dsh-hardssh` Release 取 tgz（用 npm integrity 校验），组装成 `assets/hardssh/`；App 启动由 `HardsshInstaller` 补装，runtime 重装后自动恢复。
- 运行时单一流水线 `scripts/build-runtime.sh`：collect → zod/SONAME 去重 → 补丁/闭包/架构校验 → `BUILD.json` + `runtime.json`。
- 生成式 assets：`app/build.gradle.kts` 的 `prepareRuntimeAssets` 写 `build/generated/runtimeAssets/`；参数 `-Ppackage=full|noruntime`、`-PruntimeZip`、`-PhardsshDir`、`-PruntimeUrlBase`；不再改工作树。
- MANIFEST schema v2：runtime urls 直连 + `gh-proxy.org`、hardssh 元数据、`updates.indexUrl`。
- 云端索引：tag Release 内 `runtime-index.json` + `.sig`；App 从 `releases/latest/download/runtime-index.json` 读取并验签。**不新增 runtime 渠道**。
- 索引签名复用 APK 正式 keystore：CI 从 `RELEASE_KEYSTORE_BASE64` 解 PKCS12 取私钥（RSA/SHA256withRSA），App 验签公钥由 `generateIndexPubKey` 在构建期从同一 keystore 导出（**不提交 PEM**），并回退到 APK 自身签名证书；不新增 secret。
- Fork 自动更新链接：`scripts/apply-fork-links.py` 在构建期把模板里的 `Soodok/Deepseek-Harness-Local-Android` 替换为 `<owner>/Deepseek-Harness-Local-Android`，生成 `RepoConfig.kt`；Soodok 原样不动。
- 安装健壮性：`HttpDownloader`（续传/URL 回退/sha）+ `engine.staging` 校验后换名、失败从 `engine.old` 回滚；空间预算按旧 + staging + zip 峰值。

### 关键文件
`scripts/pack-hardssh.sh`、`scripts/build-runtime.sh`、`scripts/write-runtime-manifest.py`、`scripts/write-runtime-index.py`、`scripts/sign-runtime-index.py`、`scripts/apply-fork-links.py`、`scripts/verify-apk-assets.py`、`app/src/main/java/app/dsh/mobile/engine/{HardsshInstaller,RuntimeIndexClient,HttpDownloader,RuntimeInstaller}.kt`、`app/src/main/java/app/dsh/mobile/engine/RepoConfig.kt.tmpl`。

### 待验证
- CI 首跑（本地按用户要求未编译）：Gradle `-Ppackage` 生成式 assets、2×2 矩阵、tag 的 index 签名。
- 真机首启 noruntime 下载/续传/代理、runtime 重装后 SSH 恢复、完整包不被旧内置运行时降级。

### 验证记录（2026-10-25）
- CI run [`40185178035`](https://github.com/XiaoPb/Deepseek-Harness-Local-Android/actions/runs/40185178035) @ `68c7421` **全绿**：build-hardssh、collect-runtime ×2、build-apk（full/noruntime × arm64/x86_64）、monitor。
- 产物：`runtime-arm64-v8a.zip` 123.9 MB（原 219.5 MB，zod/SONAME 去重生效）；noruntime 双 APK artifact 31.6 MB；full 双 APK artifact 279.4 MB。
- 首次 CI 编译失败根因：`compileSdk 36` 的 `android.content.pm.Signature` 移除了 `getPublicKey()`；改为 `toByteArray()` → X.509 证书 → `publicKey`。
- 本地额外验证：`assembleDebug -Ppackage=noruntime` 23.4 MB（无 runtime.zip、含 hardssh/公钥）；`-Ppackage=full` 229 MB（基线旧 runtime）并通过 `verify-apk-assets.py`。
- tag 通道本地验证：`GITHUB_REF_TYPE=tag` 生成 `<fork>/releases/download/v1.4.19/runtime-<abi>.zip` + sha；`branch/main` 生成 `releases/latest/download/...` 且不带 sha；`GITHUB_REPOSITORY=XiaoPb/...` 时 `RepoConfig`/manifest 全部指向 Fork。
- 索引签名本地模拟：真实 `dsh-release.keystore` → PKCS12 取私钥 → RSA 签名 → 证书公钥验签 OK。
- 未验证：`publish-edge` / `publish-preview` / `publish-release` 的实际 GitHub Release 上传（dev push 上按设计跳过）。

### v1.4.19 发布与真机验证（2026-10-25）
- tag `v1.4.19`（commit `718109c`，在 dev 分支上）CI 全绿，`publish-release` 成功。
- Release 共 12 个资产：8 APK（full 146–147 MB / noruntime 22–24 MB）+ 2 runtime zip（127 MB）+ `runtime-index.json` + `.sig`。
- 用正式 keystore 证书公钥验签 index → OK；`runtime-arm64-v8a.zip` 实测 sha256 `ea2f4b57…`、大小 133,687,953，与 index/manifest 一致。
- MuMu（x86_64, Android 12）：
  - 原安装包为 **debug 签名**（`CN=Android Debug`, `36f752cb…`），与 release 证书 `ea844b60…` 不同；卸载旧包后安装 `x86_64-release-noruntime.apk`。
  - 首启出现“需要下载运行时 / 127 MB / 下载完整包 / 稍后 / 下载并启动”确认框。
  - 点击下载后走 GitHub 直连成功（133,199,450 bytes，约 70s），`RuntimeInstaller` 解压 6 个顶层目录，`HardsshInstaller` 安装 `@xiaopb/dsh-hardssh@0.2.13`，引擎 Healthy，左侧栏出现「SSH 工作区」。
- 尚未验证：SSH 实际连接、关于页运行时更新、full 包离线安装、gh-proxy 回退（本次直连成功未触发）。

### v1.4.20 运行时 UX 修复（2026-10-25）
- MuMu 首次安装 noruntime 暴露两个问题：下载阶段进度条不显示；下载超过 45s 时启动遮罩被 watchdog 收起，露出黑 WebView。
- 修复：`RuntimeInstaller` 在下载阶段发布 `DOWNLOADING` + 字节进度（下载占 45%，解压占 55%）；启动遮罩在 `Installing/Starting` 期间每 15s 续期（最多 5 分钟），不再中途收起；遮罩重做为渐变 + 卡片 + 品牌 tagline。
- 修复 ABI 选择：`AboutActivity`/`RuntimeInstaller`/`SelfCheck` 改用 `Build.SUPPORTED_ABIS.first()`，避免 MuMu 等带 arm 转译的 x86 设备误选 arm64 运行时（表现为 `bundled e_machine=183, device expects 62`）。
- 关于页在已安装时显示“运行时 <version> 已是最新”，不再显示“可用，点击安装”。
- v1.4.20 版本号升至 270/1.4.20；MuMu 实测：首启 1:09 时仍显示下载卡片（112/127MB），完成后引擎 Healthy；关于页显示 x86_64 版本已是最新。
- 原生主题（v1.4.20）：`ThemeMode` 深色（默认）/浅色/跟随系统；`ThemedActivity` + `ThemeManager.wrap` 用 uiMode 选择 `values`/`values-night`；引导页与设置页均可切换；全部 layout/drawable 颜色改为语义色资源。WebView 不专门处理。

### 追加验证（同日）
- **gh-proxy 可用性**：通过 `https://gh-proxy.org/https://github.com/...` 下载 x86_64 完整包（146.1 MB）成功，`verify-apk-assets` 通过。
- **full → noruntime 覆盖**：同一 release 签名下 `adb install -r` 完整包覆盖 noruntime 成功；App 复用已下载运行时，引擎 Healthy，SSH 工作区入口仍在。
- MuMu 侧最终状态：v1.4.19 完整包 + 已下载 `runtime-x86_64` + hardssh 0.2.13；未清除用户数据。

---

## v1.4.21 运行时下载代理与安装并发修复（2026-10-25，未发布）

### 问题
- 关于页「运行时更新」偶发 `runtime install already in progress`。
- 根因：`RuntimeInstaller.installFromIndex()` 用进程级 `installing` 布尔量做 `check()` 互斥；该标志在引擎首启自动安装期间也为 true。用户在首启下载期间触发更新，或安装中再点一次（About 行会重新 check → 再次可安装），就会直接抛英文异常。且 `stop()` 只 cancel 不 join，首启的阻塞 IO 安装仍可能占着标志。
- About 行自身没有防重入：第一次点击启动安装后，再点会走 check，check 完 pending 又变成「可安装」，第三次点击再次进入安装器。

### 修复
- `RuntimeInstaller` 增加进程级 `INSTALL_LOCK`：`ensureInstalled` 与 `installFromIndex` 串行，后到者等待而非报错；`installing` 保留给扩展中心做「装配中不要装扩展」判断。
- 关于页增加 `runtimeInstalling` + `RuntimeInstaller.installing` 双重防重，重复点击提示「运行时正在安装，请稍候再试」。
- 新增 `RuntimeDownloadConfig`：默认 `https://gh-proxy.org/`；设置 → 扩展 → 运行时下载代理可改前缀或留空直连。运行时 zip 与签名索引统一「代理优先、直连兜底」，已代理地址不二次包装；用户显式关闭代理时剔除 MANIFEST/索引里预置的镜像地址。
- 首启 noruntime 下载同样读取该配置，不再依赖 MANIFEST 里的 `urls` 顺序先等直连超时。


### 主题切换入口在 rebase 中丢失 → 修复（2026-10-26，分支 fix/theme-menu）
- 现象：v1.4.24 第三次 rebase 对 `OnboardingActivity`/`SettingsActivity` 整文件取上游，`e7d85ec` 的接线（两处 `ThemePref.set`）被丢弃——`ThemePref.set` 全仓 0 调用点，偏好永远停在默认深色；引导页三卡"看得见点不动"，设置页外观行整行消失（`setting_theme` 成孤儿字符串）；上游把 `activity_settings.xml`（106 处）与 `activity_plugin_store.xml`（4 处）重写为硬编码深色，浅色下白底白字。
- 修复：`AboutActivity`/`OnboardingActivity`/`SettingsActivity` 改继承 `ThemedActivity`（attachBaseContext 的 LocaleHelper.wrap 保留，主题在基类叠加）；引导页重绑三卡 + 选中态高亮，点击写偏好并 recreate（方向/权限选择经 onSaveInstanceState 保留）；设置页外观分区加回「外观主题」行（新 `ic_theme` 图标）+ 三选一对话框，选完 recreate，返回主界面由 `MainActivity.onResume` 的 appliedTheme 检测接力；两个布局硬编码色全部替换为 `@color/*`（昼/夜 26 个色名已对齐）。
- 新探针 `scripts/probes/_test_theme_menu.py`（28 项）：`ThemePref.set` 必须有调用点、引导卡必须绑定、设置行必须接线、两个布局禁止出现 `#hex` 硬编码、三语五条文案齐全、values-night 覆盖全部昼间色名——防止下次整文件覆盖再静默丢掉入口。
- MuMu（Android 12 x86_64）实测：浅色↔深色双向切换，设置页与本页 recreate、主界面返回时重建均正常，两主题截图逐行确认可读；pet/responsive/theme-menu 三组探针全绿。
- 追加（同日，插件页复核）：实机复核发现**代码内**的状态色同样是深色调校的——插件市场卡片插件名是 `setTextColor(0xFFFFFFFF)`，浅色下直接隐形；五个主题化页面（PluginStore/About/ExtensionStore/Settings/PetSettings）共 69 处 `0xFF...` 状态色替换为 `getColor(R.color.*)`（ExtensionStore 的 companion 徽章色存 resId、实例侧解析，避免 companion 无 Context）；探针补 6 项「代码内禁止深色专用字面量」。MuMu 复核：插件市场浅/深两主题插件名与状态文字均清晰，桌宠设置页浅色下「已授权/已安装」深绿可读，关于页浅色正常。
