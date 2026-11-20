# 浏览器内核自研可行性 + 启动速度归因（调研报告）

> 日期：2026-11-20 · 基线：`main` `024a9cb6` / 工作树版本 396 `1.6.0-beta.20`
> 数据来源：本机源码与产物实测（`dsh-android` 工作树、debug APK、模拟器 emulator-5554）、
> 各家官方文档与 Maven 索引一手抓取（Chromium `webview-providers.md` / `android_build_instructions.md`、
> CEF `index.json`、GeckoView maven、WPE `Igalia/wpe-android` README、Ultralight 官网）、设备 logcat 与 `boot.log`。
> 本文只写已验证事实与一手依据；判断处标注「判断」。

---

## 0. 结论

1. **自研/自带浏览器内核不可行。** 六条候选路线逐条堵死，且不是「难」而是「物理上不成立」——
   其中 Chromium 系有系统层的包名白名单 + release 签名校验，自造的 Chromium 根本无法被 Android 选为 WebView，
   只能自绘一整套引擎界面（§1）。
2. **内核不是启动瓶颈，换内核不会变快。** WebView 从进程创建到能渲染一页只需 **1.78s**，
   而引擎就绪要 **6.4～15.2s**；这 1.78 秒**全程被引擎长尾盖住**，不在关键路径上（§2）。
3. **真正的瓶颈是引擎侧 `health-poll`（占总启动 75～83%）。** 要提速只有三条路，
   其中「引擎独立进程 + 接管」已在本机实测打通：热引擎活过 `am force-stop`，接管序列 **30～41ms**（§3–§4）。

一句话：**别造内核，去剥离引擎进程。**

---

## 1. 六条内核路线：逐条一手取证

| 路线 | 一手依据（实测/原文） | 判定 |
| --- | --- | --- |
| **自造 Chromium（系统 WebView provider）** | Chromium 官方 `android_webview/docs/webview-providers.md`：① 包名白名单仅 `com.google.android.webview`(+beta/dev/canary)、`com.android.webview`（且限 userdebug/eng）；② **release 签名校验**「only permitting apps signed with the expected release keys」；③ `targetSdkVersion >= Build.VERSION.SDK_INT` 且须实现该版本全部 API。三条由 `WebViewUpdateService` 强制 | **不成立** |
| **自造 Chromium（自绘，不用 provider）** | `docs/android_build_instructions.md` 原文：需要 `x86-64 machine running Linux with at least 8GB RAM`（>16GB 强烈建议）、`At least 100GB free disk space`、gclient 镜像 `~30 GB for Chromium`，且 **「Building the Android client on Windows or Mac is not supported and doesn't work」**。本机 Win11 | **本机不成立**；换 Linux 机器也是小时级编译 + 数百 MB 产物 |
| **CEF** | `cef-builds.spotifycdn.com/index.json` 平台键实测只有 linux32/linux64/linuxarm/linuxarm64/macosarm64/macosx64/windows32/windows64/windowsarm64 —— **无 android** | **无产物** |
| **GeckoView（Mozilla 官方，唯一成品选项）** | 实测下载 `geckoview-arm64-v8a-160.0.20261116130455.aar` = **91,504,240 B（87.3 MiB）**，解压 **185.3 MB** / 39 entry；其中 `jni/arm64-v8a/libxul.so` **147.4 MB**（zip 62.3MB）、`omni.ja` 14.5MB、`libgkcodecs.so` 4.9MB、`libcrashtools.so` 5.7MB、`libnss3.so` 2.8MB。按 zip 体积算：已发布 arm64 full 包 `191,678,030 B`（beta.17）会再 **+47.7%**；本机精简 release 包 `42,559,164 B` 会变成约 **2.2 倍** | **能力有，代价不可接受**：字节只是一层，更硬的是 API 与 `android.webkit` 完全不同，§1.1 的 51 个调用点要全量重写；而且**它对启动毫无帮助**（§2） |
| **WPE WebKit for Android** | `Igalia/wpe-android` README：① 应用必须把 `Application` 换成 `org.wpewebkit.WPEApplication` 作基类（对本仓 `DshApp` 是全局换基类）；② README 自己写着 `(TODO: package, distribute and document installation)`，官方**尚未打包分发**；③ Maven Central `org.wpewebkit` 目录 HTTP 200 但 solrsearch 查 `wpewebkit` 得 **numFound=0**，无消费级 AAR；④ 最新带 apk 的 release 是 `latest-preview`（2022-12-14），minibrowser-arm64-v8a **127.7 MB** | **不可消费** |
| **Ultralight / Servo / 其他轻量引擎** | Ultralight 官网平台只有 Windows / macOS / Linux（自述 single-process WebKit port），**无 Android**；Servo 仓库只有 Android 相关的 open issue（加载状态、滚动、WebGPU 在 android 不工作），属实验性移植，无成熟可嵌入库 | **无 Android 产物** |

