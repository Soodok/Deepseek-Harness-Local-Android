# DSH Mobile （Deepseek-harness-Mobile)

<p align="center">
  <img src="docs/promo/dsh-mobile-github.png" alt="DSH Mobile — DeepSeek Harness for Android" width="100%">
</p>

**DeepSeek Harness 的 Android 完整移植 —— 官方 dsh 引擎原样跑在手机沙箱里，无需 Root、无需 Termux、无需电脑。**

> 🌐 [English](README_EN.md) · [Deutsch](README.de.md)

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

[中文](#简介) · [English](README_EN.md)

---

## 简介

DSH Mobile 是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（DeepSeek 开源的 Agent 框架）的 **Android 完整移植**。完整的 Node.js Agent 引擎运行在应用沙箱内，监听 `127.0.0.1` 回环——会话、凭证、工作区**全部留在手机上**，装完即用；是否有模型请求出设备，取决于你配置的模型服务地址。

**这是**：官方 dsh 引擎（锁定 `0.2.1-rc.2`）+ 自建 Android 运行环境。插件体系、WebUI、工具调用链全部来自上游，本项目负责运行层：bionic 版 Node.js 运行时、前台服务保活、三级权限、扩展中心。**桌面上 dsh 能做的事，这里都能做。**

**这不是**：不是自研 Agent 框架，不是端侧大模型 App（模型走你配置的 API），不是手机自动化 Agent（读屏点击是交给 AI 的工具之一），**也不是 proot / Ubuntu 容器方案**——不套 Linux 容器、不装发行版 rootfs，Node.js 运行时与整套工具链都以 Android 原生（bionic）二进制直接打进 APK，装完即用。

## ⚡ 性能实测

| 指标 | 实测值 | 环境 |
|---|---|---|
| **冷启动到引擎就绪** | **< 10 秒** | 一加 15T（Android 16）真机 |
| **多服务并行内存** | **< 400 MB** | 引擎 + 多服务并行 |
| **兼容版本** | **Android 8.0 → 16** | 需 WebView ≥ Chrome 85 |
| **APK 体积** | **约 144 MB** | 含完整 Node.js 运行时 + 19 项扩展的工具链闭包 |
| **安装后占用** | **约 500 MB** | 运行时解压后 |

对比：Termux 快照类方案首次启动通常需**数分钟**（解压 + 手动初始化）；本项目的运行时预置在 APK 内，装完即用。proot 系方案（Ubuntu 容器）APK 看着小，但首次使用要另下 500MB～2GB 的 rootfs。

## ✨ 核心能力

### 🔌 扩展中心：把手机变成可扩展工作站

内置扩展中心提供 **Python、Go、Rust、Clang、OpenJDK、Git、Ruby、PHP、Perl、Lua、SQLite、FFmpeg、ImageMagick、OpenSSH、ADB、Vim** 等 **19 项环境扩展**：一键下载、支持卸载，三态管理与行内进度让安装状态始终可见。

- 国内镜像直连并自动切换（清华 TUNA → 中科大 → 北外 → Termux 官方）
- 自动解析依赖闭包，逐包 SHA-256 校验并原子发布，避免半套环境进入运行态
- 安装后自动修复 shebang，让 `env python3` 一类脚本能直接找到解释器
- Agent 也能自装扩展并自动激活；Root 模式下已实测自装 Android SDK 命令行工具

这意味着手机不是一次性快照：需要什么环境就补什么环境，不再需要的扩展也可卸载。

### 🧩 社区插件：把 dsh 生态带到手机上

设置 → 扩展 → **社区插件**：搜索 npm 上的 dsh 生态插件，一键安装或卸载，全程不离开 App。

- 搜索结果按兼容性过滤，减少装上才发现不能用的试错
- 安装过程显示进度与失败原因，完成后立即出现在 WebUI 插件面板
- **插件扩展 Agent 的能力，扩展中心提供命令行环境**：两套机制各司其职，按需组合

### 🔐 内置 SSH 远程工作区：手机连服务器改代码

默认内置 [@tiphareth/dsh-hardssh](https://github.com/tiphareth0/dsh-hardssh)（构建时由 CI 从 GitHub
固定提交拉取源码并编译进 runtime，**插件源码不在本仓库**）：

- **左侧栏「SSH 工作区」**：添加服务器（支持从 `~/.ssh/config` 导入），把远端目录绑定为工作区；绑定后会话里的 `read` / `write` / `edit` / `bash` **直接跑在服务器上**，改代码、跑测试、看日志都不用离开手机
- **本地工作区**：Android 无系统文件夹框，自动使用应用内目录浏览器（内部存储 / Documents / 应用数据快捷入口；0.2.10 起按 DSH 样式重绘，并支持**新建文件夹**）
- **SSH 私钥**：表单内直接“导入私钥”或“生成新密钥”并展示公钥；私钥存应用私有 `.ssh`（0600），也可手动放到 `Documents/dshdata/ssh`
- **右侧栏「SSH」**：Web 终端（xterm）、文件上传下载、本地端口转发隧道、远端命令
- **Agent 工具**：`ssh_list` / `ssh_exec` / `ssh_upload` / `ssh_download` / `ssh_tunnel` / `ssh_cluster`，以及 `remote_ls` / `remote_search` / `remote_status`
- 主机与凭据留在应用私有目录；引擎仍只监听 `127.0.0.1`，远程能力来自出站连接
- 升级插件只需改 `.github/workflows/android-build.yml` 里的 `HARDSSH_REF`

详见 [docs/SSH.md](docs/SSH.md)。

### 🖥️ 完整 Agent 任务执行：不是只能聊天的壳

官方 dsh 引擎（锁定 `0.2.1-rc.2`）连同 Node.js 运行时和工具链完整运行在 Android 应用沙箱里。Agent 可以在手机上执行真实的文件、命令与项目工作流；会话、凭证和工作区留在设备本地，手机本身就是一台可随身携带的 Agent 工作站。

**即时预览成果**：Agent 起本地 HTTP 服务，返回 `http://127.0.0.1:端口` 链接，点开就是手机里生成的页面或服务，不用把项目搬回电脑才能验收。

### 💾 数据位置：引擎和工作区留在手机私有存储

引擎只监听 `127.0.0.1`；会话、凭证、工作区**全部留在手机私有存储**，模型请求是否离开设备取决于你配置的模型服务地址。

- 会话与工作区放在**外部存储的 `Android/data/app.dsh.mobile/files/dshdata`** —— 文件管理器里可见、可直接复制备份
- 老版本升级：私有目录里的数据**自动迁移**过去（复制 → 核对条目数 → 删旧，数量不一致就保留原数据）
- 外部目录不可用时自动回退应用私有目录，功能不受影响（此时卸载会丢数据）
- ⚠️ **注意**：`Android/data/` 属于应用专属外部目录，**卸载应用时系统会一并清理**。需要长期保留请定期把该目录复制出来；`profiles/`（含凭证）始终留在私有目录，不随数据外置

需要从电脑使用界面时，走下面的局域网 WebUI 显式开关；它是独立代理，不会改变引擎只监听回环地址的设计。

### 🌐 从电脑打开原生 WebUI（局域网）

设置 → 扩展 → **局域网访问 WebUI**：开启后，同一网络下的电脑访问 `http://<手机IP>:3090`，打开的是**手机里正在运行的原生 WebUI**，不是另做一套远程界面。

- 每次开启随机生成 **6 位验证码**，自动复制且只显示一次
- 通知栏常驻「局域网 WebUI 已开启」提醒，带关闭按钮；关闭或退出引擎会真正停止代理进程，普通模式也有效
- 代理只在应用层补齐非安全上下文的四道门：`crypto.randomUUID`、回环判定、`/api` 同源检查、WebSocket 升级；**不修改引擎文件**
- 上游引擎本身拒绝绑定 `0.0.0.0`，必须由用户显式开启应用层代理，默认不会意外对外开放

详见 [docs/LAN_WEBUI.md](docs/LAN_WEBUI.md)。

### 🎙️ 语音输入：说完就能改、改完就能发

点悬浮球麦克风，底部可编辑面板会实时上屏识别结果；可手动修改后发送到当前对话。通道按 **系统识别 → 云端 OpenAI 兼容 API** 优先选择，也可安装开源离线识别插件 [dsh-asr-service](https://github.com/Soodok/dsh-asr-service)。

发送由 Android 侧时序驱动，App 退到后台也能发；写入 WebUI 原生输入框后会回读校验并多层去重，避免长文本重复提交。已知边界是部分机型发送瞬间仍可能出现 1–2 帧系统合成残影，尚未完全消除。

### 🫧 悬浮窗 · 保活 · 无障碍：这才是手机端的最强卖点

- **悬浮窗与后台反馈**：可拖动状态球展示引擎状态与最近动作；AI 连续操作从第 3 步起，通知栏会原地更新「正在操作设备 · 第 N 步」，停手 10 秒自动消失；任务完成和局域网开启也各有通知
- **21 种动作原语**：`tap`、`tap-id`、`tap_text`、`swipe`、`scroll`、`scroll_find`、`scroll_collect`、`repeat`、`repeat_until`、`double_tap`、`long_press`、`pinch`、`drag`、`back_to`、`input`、`key`、`launch`、`intent`、`set_switch`、`noop`、`collect`，另有 `sleep`
- **本地闭环**：每一步走 **PICK → ACT → VERIFY → RETRY**；支持 `when` 条件分支、乐观执行和场景记忆，成功路径可以复用，不必每次从头摸索
- **多层视觉兜底**：无障碍节点树 → OCR → 图标识别 → 帧差；每步都验证，失败会换锚点、换动作或换路径，并把失败证据回传，而不是盲点到底
- **速度有实测**：单步闭环约 **1.45 秒**（点击 1.13 秒 + verify 69ms）；compact verify 快路径约 **5ms**，体积约为完整 JSON dump 的 **1/7**；OCR 链 **326–393ms**，相比优化前 **1370–1588ms** 约快 4 倍；settle 按页面状态自适应，为 **355–1566ms**
- **视觉成本可控**：64×64 帧差单次比对 < 5ms；含截图 IPC 的帧成本 p50 约 55ms。fast mode 只在第一步思考，后续步骤交给本地闭环执行
- **真实场景已跑通**：综合任务实测 **7 次调用 / 64.7 秒**完成；7 页噪声测试站全过；浏览器 → 设置 → 返回原应用的跨应用任务可稳定执行
- **像人一样操作**：滑动轨迹带弧度与轻微抖动，点击坐标带微小随机偏移；系统能识别并排除注入手势，截图时自动隐藏悬浮窗，减少误判和视觉干扰

详见 [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md)。

### 📱 竖屏 UI：不是把桌面界面硬缩小

窄屏竖屏下，侧栏展开时会占满整行，对话流让位；横屏和宽屏完全不改。设置面板会改为竖向堆叠，导航变成顶部可横滑条，内容区拿满整宽；查看更改/代码差异的右侧面板则自动变为整屏浮层。

这三项能力都通过引擎官方 `dsh.client` 机制作为客户端插件实现：不改引擎源码，升级引擎不会覆盖，卸载插件即可恢复原状。四套插件测试 **30/30、26/26、36/36、28/28** 全部通过。详见 [docs/NARROW_UI.md](docs/NARROW_UI.md)。

### 🛡️ 设备能力与安全边界

Agent 通过只监听 `127.0.0.1:3083` 的能力桥操作手机，所有调用都经过 token 门控：

- token 每次启动随机生成，只注入引擎环境；同设备第三方 App 没有 token 时返回 `unauthorised`
- 无障碍能力负责读屏、点击、输入、手势、按键与截图，不要求把引擎端口暴露出去
- 普通模式的 `su` 闸门拦截子进程提权，避免沙箱里的 Agent 越过当前权限边界

### 🔔 后台通知与彻底退出

长任务不必盯着屏幕：连续无障碍操作会显示当前步数，任务完成由 Agent 主动推送；退出时会同时停止引擎、局域网代理和本地推理子进程，结束界面并销毁 WebView，释放约 **380 MB** 运行内存。

### 🔐 三级权限

| | 普通 | Shizuku | Root |
|---|---|---|---|
| 引擎身份 | 应用沙箱 | 应用沙箱 | uid 0，全盘 |
| Agent 能力 | 沙箱内命令 | + adb 级命令（`shz`） | 全盘读写 |
| 前置条件 | 无 | 安装并启动 [Shizuku](https://shizuku.rikka.app/) | 设备已 Root |
| 安全机制 | su 闸门拦截提权 | su 闸门拦截提权 | 双重高危确认 + 启动前自动备份 |

默认普通模式；能力未就绪的选项自动置灰；切换后引擎自动重启生效。普通模式把能力限制在应用沙箱，需要更强设备控制时再明确选择 Shizuku 或 Root，权限边界不会悄悄升级。

## 📚 功能详解

每一项能力都有独立的深度文档（设计取舍、实测数据、排障清单）：

| 文档 | 内容 |
|---|---|
| [无障碍控制](docs/ACCESSIBILITY.md) | 读屏/点击/输入/滑动/长按/拖拽等 21 种原语；多层视觉兜底、每步验证；实测 ~1.45s/步 |
| [SSH 远程工作区](docs/SSH.md) | 内置 @tiphareth/dsh-hardssh：远端工作区、Web 终端、隧道与 `ssh_*` 工具；CI 从 GitHub 构建 |
| [闭环设计笔记](docs/LOCAL_CLOSED_LOOP.md) | 端侧决策引擎的设计与演进记录 |
| [竖屏 UI 插件](docs/NARROW_UI.md) | 侧栏全宽 / 设置面板重构 / 右侧面板全屏（三个客户端插件） |
| [局域网 WebUI](docs/LAN_WEBUI.md) | 反向代理、6 位验证码、常驻提醒与关闭链路 |
| [语音输入](docs/VOICE_INPUT.md) | 双通道识别、发送去重、面板停靠与已知问题 |
| [通知与后台行为](docs/NOTIFICATIONS.md) | AI 操作进度、任务完成、局域网提醒、退出彻底化 |
| [复杂场景实测](docs/COMPLEX_SCENARIOS.md) | 噪声页面、跨应用任务、失败恢复的实测记录 |
| [快速路径选择](docs/FAST_PATH_PICKER.md) | 端侧 UI 元素选择与判别 |
| [发布与签名](docs/RELEASE.md) | 正式 key 保管与备份、GitHub Secrets、debug+release 构建、edge/ tag 发布 |

## 🌟 这些是 Termux 路线给不了的

**插件体系开箱可用，不用先当一次编译工程师**
dsh 的「一切皆插件」架构原样保留：插件热载、会话持久化全部照常工作。插件缺的语言环境由扩展中心一键补齐。Termux 路线上，`sharp` / `koffi` / `node-pty` 这类原生模块编译失败是常态。

**跨版本兼容，逐台设备踩过的坑已经踩完**
App 支持 Android 8.0 → 16；界面渲染依赖系统 WebView（**需 Chrome 85+**），构建时向前端注入 polyfill（覆盖 13 个现代 API），把 API 层门槛从原生 Chrome 126 拉回到 85；全部 so 按 **16KB 页对齐**，新内核设备直接可用。

**后台不被杀，任务完成有通知**
specialUse 前台服务 + 指数退避监督器扛住系统回收，长任务锁屏/后台持续运行，完成自动发系统通知。

## 🆚 Android 上跑 Agent 的几条路线

| | Termux 手动配置 | proot + Ubuntu | APK 快照打包 | **DSH Mobile** |
|---|---|---|---|---|
| 安装体验 | 装 Termux、配环境、装依赖 | 装容器与发行版 | 装即用 | **装即用** |
| 首次可用需下载 | 手动配置 | rootfs 500MB～2GB | 无 | **无** |
| 运行时 | 活环境（可扩展） | 容器内 glibc | 死快照，随包冻结 | **自建 bionic 闭包，CI 收集校验** |
| 许可证合规 | — | — | ⚠️ 快照打包 GPL 组件，合规存疑 | **仅含 MIT/BSD/ISC/Zlib 组件** |
| 权限分级 | 无 | 无 | 无 | **三级模式 + su 闸门 + Shizuku adb 桥** |
| 环境扩展 | 手动装 | apt | 死快照，不可扩展 | **19 项一键装 + AI 自助安装** |
| Termux 依赖 | 需要 Termux App | 需要 | 快照即 Termux | **零依赖**（硬编码路径已重定位） |

> 路线之间不是替代关系：Termux 系是在 Android 上**搭一个 Linux 环境**再跑 dsh；本项目把 dsh 需要的运行时**打进 APK**。**两条路线跑的是同一个 dsh**。
>
> 📄 完整路线剖析见 **[docs/android-agent-routes.zh.md](docs/android-agent-routes.zh.md)**（[English](docs/android-agent-routes.md)）。

## 📦 安装

**下载 Release（推荐）**：前往 [Releases](https://github.com/Soodok/Deepseek-Harness-Local-Android/releases) 下载 APK（手机选 `arm64-v8a`，最新版 **v1.4.17**），允许安装未知来源应用后安装。

> 📦 **Release 里有多种包，怎么选**：普通用户下 **`dsh-mobile-*-arm64-v8a.apk`**（正式签名）；
> 文件名带 **`-debug`** 的是调试签名版，供抓日志调试、或之前就装 debug 渠道的用户继续升级用；
> 带 `x86_64` 的仅用于模拟器。**正式版与调试版签名不同、无法互相覆盖安装**（会提示「应用签名不一致」），装错需先卸载。

**从源码构建**（JDK 17 + Android SDK，NDK r26+、CMake 3.22.1）：

```bash
./scripts/collect-termux-runtime.sh app/src/main/assets/runtime.zip aarch64
python scripts/dedupe-runtime.py app/src/main/assets/runtime.zip   # 别名去重，省 ~33MB
gradle assembleDebug -Pabi=arm64-v8a
```

或 Fork 后在 GitHub Actions 运行 **android-build** 工作流云端出包。

**系统要求**：Android 8.0+（**WebView ≥ Chrome 85**）· arm64-v8a / x86_64 · **建议预留 700MB 以上空间**。

## 🚀 快速上手

1. 首启选择显示方向与权限模式（不确定就选「普通」）
2. 等待运行时解压（真实进度）与引擎启动
3. 进入对话派任务；齿轮图标进设置；点击 Agent 给的 `127.0.0.1` 链接预览成果
4. 语音输入、悬浮窗等入口默认可见，无需开关
5. 想让 Agent 操作手机屏幕：在系统设置中开启 **DSH Screen Tap** 无障碍服务（Android 13+ 会弹窗询问「允许完全控制设备」，需点 **Allow**）

## ⛔ 边界（请知悉）

- **WebView 需 Chrome 85+**：引擎前端用了 `??=` 等新语法，属解析期特性，polyfill 补不了。Android 8.0+ 的 WebView 是可独立升级的系统组件，升到 85+ 即可；少数无法升级的老机型为已知限制
- **没有桌面环境**：不能运行 Linux GUI 桌面应用；视觉产物通过本地 HTTP + 内置 WebView 预览
- **模拟点击是"半盲"的**：读屏基于无障碍节点树，对纯图形/游戏画面无效
- **Android 13+ 开启无障碍需系统确认**：侧载安装的应用，系统会弹出「允许完全控制设备」对话框，必须手动点 Allow 才生效；adb 直接写入设置会被系统清掉（真机请走 UI 指引）
- **长任务非绝对不死**：用户强杀/极端省电模式仍会中断（引擎会自动重启，进行中任务需重新派发）
- **提权伴随风险**：Root 模式 AI 具全盘读写能力，误操作可能损坏系统——详见下方免责声明

## ❓ FAQ

**需要 Root 吗？** 不需要。普通模式覆盖绝大多数用法，Root/Shizuku 是高级可选项。

**和 Termux 方案是什么关系？** 并列关系。Termux 路线是在 Android 上搭一个 Linux 环境再跑 dsh；本项目把运行时打进 APK，装完直接用。**两边跑的是同一个 dsh**。

**它是自己实现了一套 Agent 吗？** 不是。Agent 逻辑、插件体系、WebUI 全部来自官方 [`@deepseek-ai/dsh`](https://github.com/deepseek-ai/deepseek-harness)；本项目只做 Android 侧运行环境。这也是它被称作「**移植**」而不是「框架」的原因。

**数据会上传吗？** 引擎/会话/工作区全在本机；数据是否出设备取决于你配置的模型服务地址。

**APK 为什么约 144MB？** 内置了完整的 Node.js bionic 运行时、19 项扩展的工具链闭包以及 dsh 的依赖树。这是「不依赖 Termux、装完即用」的代价；Termux 系方案把这部分留给用户自行安装，所以包体更小，但首次使用需要先配置环境。

**Agent 怎么展示网页？** 让它起本地 HTTP 服务并给你 `http://127.0.0.1:端口` 链接，点开即预览。

**无障碍点不进去？** Android 要求无障碍服务必须在系统设置中手动开启，应用内按钮只负责跳转。Android 13+ 首次开启还会弹出系统确认框（「允许完全控制设备」），必须点 Allow 才会生效。

## ⚠️ 免责声明

> **请在使用前仔细阅读本节。**

1. 本软件按「现状」提供，不附带任何明示或默示的担保。作者不对因使用、滥用或无法使用本软件导致的任何直接或间接损失承担责任。
2. **Root 模式下，引擎以最高权限（uid 0）运行，AI 生成的命令具备对整台设备的完全读写能力。** AI 可能产生错误、意外或破坏性的操作——包括但不限于删除系统文件、破坏分区、导致设备无法启动。**由此造成的任何设备损坏、数据丢失、保修失效，均由用户自行承担全部责任，与作者无关。**
3. Shizuku 模式下 Agent 可执行 adb 级操作，同样存在误操作风险，请知悉并自行斟酌。
4. 请仅在**你本人拥有或获得明确授权**的设备上使用本软件；将其用于未授权设备或非法用途的后果由使用者自行承担。
5. Root/Shizuku 模式均为可选项，不开启则 Agent 被严格限制在应用沙箱内。**如果你不想承担任何风险，请保持普通模式。**

**继续安装或开启高权限模式，即视为你已阅读、理解并接受上述全部条款。**

## 反馈

遇到问题或功能建议，欢迎提交 [Issue](https://github.com/Soodok/Deepseek-Harness-Local-Android/issues)；崩溃类问题请附上 `logcat` 输出或应用内的引擎日志。

**QQ 交流群：`1128769016`** —— 使用问题、版本动态、插件推荐都可以在群里聊；Bug 仍建议同时提 Issue，方便跟踪不遗漏。

## 🙏 贡献者

这个项目不是一个人做出来的。以下朋友的实际贡献已合入主线，特此致谢：

| 贡献者 | 贡献 |
|---|---|
| [@skynit](https://github.com/skynit) | 登录/「开始使用」弹窗渲染错位修复（PR #30）：定位到视口缩放与合成层残留的因果关系，并给出「遮罩下定稿 + 整页重栅格化」方案 |
| @henryz78 | 引擎重启后永久卡住修复（v1.4.9）：定位到 `stopping` 标志在 `start()` 中漏复位，并进一步发现 `CancellationException` 被吞导致旧监督循环覆盖新循环状态的竞态；因网络原因未能提 PR，以源码包形式提交 |

> 如果你也修好了什么问题，欢迎提 Issue / PR，或者像 @henryz78 一样把改动发到群里——署名一定会保留。

## 许可证

[MIT](LICENSE)。运行时组件沿用其原始许可证（MIT / BSD / ISC / Zlib）；`@deepseek-ai/dsh` 归 DeepSeek AI 所有。

本项目为独立社区作品，与 DeepSeek 无隶属关系。
