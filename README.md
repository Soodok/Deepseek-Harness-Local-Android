# DSH Mobile · DeepSeek Harness for Android

<p align="center">
  <img src="docs/promo/dsh-mobile-github.png" alt="DSH Mobile — DeepSeek Harness for Android" width="100%">
</p>

<h3 align="center">大多数移植项目给你一个能聊天的界面。<br>我们给你一台能写代码、能编译、能接管整台手机的工作站。</h3>

<p align="center">
  <b>原生启动 2.3 秒 · 容器启动 1.33 秒 · 108 条设备路由 · 1354 项权限声明<br>
  20 套工具链 · 真 clang / 真 JDK / 真 gradle / 双 rootfs</b>
</p>

<p align="center">
  <a href="#一快是两条路都快">① 启动快</a> ·
  <a href="#二手机不是聊天对象是执行对象">② 手机权限</a> ·
  <a href="#三手机上真的能编译">③ 编译环境</a> ·
  <a href="#-完整能力清单">能力清单</a> ·
  <a href="#-安装">安装</a> ·
  <a href="README_EN.md">English</a> ·
  <a href="README.de.md">Deutsch</a>
</p>

<p align="center">
  <i>一条来自真实用户的对比反馈：<br>
  「界面做得挺好，但是没有编译环境和 root，根本没啥用。」</i>
</p>