### 1.1 换内核的真实调用面代价

本仓对 `android.webkit` 的依赖不是「几个调用」，是深度耦合：

| 指标 | 实测（2026-11-20 在 `024a9cb6` 上重跑） |
| --- | --- |
| `webView.*(` 调用点 | **51 处**，全在 `MainActivity.kt` |
| 用到的不同方法 | **18 个** |
| `import android.webkit` 的文件 | **3 个**（`MainActivity.kt` / `WebViewDiagnostics.kt` / `WebViewDownloader.kt`） |
| `MainActivity.kt` 里提到 WebView 的行 | **313 行** |

> 调用面**不宽但极深**：51 个调用点集中在同一个 4158 行的 Activity 里，扩散范围只有 3 个文件。
> 这对换内核既不是好消息也不是坏消息——改动点集中，但没有任何一处可以「先试试看」，
> 全量替换必然是一次性大改。

而这些不是「换个类名」能改完的——捆绑在 WebView 上的还有 `WebViewClient` 拦截主文档
（`interceptWebUiDocument()` `MainActivity.kt:1007` 只拦 host∈{127.0.0.1,localhost}、port==enginePort、
path∈{"","/","/index.html"}、转发 Cookie、`Accept-Encoding: identity`）、预热页语义
（`preheatWebView()` `:1111` / `isPreheatNavigation()` `:1126` 只认 `about:blank`、预热页 `onPageFinished`
不得 `dismissSplash`）、`dropPreheatHistory`（右滑返回白屏的补丁）、以及 `scripts/patch-webview-syntax-floor.py`
对老内核 `static{}` 的转译。换内核 = 这些全部重写并重新踩一遍。

### 1.2 一个常被忽略的事实：系统 WebView 本身就是 250MB 级 Chromium

设备实测（emulator-5554，`com.google.android.webview` version `113.0.5672.136`）：

| 包 | 体积 |
| --- | --- |
| `TrichromeLibrary.apk` | **131,705,435 B** |
| `WebViewGoogle.apk` | **121,211,589 B** |

即：**用户设备上早就有这套内核，本 App 只是没有为它付字节。**（这两份是已安装包的 APK 文件体积，
不是运行时常驻内存；同属一个 Chromium 版本，说明这份成本已由系统侧承担。）

自带内核的「收益」是「不依赖系统 WebView 版本」，代价是**把别人设备上已有的 250MB 再塞进自己包里一份**。
`dumpsys webviewupdate` 显示 `Minimum targetSdkVersion 33`；本仓 `MIN_CHROME=85` + polyfill 已覆盖低版本。

---

## 2. 内核与启动速度的真实关系：它在长尾里，不在关键路径上

### 2.1 一次冷启的精确时间线（logcat，2026-11-20 10:28）

