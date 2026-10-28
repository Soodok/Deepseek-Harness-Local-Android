# DSH Mobile · DeepSeek Harness for Android

<p align="center">
  <img src="docs/promo/dsh-mobile-github.png" alt="DSH Mobile — DeepSeek Harness for Android" width="100%">
</p>

<p align="center">
  <b>把完整的 Node.js Agent 引擎，以 Android 原生二进制打进 APK。</b><br>
  不套容器、不装发行版、不要 Root、不要 Termux、不要电脑。<br>
  <b>装完即用，冷启动 3 秒级。</b>
</p>

<p align="center">
  <a href="#-为什么快">为什么快</a> ·
  <a href="#-架构">架构</a> ·
  <a href="#-能力全景">能力全景</a> ·
  <a href="#-路线对比">路线对比</a> ·
  <a href="#-安装">安装</a> ·
  <a href="README_EN.md">English</a> ·
  <a href="README.de.md">Deutsch</a>
</p>

<!-- 徽章区：左边看"有多少人在用"，右边看"项目是否健康"。 -->
[![Downloads](https://img.shields.io/github/downloads/Soodok/Deepseek-Harness-Local-Android/total?style=for-the-badge&label=downloads&color=2563eb)](https://github.com/Soodok/Deepseek-Harness-Local-Android/releases)
[![Stars](https://img.shields.io/github/stars/Soodok/Deepseek-Harness-Local-Android?style=for-the-badge&color=f59e0b)](https://github.com/Soodok/Deepseek-Harness-Local-Android/stargazers)
[![Release](https://img.shields.io/github/v/release/Soodok/Deepseek-Harness-Local-Android?style=for-the-badge&color=16a34a)](https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/latest)

[![CI](https://github.com/Soodok/Deepseek-Harness-Local-Android/actions/workflows/android-build.yml/badge.svg)](https://github.com/Soodok/Deepseek-Harness-Local-Android/actions/workflows/android-build.yml)
![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20~%2016-3ddc84?logo=android&logoColor=white)
![Engine](https://img.shields.io/badge/engine-dsh%200.2.1--rc.2-4b5563)
![Runtime](https://img.shields.io/badge/runtime-bionic%20Node.js-5fa04e?logo=nodedotjs&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-brightgreen)
[![Last commit](https://img.shields.io/github/last-commit/Soodok/Deepseek-Harness-Local-Android?color=64748b)](https://github.com/Soodok/Deepseek-Harness-Local-Android/commits/main)
[![QQ](https://img.shields.io/badge/QQ%E7%BE%A4-1128769016-12b7f5?logo=tencentqq&logoColor=white)](https://github.com/Soodok/Deepseek-Harness-Local-Android#%E5%8F%8D%E9%A6%88)
[![Telegram](https://img.shields.io/badge/Telegram-Join_group-26A5E4?logo=telegram&logoColor=white)](https://t.me/+4VN3pYyH6i8yNDA1)

---

## ⚡ 为什么快

这是本项目最核心的一条，值得单独讲。

**Android 上的 Agent 方案快不了，原因通常是架构性的。** 它们要么在手机上现搭 Linux 环境（Termux + apt），要么跑一层 proot 容器（glibc rootfs 500 MB～2 GB），要么打包一个冻结的 Termux 快照。这三条路的共同点是——**启动时要做的准备工作太多**：解压 rootfs、挂载伪文件系统、初始化软件源、装载环境变量。

DSH Mobile 走的是另一条路：**没有中间层**。

| 方案 | 跟引擎之间隔了什么 | 冷启动 |
|---|---|---|
| Termux 手动配置 | Perl/bash 启动链 + apt + 环境初始化 | 分钟级 |
| proot + Ubuntu | 内核 → proot 用户态 → glibc → 发行版 → Node | 十几秒起 |
| 冻结快照 | 解压 rootfs → 修补路径 → 启动 | 数十秒 |
| **DSH Mobile（本项目）** | **— 直接 `execve` bionic Node** | **3 秒级** |

关键在于：**我们把 dsh 需要的整套运行时，编译成 Android 原生（bionic）二进制，直接塞进 APK。** 运行时不需要「启动」，它本来就在那儿。冷启动真正做的事只有三件——解压（仅首启一次）、起 Node、等回环端口就绪。

### 实测数字

| 场景 | 设备 | 冷启动到引擎就绪 |
|---|---|---|
| 旗舰真机 | 一加 15T（Android 16） | **3.4 秒** |
| 主流机型 | 各品牌中端 Android | **5 ~ 6 秒** |
| 极端低端 | 紫光展锐手表（**3 GB 内存**） | **13 秒** |
| 模拟器 | x86_64 / 2.5 GB 内存 | **2.2 ~ 2.7 秒** |

作为参照：proot 系方案在不少中低端设备上**直接起不来**，主流机型上平均也要十几秒；Termux 手动配置首次需数分钟。

> 一台 3 GB 内存的紫光展锐手表能跑起来，本身就是架构的证明——同样的内存，proot + glibc 连 rootfs 都铺不开。

### 这些数字是怎么来的

不是调参调出来的，是几个设计决策叠加的结果：

| 设计决策 | 换来了什么 |
|---|---|
| **bionic 原生运行时**，不走 glibc、不套容器 | 省掉 proot 那层用户态翻译，也省掉 rootfs 解压与挂载 |
| 运行时**预置进 APK**（125 MB 压缩 / 404 MB 解压 / 14572 个文件） | 首次使用无需额外下载，装完即用，离线可启动 |
| `targetSdk = 28`（sideload 策略，与 Termux 同款） | 豁免 Android 10+ 的 W^X 限制，可直接 `execve` 私有目录下的二进制 |
| **CI 收集 + SONAME 去重 + SHA 校验**的运行时流水线 | 每次发布都是可复现产物，不是「我这台机器上能跑」 |
| 全部 `.so` 按 **16 KB 页对齐** | Android 15+ 的 16 KB 页内核设备直接可用 |
| 前端注入 **polyfill**（14 个现代 API） | WebView API 门槛从 Chrome 126 拉回到 **85**，国产 ROM 常见的 96 实测可用 |

---

## 🧱 架构

```mermaid
graph LR
    A["APK<br/>assets/runtime.zip"] --> B["bionic Node.js<br/>dsh 引擎 + 工具链"]
    B -->|"execve 直接启动"| C["dsh 引擎进程<br/>127.0.0.1:3080"]
    C --> D["WebView<br/>原生 App 壳"]
    C --> E["Agent 工具链<br/>文件 / 终端 / 无障碍"]
    D -.->|"localhost"| C
```

**分层职责很清晰**：

- **运行层（本项目负责）**：bionic Node 运行时、前台服务保活、三级权限、扩展中心、原生化 UI
- **引擎层（上游负责）**：插件体系、WebUI、工具调用链、会话持久化 —— 全部来自官方 dsh，原样保留

**这不是自研 Agent 框架。** 桌面上 dsh 能做的事，这里都能做；插件生态通用，能力上限跟着上游走。

---

## ✨ 能力全景

下面这些不是「功能列表」，而是**为了让桌面级引擎在 Android 上真正跑通，逐项啃下来的硬骨头**。每一项都对应真实的失败现场。

### 🖥️ 真 PTY 终端：原生 C 写出来的

Web 终端看起来简单，实际是全项目最难的一层。上游 `@deepseek-ai/dsh-subprocess-local` 有两处完全不认识 Android：

1. **进程探测器拒收该平台**。`createProcessInspector()` 只认 `linux` / `darwin` / `win32`，其它平台直接抛 `terminal inspection is unsupported on platform android`。Termux 构建的 node 报告的是 `process.platform === "android"`——**不是 linux**。所以任何终端的 spawn 在第一步就死了。而 Android 有完整 procfs，上游那个 Linux 实现本来就是对的，只是没被选上。

2. **`node-pty` 是空壳**。它没有 Android 预编译产物，官方收集脚本只能塞进一个「一调用就抛错」的占位实现。

我们的做法：

- 补丁让探测器把 `android` 当作 linux 语义（`patch-node-pty-android.py`）
- **自己写了 581 行 C**：`dsh_pty.c` + `dsh_pty_exec.c`，通过 `posix_spawn` + `openpty` 提供真实的 PTY 能力
- node-pty 替换成转发到该原生助手的兼容实现

结果是**真有 bash 的终端**：能跑 `top`、能开 vim、能接需要 tty 的交互式命令。不是管道模拟。

> 细节见 [`scripts/patch-node-pty-android.py`](scripts/patch-node-pty-android.py) 头部注释。

### 🔧 14 个 Android 适配补丁：把桌面引擎掰到手机上

这是本项目最不显眼、也最费工的部分。上游引擎默认假设自己跑在 Linux 桌面，每一个「想当然」在 Android 上都是崩溃点：

| 补丁 | 解决的问题 |
|---|---|
| `patch-node-pty-android` | PTY 探测器拒收 android 平台；node-pty 空壳 → 桥到原生 C 助手 |
| `patch-ptc-runtime-android` | PTC 子进程清空环境变量 → `libz.so.1 not found`（Android 没有 ldconfig，只能靠 `LD_LIBRARY_PATH`）；外加 bootstrap 路径映射兜底 |
| `patch-webview-polyfill` | 前端用了 14 个现代 Web API，旧 WebView 全没有 → 注入 polyfill，门槛从 Chrome 126 降到 85 |
| `patch-fs-local-android` | 文件系统的路径语义、权限检查假设 Linux 桌面 |
| `patch-apiproxy` | `openPath` / `openTextFile` 调 `xdg-open` / `open` / `start`，Android 全无 |
| `patch-attachment-local` | 附件本地存储的路径与 URI 处理 |
| `patch-bash-sysbashrc` | 装了 Termux 的设备上，bash 去读 `/data/data/com.termux/...` → Permission denied |
| `patch-curl-wrapper` | `bin/curl` 缺 `LD_LIBRARY_PATH`，依赖库找不到 |
| `patch-ptc-runtime-android` | 扩展工具链变量（Python/Perl/Git/ImageMagick/Rust…）全部缺失 → PTC 里静默失效 |

**每个补丁都是 fail-fast 的**：上游重构导致锚点没命中就**中止构建**，而不是 WARN 后假装成功——漏打一个补丁意味着某个功能对用户直接不可用，宁可发不出来也不能悄悄坏掉。

### 🔄 运行时换装：两阶段提交，不怕中途被杀

换运行时不能「先删再解压」——那会留下「删完就没」的致命窗口。也不能简单「解压到新目录再换名」：换名要逐个顶层条目做，**任何一次之后被杀，磁盘上就是新旧混合态**。

更阴险的是：混合态**不一定**被完整性检查判为不完整。检查只查「锚点文件在不在 + node 架构对不对」，**新 `bin/` 配旧 `lib/` 完全通得过**。所以「反正不完整会重装」这条兜底是不可靠的——最坏情况是带着错配的运行时启动，然后各种诡异崩溃。

我们的做法（v1.4.26 起）：

1. 解压到 `engine.staging`，`engine/` 全程不动
2. 校验 staging（锚点文件 + node 可执行位 + **ELF 架构**）
3. 写一份 **`.runtime-swap` 意图日志**（记录「我要开始换了」）
4. 逐个顶层条目换名
5. 保留 `engine.old` 直到引擎首次进入 Healthy
6. 下次启动若发现意图日志存在但没完成 → **知道是混合态，走恢复**

这是数据库领域的两阶段提交思路，搬到文件系统换装上。低内存被 OEM 杀后台是 Android 常态，没有这套机制，运行时更新就是一场赌博。

> 完整设计与失败矩阵见 [docs/RUNTIME_SWAP.md](docs/RUNTIME_SWAP.md)。

### 🔌 扩展中心：把手机变成工作站

**21 项环境扩展**一键安装：Python、OpenJDK 17、Go、Rust、Ruby、PHP、Lua、Perl、Clang、CMake、Git、SQLite、FFmpeg、ImageMagick、OpenSSH、Vim、ADB 等。

难点不在下载，在**让装完的东西真的能用**：

- **国内镜像自动切换**（清华 TUNA → 中科大 → 北外 → Termux 官方），任何一条挂了自动顺延
- **依赖闭包递归解析**，逐包 SHA-256 校验，**原子发布**——不会留下半套环境
- **shebang 自动修复**：Termux 包里的脚本写死 `#!/data/data/com.termux/files/usr/bin/python`，在我们这儿不存在 → 装完自动改写，`env python3` 直接可用
- **ELF 架构校验**：解压前断言二进制架构与设备匹配，避免装了个 arm64 包到 x86_64 上
- **Agent 也能自己装**：Root 模式下已实测自装 Android SDK 命令行工具，AI 缺什么补什么

### 🧩 社区插件：整个 dsh 生态搬过来

内置 **3540 个社区插件**目录（23 个分类，中英双语），搜索、看详情、一键装卸，全程不离开 App。

真正的工作量在**判断哪些插件能装**：

- **兼容性三级分类**：纯 JS（能跑）/ 需要原生模块（多半不行）/ 引擎 fork（设计上不兼容），用静态分析 `package.json` 与源码特征判定
- **3540 条不能一次渲染**：早期版本把全部结果塞进 ScrollView，两万多个 View 直接把主线程压死 → OOM/ANR → 进程被杀 → 引擎看门狗误判重启。现在改增量分页（首屏 30 条 + 滚动追加），并加了 12 项回归探针锁住
- **pnpm 锁自愈**：store 锁目录属主损坏（Root 残留 000 权限）时删不动，改用 rename 绕开——rename 只改父目录条目，不进入目录内部，属主正常就必成功

### 📱 原生竖屏 UI：不是把桌面缩小

三个客户端插件协同：侧栏全宽、设置面板重构、右侧面板全屏。**信息密度、触控热区、字号层级全部重排**。

这不是「响应式 CSS」级别的适配——是重新设计了手机上的信息架构：什么该藏进二级、什么该常驻、单手握持时拇指能够到哪里。

### 🫧 悬浮窗 · 保活 · 无障碍：手机端真正的增量价值

桌面版给不了的三件事：

**1. 系统级悬浮窗（桌宠）**
插件驱动的独立 overlay，资源包制（多套素材可换、云端下载、后台服务下载不怕退出页面）、动画链（加权随机动作 / 翻面 / 动画驱动位移）、气泡台词库（六档语义，可自定义措辞）。

**2. 无障碍控制：给 AI 装眼睛和手**

读屏 / 点击 / 输入 / 滑动 / 长按 / 拖拽等 **21 种原语**，实测约 **1.45 秒/步**。

三条铁律是踩血换来的：

- **验证必须是目标状态，不是动作描述**。「点了按钮」没有意义，「页面出现了 X」才有意义。AI 自报成功是最不可信的信号——实测它会对着错误页面的通用标记自认成功
- **失败要回退到「认得的状态」再重试**，同一步骤连续失败不超过 2 次就换打法
- **感知分层兜底**：无障碍树 → OCR → 帧差。任何一层都可能缺失（ColorOS 浏览器 / WebView / 游戏拿不到节点树），只有分层才可靠

> 这份经验已整理成 [完整的交接手册](docs/A11Y_AI_CONTROL_HANDBOOK.md)，包含场景分级、原语清单与坑位记录。

**3. 后台不被杀**
`specialUse` 前台服务 + 指数退避监督器。Android 的 OEM 后台清理是敌对环境，`dataSync` 类型有 6 小时上限，`specialUse` 没有——这个类型选择本身就是研究出来的。

### 🔐 三级权限：渐进式提权

| 模式 | 能力 | 说明 |
|---|---|---|
| **普通** | 沙箱内运行 | 安全默认，AI 碰不到系统 |
| **Shizuku** | adb 级操作 | 免 Root，跑命令、保活、杀进程 |
| **Root** | 完整系统权限 | su 闸门管控，每次提权可见 |

**双身份共存是个真实的坑**：Root 模式下引擎以 uid 0 跑，App 主进程是普通 uid，两者共用 `dsh-home`。我们踩过的一次：每次启动引擎都会递归 `chown` 整棵树，把 root 身份的 pnpm 按 uid 分后缀的锁目录也改错了属主 → 插件装卸全失败、每次重启复发。修法是 chown 之后按目录名的数字后缀把属主还原回去。

### 🌐 内置 SSH 远程工作区

手机连服务器改代码：远端工作区、Web 终端、隧道与 `ssh_*` 工具，全部内置。SSH 私钥可以在应用内生成或从文件管理器手动放入，Agent 直接可用。

### 🎙️ 语音输入

双通道识别（离线 PocketSphinx + 云端），发送去重、面板停靠。离线引擎的体积与识别率做了取舍，云端作为补足。

### ⚙️ 工程化：CI 一手包办

- **双架构矩阵构建**（arm64-v8a / x86_64 × full / noruntime = 8 个 APK 变体）
- **运行时流水线**：从 Termux 仓库收集 → zod 嵌套去重 → SONAME 别名去重（省 37 MB）→ 打 14 个补丁 → 写 `BUILD.json` → SHA 校验
- **签名索引**：`runtime-index.json` 用 EC P-256 detached 签名，私钥只在 CI secrets，App 端验签失败直接拒绝（不降级）
- **15 项回归探针**：每个都对应一次真实事故，PR 阶段就能拦住回归
- **git 卫生门禁**：推送前自动检查工作区脏、远端有新提交、CRLF 污染、探针目录误加

---

## 🆚 路线对比

| | Termux 手动配置 | proot + Ubuntu | 冻结快照打包 | **DSH Mobile** |
|---|---|---|---|---|
| 冷启动 | 分钟级 | 十几秒起，部分设备起不来 | 数十秒 | **3 秒级** |
| 最弱设备 | 需自行折腾 | **3 GB 内存设备基本无望** | 看快照体积 | **3 GB 手表 13 秒** |
| 安装体验 | 装 Termux、配环境、装依赖 | 装容器与发行版 | 装即用 | **装即用** |
| 首次可用需下载 | 手动配置 | rootfs 500 MB～2 GB | 无 | **无** |
| 运行时 | 活环境（可扩展） | 容器内 glibc | 死快照，随包冻结 | **自建 bionic 闭包，CI 收集校验** |
| 许可证合规 | — | — | ⚠️ 快照打包 GPL 组件，合规存疑 | **仅含 MIT/BSD/ISC/Zlib 组件** |
| 权限分级 | 无 | 无 | 无 | **三级模式 + su 闸门 + Shizuku adb 桥** |
| 环境扩展 | 手动装 | apt | 死快照，不可扩展 | **21 项一键装 + AI 自助安装** |
| Termux 依赖 | 需要 Termux App | 需要 | 快照即 Termux | **零依赖**（硬编码路径已重定位） |

> 路线之间不是替代关系：Termux 系是在 Android 上**搭一个 Linux 环境**再跑 dsh；本项目把 dsh 需要的运行时**打进 APK**。**两条路线跑的是同一个 dsh**，差别在启动路径的长度。
>
> 📄 完整路线剖析见 **[docs/android-agent-routes.zh.md](docs/android-agent-routes.zh.md)**（[English](docs/android-agent-routes.md)）。

---

## 📦 安装

**从 Release 下载（推荐）**：前往 [Releases](https://github.com/Soodok/Deepseek-Harness-Local-Android/releases) 下载 APK，允许安装未知来源应用后安装。手机选 `arm64-v8a`。

**两种包怎么选**：

| 包 | 体积 | 适合 |
|---|---|---|
| **完整包**（`-release.apk`） | ~150 MB | 绝大多数人。运行时预置，装完即用，离线也能启动 |
| **简单包**（`-release-noruntime.apk`） | ~24 MB | 空间紧张，或想走云端更新运行时。首启需联网下载约 125 MB |

> 两种包**功能完全相同**，只差运行时是否内置。共用一个签名，可互相覆盖升级。

### 环境要求

- **Android 8.0 ~ 16**（`minSdk 26`）
- **WebView ≥ Chrome 85**（国产 ROM 常见的 96 实测可用；构建时注入 polyfill，把 API 门槛从 126 拉回 85）
- 存储：约 720 MB（APK + 解压后运行时）

---

## 🚀 快速上手

1. 安装 APK，首次启动走引导（语言 / 朝向 / 主题 / 权限模式）
2. 等引擎就绪（状态栏显示 `Engine ready`）
3. 在设置里填入模型 API Key，或登录 DeepSeek 账号
4. 选一个工作区，开始对话

**想做开发任务**：设置 → 扩展中心，装 Python / Git / Node 等；然后告诉 AI 要做什么。

---

## 🔧 从源码构建

```bash
# 前置：JDK 17+、Android SDK 36、Node 22、Python 3

# 1) 收集运行时（从 Termux 仓库拉 bionic 包 + 打 Android 补丁）
./scripts/build-runtime.sh --abi arm64-v8a --out _runtime_bak/rt-arm64.zip

# 2) 构建 APK
gradle :app:assembleDebug -Pabi=arm64-v8a -Ppackage=full \
       -PruntimeZip=$PWD/_runtime_bak/rt-arm64.zip

# 输出：app/build/outputs/apk/debug/app-debug.apk
```

流水线细节（去重、PTC 补丁、polyfill 注入、`BUILD.json`、索引签名、发布流程）见 **[docs/git-workflow.md](docs/git-workflow.md)** 与 **[docs/RELEASE.md](docs/RELEASE.md)**。

---

## 📚 深度文档

每一项能力都有独立文档（设计取舍、实测数据、排障清单）：

| 文档 | 内容 |
|---|---|
| [无障碍控制](docs/ACCESSIBILITY.md) | 21 种原语；多层视觉兜底、每步验证；实测 ~1.45 s/步 |
| [SSH 远程工作区](docs/SSH.md) | 内置 @xiaopb/dsh-hardssh：远端工作区、Web 终端、隧道与 `ssh_*` 工具 |
| [闭环设计笔记](docs/LOCAL_CLOSED_LOOP.md) | 端侧决策引擎的设计与演进记录 |
| [竖屏 UI 插件](docs/NARROW_UI.md) | 侧栏全宽 / 设置面板重构 / 右侧面板全屏 |
| [局域网 WebUI](docs/LAN_WEBUI.md) | 反向代理、6 位验证码、常驻提醒与关闭链路 |
| [语音输入](docs/VOICE_INPUT.md) | 双通道识别、发送去重、面板停靠与已知问题 |
| [桌宠](docs/pet/README.md) | 系统级悬浮窗：安装与限制（[使用](docs/pet/PET_GUIDE.md)）、桥插件与内部逻辑（[架构](docs/pet/PET_ARCHITECTURE.md)）、[路线图](docs/pet/PET_ROADMAP.md) |
| [通知与后台行为](docs/NOTIFICATIONS.md) | AI 操作进度、任务完成、局域网提醒、退出彻底化 |
| [复杂场景实测](docs/COMPLEX_SCENARIOS.md) | 噪声页面、跨应用任务、失败恢复的实测记录 |
| [快速路径选择](docs/FAST_PATH_PICKER.md) | 端侧 UI 元素选择与判别 |
| [Git 工作流](docs/git-workflow.md) | 合并原则、推送通道、发版与验证清单、历史事故教训 |
| [发布与签名](docs/RELEASE.md) | 正式 key 保管与备份、GitHub Secrets、debug+release 构建、edge / tag 发布 |
| [版本发布说明归档](docs/release-notes/) | 各版本 `RELEASE_NOTES_*.md`（v1.3.0 起）集中存档 |

---

## ⛔ 边界（请知悉）

- **不是端侧大模型**：模型走你配置的 API，本 App 负责 Agent 运行时
- **不是手机自动化 App**：读屏点击只是 AI 可用的工具之一，不是产品定位
- **不做金融类应用**（合规红线）
- **无障碍能力有明确边界**：不注入不可检测的输入 —— 自动化与欺诈之间那条线我们不动
- **插件生态跟着上游走**：dsh 引擎锁 `0.2.1-rc.2`，上游插件 API 变更我们需要跟进

---

## ❓ FAQ

**Q：为什么 APK 这么大？**
A：完整包里塞了整套 Node 运行时 + 工具链（125 MB 压缩、404 MB 解压后）。换来的是「装完即用、离线可启动、冷启动 3 秒」。不想这样可以用 24 MB 的简单包。

**Q：为什么不走 proot？为什么用 `targetSdk 28`？**
A：proot 要多一层用户态翻译和 rootfs 解压，直接吃掉启动速度——也就吃掉了低端设备的可用性。`targetSdk 28` 是 sideload 分发策略，与 Termux 同款：豁免 Android 10+ 的 W^X，允许直接 `execve` 私有目录下的二进制。这是冷启动能压到 3 秒的前提之一。

**Q：Android 15 / 16 能用吗？**
A：能。全部 `.so` 按 16 KB 页对齐，适配 16 KB 页内核；界面依赖系统 WebView（需 Chrome 85+）。

**Q：数据存在哪？卸载会丢吗？**
A：引擎、凭证、工作区默认在应用私有目录（`files/dsh-home`），并自动外置到共享存储（`Documents/dshdata`，需「所有文件访问」权限）。**授权后卸载重装不丢**；未授权则随应用卸载删除。

**Q：需要 Root 吗？**
A：不需要。默认普通模式即可完整使用；Shizuku 提供 adb 级能力；Root 是可选增强。

**Q：和 DSHA / 其他移植版的区别？**
A：都能跑 dsh。差别在运行层架构与启动路径——见上方[路线对比](#-路线对比)，以及 [docs/android-agent-routes.zh.md](docs/android-agent-routes.zh.md)。

---

## ⚠️ 免责声明

本项目为社区移植，与 DeepSeek 官方无隶属关系。使用本软件产生的任何后果由使用者自行承担；请遵守当地法律法规与所接入模型服务的条款。

---

## 反馈

- **Issue**：[提交问题](https://github.com/Soodok/Deepseek-Harness-Local-Android/issues)（请附设备型号、Android 版本、App 版本与复现步骤）
- **QQ 群**：1128769016
- **Telegram**：[https://t.me/+4VN3pYyH6i8yNDA1](https://t.me/+4VN3pYyH6i8yNDA1)
- **Pull Request**：欢迎。合并用 `--no-ff` 保留提交线路，规范见 [docs/git-workflow.md](docs/git-workflow.md)

---

## 🙏 贡献者

感谢每一位提交 Issue、PR 与反馈的贡献者 —— 尤其是那些把复现时间线、`/proc` 证据、`ctime` 对比一起附上的报告，它们直接促成了多个架构级修复。

<a href="https://github.com/Soodok/Deepseek-Harness-Local-Android/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=Soodok/Deepseek-Harness-Local-Android" alt="Contributors">
</a>

---

## 许可证

[MIT](./LICENSE) · 第三方组件许可见 [NOTICE](./NOTICE)