<!-- 徽章区：左边看"有多少人在用"，右边看"项目是否健康"。 -->
[![Downloads](https://img.shields.io/github/downloads/Soodok/Deepseek-Harness-Local-Android/total?style=for-the-badge&label=downloads&color=2563eb)](https://github.com/Soodok/Deepseek-Harness-Local-Android/releases)
[![Stars](https://img.shields.io/github/stars/Soodok/Deepseek-Harness-Local-Android?style=for-the-badge&color=f59e0b)](https://github.com/Soodok/Deepseek-Harness-Local-Android/stargazers)
[![Release](https://img.shields.io/github/v/release/Soodok/Deepseek-Harness-Local-Android?style=for-the-badge&color=16a34a)](https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/latest)

[![CI](https://github.com/Soodok/Deepseek-Harness-Local-Android/actions/workflows/android-build.yml/badge.svg)](https://github.com/Soodok/Deepseek-Harness-Local-Android/actions/workflows/android-build.yml)
![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20~%2016-3ddc84?logo=android&logoColor=white)
![Engine](https://img.shields.io/badge/engine-dsh%200.2.2--rc.1-4b5563)
![Runtime](https://img.shields.io/badge/runtime-bionic%20Node.js-5fa04e?logo=nodedotjs&logoColor=white)
![License](https://img.shields.io/badge/license-MIT%20%2B%20runtime%20license-brightgreen)
[![Last commit](https://img.shields.io/github/last-commit/Soodok/Deepseek-Harness-Local-Android?color=64748b)](https://github.com/Soodok/Deepseek-Harness-Local-Android/commits/main)
[![QQ](https://img.shields.io/badge/QQ%E7%BE%A4-1128769016-12b7f5?logo=tencentqq&logoColor=white)](https://github.com/Soodok/Deepseek-Harness-Local-Android#%E5%8F%8D%E9%A6%88)
[![Telegram](https://img.shields.io/badge/Telegram-Join_group-26A5E4?logo=telegram&logoColor=white)](https://t.me/+4VN3pYyH6i8yNDA1)

---

## 三个数字，三件事

| | 我们 | 大多数同类项目 |
|---|---|---|
| **原生启动** | **2.3 秒**（低端 3 GB 设备也能跑） | 十几秒，或者直接起不来 |
| **Linux 容器启动** | **1.33 秒**，容器层只占 0.8% | 解压 rootfs，十几秒起 |
| **手机能力接入 Agent** | **108 条路由 / 42 条目录项 / 21 个独立开关** | 一个聊天框 |
| **手机端编译** | **clang + JDK 17 + gradle + cmake + rustc + go** | 无 |

下面三部分，逐条拆解这三件事背后的实现。

---

## 一、快，是两条路都快

同类方案通常只能二选一：

- **原生运行时**快，但跑不了 glibc 程序，也没有 `apk` / `apt`；
- **容器或 rootfs**能编，但启动前要先解压、挂载、初始化软件源，慢十几秒。

我们两条路都做到了快，而且**容器这一层几乎不带来额外开销**。

### 路线 A：原生 bionic —— 直接 `execve`，不套中间层

整套 Node.js 运行时被编译成 Android 原生（bionic）二进制，直接打进 APK。**它不需要「搭建」**——没有 rootfs 要铺、没有伪文件系统要挂、没有软件源要初始化。冷启动只剩三件事：解压（仅首启一次）、用 `execve` 启动 Node、等待回环端口就绪。

| 场景 | 设备 | 冷启动到引擎就绪 |
|---|---|---|
| 真机实测 | 一加 15T（Android 16） | **2.3 秒** |
| 主流中端 | 各品牌 Android | **5 ~ 6 秒** |
| 极端低端 | 紫光展锐手表（**3 GB 内存**） | **13 秒** |
| 模拟器 | x86_64 / 2.5 GB | **2.2 ~ 2.7 秒** |

对照组：基于 `proot` 的方案在不少中低端设备上**直接起不来**，在主流机型上启动平均也要十几秒；首次手工搭建 Linux 环境则需要数分钟。

> 一台 **3 GB 内存**的手表能把整套引擎跑起来——同内存下，`proot` + glibc 连 rootfs 都铺不开。这不是调参调出来的，是架构差异。

### 编译缓存：再提速约 20%

从新版本开始，两条路径都注入了 **Node.js 编译缓存**（`NODE_COMPILE_CACHE`），把 JS 模块的编译产物落到磁盘，下次启动直接复用，省掉重复解析与编译。

干净 A/B 对照（同一模拟器、x86_64、各 3 轮、每轮清场）：

| 状态 | 三轮结果 | 中位数 |
|---|---|---|
| **缓存命中** | 3652.2 / 3416.8 / 3385.7 ms | **3416.8 ms** |
| **缓存清空** | 4540.2 / 4088.8 / 4528.4 ms | **4528.4 ms** |

启动耗时减少 **1111.6 ms，约 24.5%**，缓存落盘体积为 **15 MB**。

有一个必须处理的细节：`NODE_COMPILE_CACHE` 是**懒写盘**的。不注入刷盘器时，跑完一整轮后，缓存目录也只有 **16 KB**——缓存等于不存在。所以我们内置了 `node-compile-cache-flusher.cjs`，在原生与容器两条路径中都注入它，让缓存真正落盘，达到 15 MB。

这是当前唯一还有正收益的启动优化项，已进入产线。

### 路线 B：Linux 容器 —— 端到端中位 **1.33 秒**，容器层只占 **0.8%**

这是最反直觉的一条数据。

在自研容器 `dsh_alpine` 里，**跑一条命令只要 2.4 ms**；「从零 → 进 Linux → 引擎就绪」的端到端耗时**中位数为 1.33 秒**。

拆开看这 1.33 秒花在哪：

| 环节 | 占端到端耗时 |
|---|---|
| **容器层本身**（路径翻译 + SIGSYS 处理 + ELF 加载） | **0.8%** |
| 引擎插件加载（Node 侧） | **96%** |

**结论：容器这层几乎不花时间。** 换任何 OS 级方案，瓶颈都在 Node 加载插件所占的那 96%，不在容器——而那 96% 恰恰是唯一无法绕过、也不该绕过的部分。

同一台设备（OPPO PLZ110 / Android 16 / 内核 6.12.38）横向对照：

| 方案 | 进 Linux 跑一条命令 |
|---|---|
| **我们的 `dsh_alpine`** | **2.4 ms** |
| 真 chroot（需 root） | 1.6 ms |
| `proot`（免 root） | **19 ~ 33 ms** |

我们只比需要 root 的真 `chroot` 慢**约 1 ms**（就是路径翻译与信号处理的成本），但**比 `proot` 快 8 ~ 14 倍**。

内存表现同样干净——跑引擎时与真 `chroot` 几乎等同（容器 180~186 MB vs 真 `chroot` 181~187 MB），容器 loader 自身 RSS 仅 **1.8 MB**。文件 IO 比 `proot` 快约 **30 倍**，syscall 快 **2.7 倍**。

> 换句话说：我们不是「在慢的基础上做优化」，而是**把容器这一层做到了接近零开销**。免 root，却有 root 级的性能。

### 起点为什么能这么快（六条硬约束）

| 决策 | 换来了什么 |
|---|---|
| **bionic 原生运行时**，不走 glibc、不套容器 | 省掉用户态翻译，省掉 rootfs 解压与挂载 |
| **容器用 seccomp + SIGSYS，不用 ptrace** | 省掉 `proot` 的全量 syscall 拦截与用户态模拟 |
| **编译缓存落盘**（两条路径都注入刷盘器） | 复用 JS 编译产物，再省约 1.1 秒 / 24.5% |
| 运行时**预置进 APK**（130.8 MB → 解压 418.1 MB / 15292 个文件） | 首启不用下载，装完即用，断网也能启动 |
| `targetSdk = 28`（sideload 策略） | 豁免 Android 10+ 的 W^X，可直接 `execve` 私有目录二进制 |
| **CI 收集 + SONAME 去重 + SHA 校验**，全部 `.so` **16 KB 页对齐** | 产物可复现；Android 15+ 的 16 KB 页内核直接可用 |

另有一条：前端注入 **polyfill**，把 WebView 门槛从 Chrome 126 **拉回 85**——国产 ROM 常见的 96 实测可用。

---

## 二、手机不是聊天对象，是执行对象

大多数手机 Agent 项目的能力上限是「能读屏幕、能点一下」。

我们的上限是：**只要 Android 肯给，Agent 就能用。**

**1354 项权限声明**，真机实收 **432 项**。提供 **108 条 HTTP 路由**、**42 条自描述目录项**，配有 **21 个独立能力开关**——每项能力都要用户单独点头，不是一把梭全开。

### 它能真正操作这台手机

| 层面 | 能力 |
|---|---|
| **看** | 无障碍节点树（含坐标 / 角色 / 可点性）、截图（**不弹 MediaProjection 授权框**）、OCR 兜底、帧差判断、屏幕变化监控 |
| **动** | 点按 / 长按 / 双击 / 滑动 / 拖拽 / 双指缩放 / 输入 / 按键 / 启动 App / 构造 Intent / 滚动查找 / 滚动收集 / 等待 / 条件分支 |
| **闭环** | 21 个动作原语组成的 `PICK → ACT → VERIFY → RETRY` 执行器；执行失败时会换锚点、换动作、换路径，并把失败证据（候选列表、截图、原因）交回决策层 |
| **看见它的手** | `dev hand` 在屏幕上画出 AI 即将点击的点：绿 = 点击 / 橙 = 长按 / 蓝 = 滑动，2.5 秒淡出，不吃触摸事件 |
| **感知** | 电量 / 温度 / 音量 / 内存 / 网络 / 前台应用 / 加速度 / 陀螺仪 / 光线 / 距离 / 计步 / 磁场 / 气压 |
| **上下文** | `dev context` 一次返回时间 + 屏幕 + 锁屏 + Doze + 电池 + 网络 + 前台应用 + 通知概况 + 该不该打扰 |
| **个人数据** | 日历 / 联系人 / 短信 / 通话记录（**四项独立授权**） |
| **通知** | 列表 / 聚合摘要 / **直接回复（RemoteInput）** |
| **控制** | WiFi / 蓝牙 / 音量 / 亮度 / 手电筒 / 振动 / 媒体会话 / 定时提醒 / 地理围栏 / NFC 读标签 |
| **射频** | 蓝牙扫描 + 广播 + GATT + RFCOMM、手机当 BLE 外设、手机冒充蓝牙键盘、HCI 二进制日志解析、WiFi 链路与抓包 |
| **网络** | 按 App UID 查流量、**按 UID 插防火墙规则断网**（只断目标应用，不断自己） |
| **虚拟硬件** | 通过 `/dev/uhid` 注册**真实的**键盘 / 鼠标 / 多媒体键——系统分不出它和物理设备 |
| **系统层** | `root` 门脚本提供 19 个子命令：进程 / 文件 / 属性 / 服务 / 包管理 / 设置 / 网络 / 挂载 / 日志 / 任意命令 |

### Root 不是必需，但给了就是另一台机器

三档身份，从普通到 Root：

| 模式 | 引擎身份 | 能做什么 |
|---|---|---|
| **普通** | App 沙箱身份 | 工作区、文件、Shell、Node、PTY、插件、多数常规 Android 能力 |
| **Shizuku** | App 身份 + shell 桥 | `dumpsys` / `cmd` / `pm` / `am`、部分系统操作 |
| **Root** | uid 0 | `/dev/uhid` 虚拟输入、UID 级防火墙、Doze 白名单、系统级进程与文件、底层射频 |

**Root 模式下的四条自律**（不是能力限制，是我们自己划的线）：

1. `rm` / `exec` / `prop set` / `pm uninstall` **默认 dry-run**，必须显式传 `confirm=1` 才执行
2. 关键进程保护：对 `init` / `zygote` / `zygote64` / `system_server` / `surfaceflinger` / `servicemanager` / `logd` / `vold` / `netd` 的操作一律拒绝
3. `rm` 不真删——移进隔离区 `/data/local/tmp/dsh-root-trash/<batch>/`，`root undo <batch>` 可恢复
4. **绝不修改 SELinux 策略**：可执行代码里不存在 `setenforce` / `supolicy` / `magiskpolicy` / `sepolicy-inject`

### 这些坑是真机踩出来的，都写进了代码注释

| 现象 | 真因 |
|---|---|
| 虚拟 HID 在 App 进程里打不开 | `/dev/uhid` 只放行 `u:r:ksu:s0`；App 却是 `u:r:untrusted_app`。所以虚拟 HID 走引擎侧门脚本，不经桥 |
| 创建了设备却收不到按键 | 必须留 **200 ms** 等 `InputDispatcher` 注册；急着发出的按键会被丢弃 |
| 事件抓得到，系统不响应 | 屏幕必须 **Awake**。`Dozing` 下事件能到内核（`getevent` 看得见），但 `InputDispatcher` 不派发 |
| 音量键在 Consumer 页无效 | 两页 usage 号**不通用**：音量加在 Consumer 页是 **233**，在 Keyboard 页是 **128** |
| 防火墙规则明明在链上，却读回 null | `iptables -vL` 打印的是 `owner UID match 10360`，**不是**命令行的 `uid-owner`——第一版按后者 grep，结果全空 |
| 断网后 `ping` 全丢包，判不出成败 | `ping` 需要 ICMP raw socket，普通 uid 拿不到，**有没有规则都 100% 丢包**。必须读规则命中计数 |

### 自描述，不需要背文档

```bash
dev                    # 列出 9 组能力目录
dev <能力>             # 看某个能力的用法
dev <能力> k=v         # 直接执行
dev api GET /dev/help  # 原始目录 JSON（含 params / body / limits）
dev raw                # 真实路由表（可核对目录是否漂移）
```

`dev` 是纯渲染器，从服务端拉目录，**不在脚本里硬编码能力名**。MCP 工具表也从同一份目录派生——**42 个工具，新增端点会自动出现在工具表里，不用维护第二份清单。**

---

## 三、手机上真的能编译

这是同类项目最常缺、我们也最想强调的一块。

**20 套工具链，一键装，装完就能编。**

### 不是「能跑脚本」，是完整的编译链

| 分类 | 工具链 | 提供的二进制 |
|---|---|---|
| **C / C++** | Clang / LLVM | `clang` `clang++` **`make`** |
| **JVM** | OpenJDK 17 | `java` **`javac`** `jar` |
| **Android 构建** | android-buildtools | `aapt` `aapt2` **`apksigner`** **`gradle`** `d8` |
| **构建系统** | CMake | `cmake` |
| **Rust** | Rust | `rustc` `cargo` |
| **Go** | Go | `go` `gofmt` |
| **Python** | Python | `python3` `pip3` |
| **其他语言** | Ruby / PHP / Lua / Perl | `ruby` `gem` `php` `lua` `perl` |
| **版本控制** | Git | `git` |
| **打包** | Archivers | `zip` `unzip` `xz` |
| **媒体** | FFmpeg / ImageMagick | `ffmpeg` `ffprobe` `magick` `convert` |
| **数据库** | SQLite | `sqlite3` |
| **远程** | OpenSSH / ADB | `ssh` `scp` `adb` `fastboot` |
| **本地推理** | llama.cpp | `llama-server` `llama-cli` `llama-embedding` |
| **编辑** | Vim | `vim` |

### 三条编译链，都在这台手机上跑通

**① 原生 C / C++** —— clang + make + cmake，能编出 `.so`、能编 NDK 工程。

**② Java / Android** —— OpenJDK 17 + gradle + aapt2 + apksigner + d8。**这是一条真能打出 APK 的完整编译链**，我们用它在手机上编过本项目的扩展。

这条路有一个很容易踩的坑：`android-buildtools` 内嵌的 gradle 启动脚本在 Android 的 toybox 环境里是**开箱即坏**的——`/system/bin/xargs` 是 toybox 版本，不剥引号，会把 `"-Xmx64m"` 原样传给 JVM，报 `Could not find or load main class "-Xmx64m"`；内嵌 JDK 还把 `user.home` 与 `java.io.tmpdir` 硬编码为某个外部环境的固定路径。我们在安装期打补丁修掉了这些问题（issue #86），现在开箱可用。

**③ 更多语言** —— rustc / cargo、go、python、ruby、php、lua、perl，各自采用独立闭包安装，并进行 SHA-256 校验和原子发布。

### 兼容性：两条腿走路，不留缺口

第三条重点中，兼容性是最容易被忽略、却最实用的一层：

| 你要跑的 | 走哪条 |
|---|---|
| 常规 Node / Python / Go / Rust / C 工具链 | **原生 bionic**（2.3 秒启动，本地工具链直接可用） |
| 只发布 glibc 构建的预编译 CLI | **Linux 容器**（Ubuntu rootfs，`apt` 装依赖） |
| 需要 `apk add` / `apt install` 装任意系统软件 | **Linux 容器**（Alpine 或 Ubuntu） |
| 需要 jemalloc / libnuma 这类 glibc 专有库 | **Linux 容器**（glibc 2.39 完整用户态） |

**两条路线共用同一套引擎代码、同一个 `$DSH_HOME`、同一份工作区。** 切换路线不需要重装、不需要重新配置、不需要第二个 App。

同类项目通常只有一条路：要么原生（跑不了 glibc 程序），要么容器（慢十几秒且常常起不来）。**我们两条都给了，而且两条都快。**

### 需要 glibc 时：Linux Workspace

**可选加装，默认关闭。** 这是一套**自研的免 root 用户态容器**：

| 特性 | 实现方式 |
|---|---|
| 不依赖 `ptrace` | seccomp BPF 捕获指定 syscall + `SIGSYS` handler |
| 不依赖 user namespace | 固定基址 `0x2000000000`、`ET_EXEC` 非 PIE、自写 ELF loader |
| 不依赖 `chroot` | 用户态路径翻译，`/proc` `/sys` `/dev` 宿主透传 |
| 不要求 Root | 全程在 App 沙箱内运行 |
| 双向端口映射 | seccomp 同时改写 guest 与 host 侧端口 |

首次启动时，容器通过 `self-reexec` 完成 guest 用户态接管，之后运行的就是一个正常的 Linux 进程树。

两套 rootfs 任选：

| 发行版 | libc | 包管理器 | 来源 | 压缩 | 解压 |
|---|---|---|---:|---:|---:|
| **Alpine 3.22** | musl | `apk` | **APK 内置，离线可用** | 3,979,016 B | 8,510,313 B |
| **Ubuntu 24.04 base** | glibc 2.39 | `apt` | 首次联网下载 | 29,936,675 B | 100,784,109 B |

实测：容器启动 **31 ms**；Ubuntu 冷启 **2.6 ~ 4.2 秒**；Alpine 冷启 **0.1 秒级**。Ubuntu 比 Alpine 慢一个量级，是由发行版自身开销（glibc + init 脚本链）决定的，不是加载器缺陷。

**注意**：这不是安全沙箱。它保证兼容性和性能，**不隔离不可信代码**。

### 能编，就能跑

编完直接起本地服务，在手机内的 WebView 中预览：

> 「在当前工作区建个项目，编译它，起个开发服务器，把 127.0.0.1 预览链接发我。」

Agent 会真的执行：建目录 → 写代码 → `make` / `gradle build` → 起服务 → 给你回环链接。

**不提供 X11 / Wayland 桌面**，图形结果走本地 HTTP + WebView 预览。

---

## 对比：同类方案差在哪

| | 通用聊天型移植 | 手工搭 Linux 环境 | `proot` 容器 | **本方案** |
|---|---|---|---|---|
| 需要 Root | 否 | 否 | 否 | **否**（要 Root 时有完整通道） |
| 需要额外 Linux 环境 | 否 | **是** | 常需要 | **否** |
| 原生启动 | 视实现 | 分钟级 | 十几秒起，弱机起不来 | **2.3 ~ 3.4 秒** |
| 容器启动 | 无容器 | 分钟级 | 慢 | **1.33 秒端到端（容器层 0.8%）** |
| 免 root 容器性能 | — | 不适用 | 基准 | **2.4 ms/命令，接近真 chroot** |
| 编译缓存加速 | 无 | 无 | 无 | **有（约 24.5%）** |
| 手机能力接入 Agent | 基本没有 | 需自己写 | 需自己写 | **108 路由 / 42 目录 / 23 开关** |
| 手机端编译 C / C++ | 没有 | 有 | 有 | **有** |
| 手机端编译 Java / APK | 没有 | 部分 | 部分 | **有（JDK 17 + gradle + aapt2 + apksigner）** |
| glibc 兼容 | 无 | 是 | 是 | **容器可选（Alpine + Ubuntu 双 rootfs）** |
| Root 级能力 | 没有 | 需外挂 | 需外挂 | **19 个子命令 + 四道自律** |
| 装完即用 | 是 | 否 | 否 | **是** |

> 那条用户反馈再贴一次：**「界面做得挺好，但是没有编译环境和 root，根本没啥用。」**
>
> 我们做的事很简单：**界面之外，把「能干活」这一层补满。**

---

## 📖 完整能力清单

以下是按分组列出的全部能力。前七组对应 `GET /dev/help` 的自描述目录（**42 条目录项 / 36 个唯一路径**），另附几个目录之外但真实存在的路由（`/dev/help`、`/dev/perms/status`、`/dev/perms/revoke`）。

### 感知（5 项）

| 端点 | 能力 |
|---|---|
| `GET /dev/status` | 设备总览：电量 / 温度 / 音量 / 屏幕 / 内存 / 网络 / 前台应用 |
| `GET /dev/ping` | 桥与无障碍是否在线（最快的一次健康检查） |
| `GET /dev/sensors` | 传感器读数：`type=accel\|gyro\|light\|proximity\|step\|magnet\|pressure` |
| `GET /dev/notifications` | 当前通知列表（需通知使用权），`limit=20` |
| `GET /dev/media` | 当前媒体会话：标题 / 艺术家 / 进度 / 播放状态 |

### 个人数据（4 项，各自独立授权）

| 端点 | 能力 |
|---|---|
| `GET /dev/calendar` | 查日程，`from` / `to` 为毫秒时间戳 |
| `GET /dev/contacts` | 搜通讯录，`q=<关键词> limit=20` |
| `GET /dev/sms` | 读短信，`q=<关键词> limit=20` |
| `GET /dev/calls` | 通话记录，`limit=20`（需 `READ_CALL_LOG`） |

### 控制（6 项）

| 端点 | 能力 |
|---|---|
| `POST /dev/control` | `op=wifi\|bt\|volume\|brightness\|torch\|vibrate`；WiFi / 蓝牙在 Android 10+ 会返回 `user_confirmation_required` |
| `POST /dev/clipboard` | 剪贴板读写 |
| `POST /dev/media` | 媒体控制：`op=play\|pause\|toggle\|next\|previous\|stop\|seek\|volume\|stream-volume` |
| `POST /dev/sms` | 打开短信发送界面（**不直接发送**，由用户确认） |
| `POST /dev/notify-reply` | 代用户回复可回复的通知（RemoteInput） |
| `POST /dev/calendar` | 新增日程 |

### 调度（3 项）

| 端点 | 能力 |
|---|---|
| `POST /dev/remind` | 建定时提醒，`in_seconds=` 或 `at=<ISO8601>`，`repeat={"kind":"once\|daily\|weekday"}`；不依赖网络，重启后仍在 |
| `GET /dev/remind-list` | 列出全部提醒 |
| `POST /dev/remind-cancel` | 取消提醒 |

### 底层射频与感知扩展（16 项）

| 端点 | 能力 | 需 Root |
|---|---|:---:|
| `POST /dev/bt` | 蓝牙底层：`op=status\|enable\|disable\|scan\|advertise\|connect\|services\|read\|write\|rfcomm\|log`；`log` 支持 `format=binary` 解析 HCI 抓包 | 部分 |
| `POST /dev/wifi-radio` | WiFi 底层：`op=status\|scan\|enable\|disable\|link\|capture` | 抓包需 |
| `POST /dev/net` | 按 App（UID）查流量 / 断网；查询不需 root，封锁需 | 封锁需 |
| `POST /dev/ble` | 手机当 BLE 外设（GATT Server），别的设备连上来读写你开放的特征值 | |
| `POST /dev/bt-hid` | 手机冒充蓝牙键盘控制别的设备（HID Device，部分 OEM 会拦） | |
| `POST /dev/pulse` | 通过振动传递摩斯电码：不用亮屏、不出声就知道结果 | |
| `GET /dev/context` | 上下文总线：时间 / 屏幕 / 锁屏 / Doze / 电池 / 网络 / 前台应用 / 通知概况 + `should_nudge` | |
| `POST /dev/nudge` | 主动提醒闸门：静音时段 / 息屏 / 锁屏 / Doze / 同一 key 的 30 分钟冷却，命中任一条件即拒，并回报具体原因 | |
| `POST /dev/hand` | 「AI 的手」可视化：`op=tap\|path\|clear\|hide\|status\|debugbg` | |
| `GET /dev/notify-digest` | 通知摘要：按 App 聚合计数 + 最近一条的时间（**不返回正文**） | |
| `POST /dev/scene` | 传感器场景推断：dark / covered / outdoor / still / moving / walking / running / altitude_change | |
| `POST /dev/screenwatch` | 屏幕变化检测：只回 `changed_ratio` / `mean_delta` / 变化网格，**不存视频也不存图** | |
| `POST /dev/doze` | 电池优化白名单自助：`op=add\|remove\|list`，并回报是否真的生效 | |
| `GET /dev/nfc` | NFC 硬件状态：硬件是否存在 / 系统是否开启 / 前台读标签是否已启动 | |
| `POST /dev/nfc` | NFC 前台读标签（NDEF 文本与原始字节）；**无刷写、无卡模拟** | |
| `POST /dev/geofence` | 地理围栏：`op=list\|add\|remove\|clear` | |

### 屏幕与交互（6 项）

| 端点 | 能力 |
|---|---|
| `POST /dev/dialog` | 弹窗检测：用几何判据（底部成对按钮）找出系统弹窗与安全按钮 |
| `POST /dev/verify` | 校验某个动作是否真的生效（前后屏幕对比），`expect=` / `gone=true\|false` / `timeout_ms=` |
| `GET /dev/record` | 录屏状态：是否拿到授权 / 是否正在录屏 / 已录多久 |
| `POST /dev/record` | 屏幕录制；`op=audio` 时用 `AudioPlaybackCaptureConfiguration` 录制其他 App 的音频 |
| `GET /dev/hid` | 虚拟 HID 设备状态：创建状态 / 设备类型 |
| `POST /dev/hid` | 虚拟 HID 设备：注册真实外设键盘 / 鼠标 / 多媒体键并注入按键；`op=status\|create\|key\|tap\|text\|mouse\|destroy` |

### 权限自助（2 项，另含 revoke 子路由）

| 端点 | 能力 |
|---|---|
| `GET /dev/perms` | 权限总览：声明数 / 已获授权数 / 尚缺数（另有 `GET /dev/perms/status` 看进度详情） |
| `POST /dev/perms` | 授予权限，body `{"force":true}` |
| `POST /dev/perms/revoke` | 撤销指定权限，body `{"perms":[...]}` |

### 扩展与诊断（1 项）

| 端点 | 能力 |
|---|---|
| `GET /diag` | 桥自诊断：端口 / token / 无障碍 / 各能力开关 |

### 21 个独立能力开关

每项能力都要用户单独授权，默认全关。开关保存在 `capabilities` 偏好设置中，键名前缀为 `cap.`：

| 开关 | 覆盖能力 |
|---|---|
| `status` | 设备总览、上下文总线 |
| `sensor` | 传感器读数、场景推断 |
| `notify` | 主动提醒（nudge） |
| `notify_read` | 通知列表、通知摘要 |
| `notify_action` | 代用户回复通知 |
| `clipboard` | 剪贴板读写 |
| `control` | 硬件开关、振动、Doze 白名单、弹窗检测、动作校验、「AI 的手」 |
| `wifi` | WiFi 底层 |
| `bt` | 蓝牙底层、BLE 外设、蓝牙 HID |
| `sched` | 定时提醒 |
| `media` | 媒体会话读写 |
| `personal_calendar` | 日历读写 |
| `personal_contacts` | 通讯录 |
| `personal_sms` | 短信读写 |
| `personal_calls` | 通话记录 |
| `personal` | 个人数据总开关 |
| `rf` | NFC、地理围栏 |
| `screen_capture` | 屏幕变化监控、录屏 |
| `hid` | 虚拟 HID 设备 |
| `net` | 按 UID 查流量与断网 |
| `root` | 系统级门脚本 |

### 33 个门脚本

门脚本是给 AI 直接调用的命令行入口，比 `dev api` 更省 token、更适合脚本化：

| 门脚本 | 用途 |
|---|---|
| `dev` | 统一自描述入口：能力目录、用法、执行、直调 API、查路由表 |
| `scr` | 屏幕脚本：dump / shot / tap / input / swipe / key / find / changed / batch |
| `loop` | 本地闭环执行器：多步计划一次下发，`PICK → ACT → VERIFY → RETRY` |
| `fast` | 无障碍操作期间的「思考开关」，临时关掉干扰 |
| `root` | 系统级 19 个子命令：`info` `ps` `kill` `cat` `ls` `rm` `undo` `cp` `mv` `chmod` `chown` `prop` `svc` `pm` `settings` `net` `mount` `journal` `exec` |
| `hid` | 虚拟 HID 一次性执行器：`probe` / `create` / `tap` / `text` / `key` / `mouse` |
| `mcp` | 设备 MCP server（stdio JSON-RPC）：`serve` / `list` / `selftest` / `probe` |
| `psx` | 列进程 |
| `killx` | 按 pid 或名称模式结束进程 |
| `say` | 朗读文本 |
| `media` | 控制当前正在播放的媒体 |
| `notify` | 发通知 |
| `notify-reply` | 代用户回复一条可回复的通知 |
| `remind` | 定时提醒 |
| `bt-radio` | 蓝牙应用层：扫描 / 广播 / GATT / RFCOMM |
| `wifi-radio` | WiFi 底层（基于 `iw`）：扫描（含隐藏 SSID）/ 链路 / 抓包 |
| `device-control` | 硬件开关 |
| `device-status` | 一次性设备快照 |
| `device-ping` | 最快的健康检查（桥 + 无障碍） |
| `dev-calendar` / `dev-clip` / `dev-contacts` / `dev-sms` / `dev-phone` | 个人数据各端点 |
| `dev-dialog` / `dev-verify` | 弹窗检测与动作校验 |
| `dev-geofence` / `dev-nfc` | 地理围栏与 NFC |
| `dev-record` / `dev-sensors` | 录屏与传感器 |
| `getperm` / `grantperm` / `revokeperm` | 权限查询、授予、撤销（只读 / 需 Root） |
| `_dsh_http.sh` | 公共库：用 bash 内建 `/dev/tcp` 实现 HTTP 客户端，不是独立入口 |

### 21 个动作原语

`loop` 执行器支持的完整动作集：

`tap` `tap_text` `double_tap` `long_press` `swipe` `drag` `pinch` `input` `key` `launch` `intent` `scroll` `scroll_find` `scroll_collect` `set_switch` `collect` `repeat` `repeat_until` `back_to` `sleep` `noop`

---

## 📦 安装

### 1. 确认架构

```bash
adb shell getprop ro.product.cpu.abi
```

`arm64-v8a` → 手机包；`x86_64` → 模拟器包。

### 2. 选包

| 包 | 体积 | 代价 | 适合 |
|---|---:|---|---|
| **完整包**（`-release.apk`） | **~161 MB** | 运行时预置，装完即用、离线可启动 | 绝大多数人 |
| **简单包**（`-noruntime.apk`） | **~34 MB** | 首启联网下载 130.8 MB 运行时（解压后 418.1 MB） | 存储紧张 / 想独立更新运行时 |
| 源码构建 | 视参数 | 需 JDK 17 / SDK 36 / NDK / CMake / Node / Python | 开发者 |

两个包跑的是**完全同一套引擎**，差别只是运行时在 APK 里还是在网络上。

### 3. 首次启动

1. 引导页选语言 / 朝向 / 主题
2. 选运行模式：**普通**（不需要 Root）/ Shizuku / Root
3. 等运行时就位（完整包已内置，只需首次解压；简单包此时联网下载）
4. 配置模型 API Key 或登录模型服务
5. 选工作区

第一次可以这样测：

> **「在当前工作区建个 `hello.c`，用 clang 编出来跑一下，把输出发我。」**

### 4. 想操作其他 App

到系统设置里开启 `DSH Screen Tap` 无障碍服务。Android 13+ 还要确认「允许完全控制设备」。**App 不能静默开启这个权限，必须由你手动点。**

### 5. 想用 Linux 容器

设置里切到 Linux 模式，首次会部署 Alpine（内置，离线）或 Ubuntu（需下载约 30 MB）。两条路径共用同一份工作区与配置。

---

## 🔧 从源码构建

```bash
# 前置：JDK 17+、Android SDK 36、Node 22、Python 3

# 1) 收集运行时（拉 bionic 包 + 打 Android 适配补丁）
./scripts/build-runtime.sh --abi arm64-v8a --out dist/runtime-arm64-v8a.zip

# 2) 构建 APK
./gradlew :app:assembleDebug -Pabi=arm64-v8a -Ppackage=full \
  -PruntimeZip="$PWD/dist/runtime-arm64-v8a.zip"

# 输出：app/build/outputs/apk/debug/app-debug.apk
```

Windows Git Bash 用 `gradle.bat` 替代 `./gradlew`。

不打包运行时（体积小，首启要下载）：

```bash
./gradlew :app:assembleDebug -Pabi=arm64-v8a -Ppackage=noruntime
```

运行时流水线细节见 [docs/CI_RUNTIME_PIPELINE.md](docs/CI_RUNTIME_PIPELINE.md)。

---

## 📚 深度文档

| 文档 | 内容 |
|---|---|
| [docs/CAPABILITY_MAP.md](docs/CAPABILITY_MAP.md) | 手机能力全量盘点、权限档位、实现状态 |
| [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md) | 无障碍控制、视觉兜底、失败恢复 |
| [docs/CI_RUNTIME_PIPELINE.md](docs/CI_RUNTIME_PIPELINE.md) | 运行时收集、去重、校验、发布 |
| [docs/RUNTIME_SWAP.md](docs/RUNTIME_SWAP.md) | 运行时两阶段换装与混合态恢复 |
| [docs/LAN_WEBUI.md](docs/LAN_WEBUI.md) | 局域网 WebUI 代理与验证码 |
| [docs/SSH.md](docs/SSH.md) | SSH 远程工作区 |
| [docs/android-agent-routes.zh.md](docs/android-agent-routes.zh.md) | Android Agent 路线对比长文 |
| [docs/RELEASE.md](docs/RELEASE.md) | 签名、构建、发布流程 |

---

## ⛔ 边界（请知悉）

**不是端侧大模型。** 模型不在 APK 里，走你配置的 API 或模型服务。

**不是安全沙箱。** Linux Workspace 也一样——它保证兼容性和性能，**不隔离不可信代码**。Root 模式下 Agent 拥有整个设备的读写能力。

**有些权限静默不了**（系统不让，会返回 `user_confirmation_required`，绝不假装成功）：无障碍服务、MediaProjection、Android 13+ 的 WiFi / 蓝牙开关、部分 OEM 的 HID Profile。

**长任务不等于永不中断。** 前台服务 + 监督器尽力保活，但用户强停、极端省电策略、厂商冻结后台（如 `OplusHansManager`）、设备重启、Root 操作杀进程都会断。断了要重新派发。

**无障碍读不全所有界面。** 纯 Canvas、游戏画面、部分受保护页面没有可用节点树，程序会按层降级（节点树 → OCR → 图标几何 → 帧差），但仍不能把所有图形界面变成可靠的结构化信息。

**不该拿它做的事。** 不要用虚拟 HID / 无障碍 / Root 绕过风控、欺骗用户、操作未授权设备。项目**不提供**让注入输入「不可检测」的实现。

---

## ❓ FAQ

**需要 Root 吗？** 不需要。普通模式已经能跑引擎、编译、读文件、用多数常规能力；Root 只是把系统级那一层也打开。

**Linux Workspace 需要 Root 吗？** 不需要。它用 seccomp 在 App 沙箱内加载 rootfs，不依赖 Root / `ptrace` / user namespace / `chroot`。

**真的能在手机上编 APK 吗？** 能。OpenJDK 17 + gradle + aapt2 + apksigner + d8 全套，装 `android-buildtools` 扩展即可（其中的 gradle 启动脚本我们已按 issue #86 修好）。

**完整包 161 MB 装了什么？** 整套 bionic Node 运行时——解压后 418.1 MB、15292 个文件。简单包只带清单，首启下载同一个 zip。

**为什么不走 `proot`？** 它多了一层用户态翻译，还要解压 rootfs，直接吃掉启动速度。我们用 seccomp 做同样的事，省掉 `ptrace` 那一层：只比需要 root 的真 `chroot` 慢约 1 ms，比 `proot` 快 8~14 倍。

**Alpine 和 Ubuntu 选哪个？** 默认 Alpine（小、离线、快）；需要 glibc-only 的预编译程序或 `apt` 生态，就选 Ubuntu。

**能力「存在」但 Agent 不知道？** 五个条件必须全部满足：① 实现已注册进桥；② 已登记进能力目录；③ 能力开关已由用户授权；④ 环境说明书已升级；⑤ 引擎已重启。自检：

```bash
dev
dev api GET /dev/help
dev api GET /dev/routes
```

**怎么从电脑访问手机 WebUI？** 在设置里开启局域网 WebUI，启动代理，配合 6 位一次性验证码访问。**这暴露的是能操作文件、执行命令的 Agent，只在可信网络用，用完就关。**

**和手工搭 Linux 环境能一起用吗？** 能。跑的是同一个引擎，差别只在运行层。

---

## ⚠️ 免责声明

独立社区项目，与 DeepSeek 官方**无隶属关系**。

只在你拥有或明确获授权的设备上使用。普通 / Shizuku / Root 三档模式的系统边界完全不同；Root 模式下 Agent 生成的命令可能修改或删除系统文件、应用数据、网络规则、设备配置。

使用者自行承担以下风险及后果：数据丢失、应用或系统损坏、设备无法启动、账号或服务条款风险，以及 Root / Shizuku / 无障碍 / 自动化带来的后果。

模型服务商的 API 使用、数据处理、计费规则由你配置的服务端点决定。

---

## 反馈

- [GitHub Issues](https://github.com/Soodok/Deepseek-Harness-Local-Android/issues)
- QQ 群：1128769016
- [Telegram](https://t.me/+4VN3pYyH6i8yNDA1)

提 issue 请附：设备型号、Android 版本、CPU ABI、版本号、当前运行模式、相关权限状态、`logcat`、最小复现步骤、失败时的原始输出。

---

## 🙏 贡献者

感谢所有提 Issue、提供复现日志、参与真机测试、提交 PR 的贡献者。完整名单见 [Contributors](https://github.com/Soodok/Deepseek-Harness-Local-Android/graphs/contributors)。

也要感谢上游与生态：DeepSeek Harness、AOSP、bionic 工具链、Node.js、ripgrep、OpenSSH、SQLite、FFmpeg、llama.cpp。

---

## 许可证

除特别声明外，仓库内容（Kotlin、Java、XML、脚本、探针）均使用 [MIT License](LICENSE)。

`app/src/main/cpp/dsh_alpine.c` 及其运行时实现使用**专有许可证**，条款见 [dsh-linux-runtime LICENSE](https://github.com/Soodok/dsh-linux-runtime/blob/main/LICENSE)。**专有许可不等同于仓库根的 MIT。** 第三方组件原始许可证见 [NOTICE](NOTICE)。

修改 `dsh_alpine` 时必须遵守其专有许可证，且不得引入 GPL / AGPL 或与本项目许可冲突的代码。