```
10:28:48.202  Start proc app.dsh.mobile
10:28:48.846  WebViewFactory Loading com.google.android.webview 113.0.5672.136
10:28:49.110  sandboxed_process0 起（Chromium 渲染进程）
10:28:49.336  webview gate pass: chrome=113 min=85 provider=com.google.android.webview
10:28:49.979  preheat page finished (engine still starting)   <-- 进程起→能渲染一页 = 1.78s
10:28:51.671  boot step spawn: 641ms (total 1719ms)           <-- 引擎才刚开始起
10:28:56.646  boot step health-poll: 4975ms (total 6694ms)
10:28:56.680  engine healthy on :3080
10:28:58.629  dropped preheat history entry
```

### 2.2 关键差值

| 段 | 耗时 | 与引擎的关系 |
| --- | --- | --- |
| 进程创建 → WebView gate 通过 | ≈1.1s | 与引擎**并行** |
| 进程创建 → 预热页可渲染 | **≈1.78s** | 与引擎**并行**，此时引擎还没 spawn |
| spawn（起 node 进程） | 590～904ms | 关键路径 |
| health-poll（引擎真的就绪） | **4843～12689ms** | 关键路径，占 75～83% |

**判断：WebView 的 1.78 秒在引擎就绪（6.4～15.2s）之前早就结束了，用户看到的白屏/启动页
时间由引擎决定，不由内核决定。** 换任何内核都不会把这 1.78 秒从关键路径上移走——它本来就不在。

反过来说，换内核只会**变慢**：GeckoView 要在启动时加载 147MB 的 `libxul.so` + 14.5MB `omni.ja`，
而系统 WebView 的 Chromium 可执行映射在 zygote/preload 体系里早已热；自绘内核还会失去
「`about:blank` 预热 + 主文档拦截」这套已经跑通的加速（新内核的预热语义要从零设计）。

### 2.3 与仓库既有决策一致

- `docs/MODULARIZATION_PLAN.md:34` A06：浏览器引擎 | WebView/Gecko 双内核 | 系统 WebView + polyfill | **不做 Gecko**
- `docs/COMPETITIVE_STRATEGY.md:91`：「polyfill 已覆盖 Android 8 老 WebView；第二内核维护成本远大于收益」

本次调研的结论是**该决策仍成立，且依据比当初更硬**（签名校验/包名白名单是系统级墙，不是工程量问题）。

---

## 3. 真正的瓶颈：`health-poll`（引擎侧装配）

### 3.1 boot.log 七次冷启采样（2026-11-20 10:53）

| spawn | health-poll | TOTAL | health-poll 占比 | webview-first-paint |
| --- | --- | --- | --- | --- |
| 590ms | 4843ms | 6450ms | 75.1% | 7980ms |
| 641ms | 4976ms | 6729ms | 74.0% | 8696ms |
| 712ms | 5743ms | 7981ms | 72.0% | 9730ms |
| 822ms | 6133ms | 8337ms | 73.6% | 10373ms |
| 837ms | 12689ms | 15223ms | 83.3% | 20191ms |
| 864ms | 6542ms | 8759ms | 74.7% | 10841ms |
| 904ms | 8668ms | 11055ms | 78.4% | 14521ms |

> 采样环境是模拟器 + 多会话并发（`health-poll` 从 4843 到 12689 的方差主要来自 CPU 争用，
> 不是引擎行为差异）。真机数字见 `AI_CONTEXT.md` 第一百四十章：热态 2819～3423ms、冷态 4137～5274ms。

`webview-first-paint`（真实页渲染完成）恒晚于 TOTAL **1.5～5.0s**（`MainActivity.kt:995`
`BootTrace.noteWebViewLoaded` 打点，预热页分支提前 return 不计），说明**引擎就绪之后还有一段前端拉起的尾巴**。

### 3.2 spawn 内部已经没什么可抠的了

```
orphan-cleanup 2ms / heal-shebangs 30ms / heal-pnpm 8ms / patches 197ms / mode-resolve 0ms
profile-plugins 17ms（含 10 个 patch 插件部署，每项 1～8ms）
```

`spawn` 的 590～904ms 里 197ms 是 patches、其余是进程与 PTY 建立。**能砍的只剩 patches。**

### 3.3 node 自身启动不是瓶颈（实测）

| 项 | 实测 |
| --- | --- |
| `engine/bin/node` 裸跑 `console.log` | `real 0.12～0.13s`，`process.uptime()` 107～114ms（3 次） |
| node 二进制 | v24.22.3 / 45,809,904 B |
| `NODE_COMPILE_CACHE` | 已注入 `/data/user/0/app.dsh.mobile/files/dsh-home/.node-compile-cache-native`，实测 **14,000 KB**，子目录 `v24.22.3-x64-964aae3f-10193` |

即编译缓存机制已经在生效。**剩下那 4.8～12.7 秒全是插件装配 + 338 个插件/74 个客户端插件的激活 + WebUI 就绪。**

---

## 4. 三条真正能提速的路径

### 4.1 路径 A（推荐）：引擎独立进程 + 接管热引擎

这是本次调研**唯一已实测打通**的提速路径，收益是「杀后台再开」从 6.4～15.2s 降到**几十毫秒**。

**关键实验（两轮一致，决定性证据）**

```
# 用 app 自己的 uid、脱离 app 进程树起 node
run-as app.dsh.mobile sh -c 'setsid <engine/bin/node> -e "...listen(9998)" &'
# 外层 shell 退出后
ps  ->  15834  1  u0_a193  node          # ppid=1，已脱离 app
am force-stop app.dsh.mobile
ps  ->  15834  1  u0_a193  node          # 仍存活，State: S，TracerPid: 0
grep 270E /proc/net/tcp -> 0100007F:270E ... 0A ... uid 10193 inode 204442   # 端口仍 LISTEN
# force-stop 之后用另一个进程请求
AFTER_FS_status=200 body=alive-ok
```

对照：`su` 起的 uid 0 detached node（pid 12149）同样活过 `force-stop`。**这是「引擎能活在 app 进程之外」的第一手证据。**

**接管序列实测（进程外读 token + 请求 WebUI）**

```
t_read_log = 1～2ms      # engine.log 27963 字节，正则取最后一条 dsh web: <url?token=...>
t_webui_respond = 26～39ms   status=303
t_done = 30～41ms
```

> 读日志必须 `.split('\u0000').join('')`（engine.log 含 NUL 填充），token URL 长度实测 72 字节。
> `303` 是 WebUI 的正常重定向（token 被受理），不是 401/403。

**现成的可行性旁证**

- token 通道**不需要新建**：`EngineSupervisor.extractTokenUrl()`（`:125`）本来就是「按字节偏移读 engine.log 尾部取 `dsh web:` 行」，进程外读同一份日志同样成立（实测 1～2ms）。
- `EngineService` 已是 `START_STICKY` 前台服务，`onTaskRemoved` 有自愈拉回（`:104`），保活底牌是无障碍服务——**引擎侧独立进程后这套机制可以简化而非新增**。
- 目前语义是**杀而不是接管**：`reclaimPort()` `:1541` 直接转调 `reclaimPortByPort()` `:1560`（注释 *thorough reclaim, kill every holder of this port*），代码里没有任何「接管已在跑的引擎」分支；`cleanupOrphanEngine()` `:1661` = `if (!portInUse()) return; reclaimPort()`。所以「杀后台再开」必然重跑全流程——**这正是要改的那一处**。

**必须一并解决的问题（已实测的坑）**

| 坑 | 实测现象 | 需要的处理 |
| --- | --- | --- |
| 手动直跑引擎入口会崩 | 以 `run-as` 直跑 `engineRoot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js` 报 `node-addon-require-builtin` 的 `requireStack` 错误 | 独立进程必须**复刻 `EngineProcess` 的完整 env**（`EngineConfig.kt:1835-1860` 渲染段：`DSH_SHZ_PORT`/`DSH_BRIDGE_TOKEN`/`DSH_PET_BRIDGE_TOKEN`/`LD_LIBRARY_PATH`/`PREFIX`/`SHELL`/`HOME`/`DSH_HOME`/`NODE_COMPILE_CACHE` 等） |
| 桥 token 是进程内随机、不落盘 | `AgentBridge.currentToken()`（`:149`）每次进程启动 `UUID.randomUUID()`，注释明写「不落盘、不复用」；`PetBridgeServer` 同理 | 接管时新 app 进程**拿不到旧引擎的桥 token** → 设备能力（scr/notify/shz）会 403。要么把 token 落盘（安全审查要过），要么让旧引擎带着「可被接管」的通道 |
| 旧引擎的 token URL 会混在历史日志里 | `extractTokenUrl` 按**字节偏移**（`logOffsetAtSpawn`）读，接管场景没有「本次 spawn」，偏移无从取 | 需以「进程启动时间 + 端口 + token 行」三重判据去歧义，不能只取日志最后一条 |
| 孤儿进程的父链判断过宽 | `OrphanReaper.reapWithPrivileged` 的 `if (ppid != 1 && alive.contains(ppid)) continue`，`alive` 是 app 可见**全量** /proc pid 集合，比注释意图（只排除本 app 进程树）宽得多 → 父进程仍是 su 外壳/ksud 的真 root 孤儿会被跳过 | 接管方案要先把这条收紧，否则「该保留的」与「该杀的」分不开 |
| detached 进程的调度属性**取决于启动路径**（两轮实测不一致） | 第 1 轮（`su` 起）node 落 `0::/uid_0/pid_6733`、cpuset/cpu 非 top-app、`oom_score_adj=-950`；第 2 轮（`run-as` + `setsid`）node 落 `0::/uid_10193/pid_16184`、cpuset/cpu **= top-app**、`oom_score_adj=0` | **不能一概而论「脱离 app 必失前台优先级」**；两种起法结果相反，独立进程的优先级必须按实际启动路径逐条量测，不能沿用某一轮的结论 |

**收益与代价（判断）**

- 收益：用户「杀掉后台再打开」从 6.4～15.2s → 接管 30～41ms + 前端渲染；真正实现「秒开」。
- 代价：进程生命周期、token 传递、cgroup/优先级、清场语义（从 kill 改 adopt）四处都要动，且都在安全边界上。
- 属于评审必须过一遍的改动，不适合塞进一次发版。

### 4.2 路径 B：前后端分离（首屏不等引擎）

事实基础：前端 bundle 只有 **100 entries / 原始 5.55 MB / zip 1.49 MB**（最大 `dist/assets/index-Ciz7DZxv.js` 1144.6KB(zip 325.3KB)、`vendor-BUMdD3V7.js` 732.9KB、`index-CSAdOBx6.css` 90.1KB；`index.html` 9957 B），
WebView 预热 1.78s 就绪——**壳子和前端足够快，慢的是引擎**。

做法：WebView 先加载本地静态壳（不走 `127.0.0.1:3080`），壳内连引擎；引擎就绪后壳切到真实会话。
用户感知从「等 6.4～15.2s 白屏」变成「壳子立刻出现 + 显示启动进度」。
**判断**：这条不改引擎进程模型、风险最低，但只改善「感知」，不减少等待——可作为路径 A 的过渡。

已有可复用的积木：`interceptWebUiDocument()` 已具备「拦主文档并本地构造响应」的能力，
`preheatWebView()` + `isPreheatNavigation()` 已具备「预热页不算真实页」的语义，
`dismissSplash()` `:2092` 已幂等。

### 4.3 路径 C：引擎侧剪枝（唯一能真正缩短 `health-poll` 的）

已验证的既有事实：

- `EngineConfig.kt:1445-1470` startup trim tier 0 已禁 5 个遥测插件：warm compile cache 由 1330/1330/1330 改善到 1160/1220/1180ms；
- 服务端 HTML 字节一致 49027 B / **74 个 client plugins**；`android-overlay.yml` 本体 14309 B / **31 条 `id:` 行**；历史记录里引擎共加载 **338 个插件**；
- `require('module').enableCompileCache?.()`（`:1138`）+ `NODE_COMPILE_CACHE`（`:1881`）+ `EngineSupervisor` 的 `compileCacheFlusher`（`:1425`/`:1454`）已在用，缓存实测 14MB。

因此可做的是：把「用户首屏不需要」的服务端插件推迟到空闲/首次使用时激活（现有 trim 机制已证明这条路有收益，
且 `client.js` 拉取在 engine.log 里可见是并行批量请求）。**但 338 → 74/31 的层级不同，要逐条确认依赖，不能拍脑袋删。**

---

## 5. 建议

| # | 做不做 | 理由 |
| --- | --- | --- |
| 自研内核 / 换 GeckoView / 换 WPE | **不做** | §1 六条全堵；§2 内核不在关键路径 |
| 引擎独立进程 + 接管（路径 A） | **做，但单独排期 + 评审** | 唯一实测打通的秒开路径；四个坑必须一并处理 |
| 前后端分离首屏（路径 B） | **做，低风险过渡** | 只改 UI 层，不改进程模型 |
| 引擎侧剪枝（路径 C） | **继续做，小步验证** | 唯一能缩短 health-poll 的方向，已有 trim 先例 |

---

## 6. 未验证 / 风险边界

1. **全部设备证据来自模拟器**（emulator-5554 / Android 14 / x86_64 / `adb root`）。真机上 detached 进程能否活过厂商 ROM 的清理（MIUI/HyperOS 的「后台清理」比 AOSP `force-stop` 更狠）**未验证**——这是路径 A 最大的未知。
2. GeckoView 的 185.3MB 解压体量只算了 arm64-v8a 单 ABI；**双 ABI 打包会更大**，且未测试其对冷启的实际耗时影响（本文只用体量与既有 API 差异做判断）。
3. Chromium 编译门槛是否可用「租一台 Linux 构建机」绕过，本文未做成本核算；即便编译成功，provider 的签名白名单仍然拦着，**只能自绘**。
4. `health-poll` 的方差（4843～12689ms）在模拟器上受并发会话干扰明显，真机稳态数值以 `AI_CONTEXT.md` 第一百四十章为准。
5. 路径 A 的「接管后桥 token 失效 → 设备能力 403」是**代码注释层面的必然**（`AgentBridge` 明写不落盘），未在接管场景实测；修法（落盘 vs 引擎侧转发）涉及安全审查，本文不给结论。
6. 手动直跑引擎入口失败（`node-addon-require-builtin`）说明「独立进程」不是把 spawn 换个地方那么简单，env 复刻面**只从渲染代码推断，未逐项实测**。

---

## 7. 复用经验

- **判定「某组件是否在关键路径」用并行时间线，不用体感。** 本次靠「进程起 → 预热页 1.78s」与「进程起 → 引擎就绪 6.4s」两条并行时间线，一句话否掉了整个内核方向的提速假设。
- **系统级约束先查官方 provider 文档，再算工程成本。** Chromium 的包名白名单 + release 签名校验是墙，不是工作量；先查这一条能省掉「能不能编出来」的全部讨论。
- **「进程能否活过 force-stop」必须自己起一个 detached 进程来验**，不能从 `START_STICKY` / 前台服务推断。实测结论与直觉相反：不在 app 进程树里的同 uid 进程能活，而 app 的子进程必死。
- **改 Kotlin raw string 里的 shell 脚本文本，永远不用 Python/JS 中转转义**（沿用既有教训：`$` 与 `\` 两层转义会静默错一层）。
