# DSH Mobile

**DeepSeek Harness (dsh) 的 Android 本地引擎壳 —— 让手机本身成为一台 AI Agent 主机。**

[![CI](https://github.com/Soodok/dsh-android/actions/workflows/android-build.yml/badge.svg)](https://github.com/Soodok/dsh-android/actions/workflows/android-build.yml)
![Release](https://img.shields.io/badge/version-v0.1.0--m1.34.0-blue)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![Arch](https://img.shields.io/badge/arch-arm64__v8a%20%7C%20x86__64-9cf)
![License](https://img.shields.io/badge/license-MIT-brightgreen)

[中文](#-) · [English](README_EN.md)

---

## 概览

DSH Mobile 在**普通 Android 手机**上完整运行 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（DeepSeek 2026-08 开源的 Agent 框架）——**无需 Root、无需 Termux、无需电脑**：

- 引擎以原生 bionic Node.js 进程运行于应用私有沙箱，经 `127.0.0.1` 回环对外服务；
- 会话、凭证、工作区**全部留存本机**，数据不出设备；
- 内置完整 POSIX 工具链（bash / ripgrep / pnpm / curl），Agent 具备真实命令执行与文件检索能力；
- 三级运行权限模型（普通 / Shizuku / Root）+ 无障碍模拟点击，按需递进放开能力边界。

> 当前状态（v0.1.0-m1.34.0）：**arm64 真机端到端验证通过**——引擎 Healthy、bash/rg 可用、会话持久化正常、cordis 插件树全激活、权限三模式可用、UI 完成度生产级。

## 核心特性

| 特性 | 说明 |
|---|---|
| **本地引擎** | 完整 dsh 引擎 + Node.js 运行时内置 APK，回环监听 `127.0.0.1:3080`，零云依赖 |
| **免 Termux** | 自建 bionic 闭包运行时（许可证干净，仅含 MIT/BSD/ISC/Zlib 组件），装完即用 |
| **三级权限模型** | 普通（沙箱）/ Shizuku（adb 级）/ Root（全盘），首启引导选择，随时在权限中心切换 |
| **权限中心** | 独立设置页统一管理运行模式、能力探测状态（su / Shizuku）、无障碍开关，能力未就绪项自动置灰不可选 |
| **su 闸门** | 非 Root 模式下引擎 PATH 注入拒绝型 su 遮罩——即便设备已对应用授权 su，Agent 也无法私自提权 |
| **shz 提权通道** | Shizuku 模式下 Agent 可用 `shz <cmd>` 经本地桥以 adb 身份执行命令（保活/杀进程等） |
| **无障碍模拟点击** | 基于 `AccessibilityService.dispatchGesture` 的手势注入，Agent 可模拟点击/滑动操作屏幕 |
| **插件配置自愈** | ProfileGuardian 三层防护：last-good 快照 → 同签名崩溃自动回滚 → 安全模式兜底，坏配置永不锁死用户 |
| **运行时完整性校验** | 覆盖升级后校验引擎目录完整性（入口/依赖库/包文件数），缺失即强制重装，杜绝空壳半装态 |
| **进程监督器** | `specialUse` 前台服务 + 指数退避自动重启 + 用户级热重启；解压阶段展示真实百分比进度 |
| **预览直达** | 点击 Agent 输出的 `http://127.0.0.1:<port>` 链接即进入预览模式，一键返回主界面 |
| **显示适配** | 竖屏 viewport 缩放（50%–150% 滑条无级调节）、横屏桌面模式（1280px 视口）、工具栏可隐藏 + 把手自由拖动 |

## 权限模型

| | 普通 | Shizuku | Root |
|---|---|---|---|
| 引擎进程身份 | 应用沙箱 uid | 应用沙箱 uid | `su`（uid 0）整体提权 |
| Agent 命令边界 | 沙箱内 | 沙箱内 + `shz`（adb 级） | 全盘 |
| 无障碍点击 | 可用 | 可用 | 可用 |
| 前置条件 | 无 | [Shizuku](https://shizuku.rikka.app/) 服务运行中并已授权 | 设备已 root（检测到 `su`） |
| 安全机制 | su 闸门遮罩生效 | su 闸门遮罩生效 | 启动前 tar 备份用户资产 + 双重高危确认 |
| 切换生效 | 即时（自动重启引擎） | 即时（自动重启引擎） | 即时（自动重启引擎） |

> 能力探测在引导页与权限中心实时展示：Root 检测为轻量 stat（不触发 Magisk 弹窗），Shizuku 呈现三态（已授权 / 等待授权 / 未运行）；能力未就绪的选项**置灰且不可点击**。

## 系统架构

```
┌──────────────────────────────────────────────────────┐
│ UI 层                                                 │
│   MainActivity (WebView 壳 · 官方 WebUI)               │
│   SettingsActivity (权限中心 · MIUI 分组卡片)           │
│   OnboardingActivity (首启引导)  AboutActivity         │
├──────────────────────────────────────────────────────┤
│ 服务层                                                │
│   EngineService (specialUse 前台服务)                  │
│     └── EngineSupervisor 状态机                        │
│         Idle → Installing → Starting → Healthy        │
│            ↖ Backoff(指数退避) ← Crash/SafeMode        │
├──────────────────────────────────────────────────────┤
│ 能力层                                                │
│   Privilege (权限模式/能力探测/Shizuku binder)          │
│   ShizukuHttpBridge (127.0.0.1:3082 shz 命令桥)        │
│   DshAccessibilityService (手势注入)                   │
│   ProfileGuardian (配置快照/回滚/安全模式)              │
├──────────────────────────────────────────────────────┤
│ 进程层                                                │
│   EngineProcess                                       │
│     ├── dsh_pty.c (JNI fork + PTY + execve)           │
│     ├── 日志泵 → logcat + engine.log                  │
│     └── 退出监听 → 退避重启                            │
├──────────────────────────────────────────────────────┤
│ 运行时层                                              │
│   RuntimeInstaller (assets/runtime.zip                │
│     → 完整性校验 → 解压 → chmod → 版本戳)              │
│   bionic runtime（Termux 构建，闭包 ~69MB）            │
│     bin/: node bash rg pnpm curl                      │
│     lib/: openssl icu sqlite readline ncurses         │
│           iconv pcre2 c-ares zlib libc++              │
│     lib/node_modules/: @deepseek-ai/dsh 及全部依赖    │
└──────────────────────────────────────────────────────┘
```

## 快速开始

### 方式一：下载 Release（推荐）

前往 [Releases](https://github.com/Soodok/dsh-android/releases) 下载对应架构的 APK（真机选 `arm64-v8a`），允许未知来源后安装。首次启动将进入引导页选择显示方向与运行权限模式。

### 方式二：GitHub Actions 云构建

1. Fork 本仓库；
2. 在 Actions 页运行 **android-build** 工作流（双架构矩阵，含运行时收集 + 闭包校验 + 16KB 对齐防呆）；
3. 从 Artifacts 下载 APK。

### 方式三：本地构建

要求：JDK 17、Android SDK（NDK r26+、CMake 3.22.1）、Gradle 9.x、Git Bash（Windows 下运行收集脚本）。

```bash
# 1. 补齐 SDK 组件
sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"

# 2. 生成运行时包（从 Termux 仓库收集 bionic 闭包）
./scripts/collect-termux-runtime.sh app/src/main/assets/runtime.zip aarch64

# 3. 构建
gradle assembleDebug -Pabi=arm64-v8a

# 4. 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **无 Root 设备安装提示**：部分定制系统（如 ColorOS）拒绝 adb 发起的安装，请将 APK 推至 `/data/local/tmp/` 后经设备文件管理器手动安装，或使用 `su -c pm install -r`。

### 首次启动

1. **引导页**：选择默认显示方向（竖屏 / 横屏桌面布局）与运行权限模式（普通 / Shizuku / Root）；
2. **引擎初始化**：首次启动解压运行时（显示真实进度），随后引擎进入 Healthy；
3. **权限中心**：右上角齿轮进入设置页，可随时切换权限模式（自动重启引擎生效）、查看能力状态、开启无障碍模拟点击。

## 工程决策记录

以下决策每一条都对应真实真机事故的修复经验，是本项目区别于"能跑就行"的核心资产：

1. **targetSdk 28（刻意决策）**——Android 10+ 的 W^X 政策按 targetSdk 分域执法，sideload 应用定在 28 即可合法 `execve` 私有目录二进制（Termux 同款策略）。若未来被强制移除，迁移路径为 jniLibs `lib*.so` 伪装，仅动打包层。
2. **16KB 内存页对齐**——2025+ 新旗舰内核页大小为 16384 字节，任何 `p_align=4096` 的 ELF 在其上 dlopen 必失败。本项目 CMake 显式 `-Wl,-z,max-page-size=16384`，且 CI 对 APK 内逐 so 断言对齐（防回归）。
3. **SONAME 精确别名**——Android linker 按 NEEDED 逐字查找（如 `libreadline.so.8`），deb 包内的版本化文件名不匹配即链接失败；且 Android SELinux 禁止 `link()`/symlink，别名必须 `cp` 实体副本。CI 逐库断言。
4. **specialUse 前台服务**——规避 Android 14 对 `dataSync` 类型 6 小时强杀上限，Agent 长任务不被系统限时。
5. **Android 无硬链接**——SELinux 对 `app_data_file` 默认拒绝 `link()`；所有"临时文件 + 原子发布"逻辑降级为 `rename()`（同目录 rename 具同等原子性）。
6. **显式依赖闭包**——`apt-get download` 不递归依赖，运行时清单人工对照 Termux Packages 索引列全（bash→readline→ncurses、bash→libiconv、ripgrep→pcre2），CI 校验实体。
7. **su 闸门（分级提权）**——su 授权按调用方 uid 记录，设备投权后任意 shell 均可提权；本项目在引擎 PATH 首位注入拒绝型 su 遮罩，**仅 Root 模式放行**，实现"授权了 su ≠ Agent 永远能用 su"。
8. **Shizuku 官方三件套**——`api + provider + aidl` 缺一不可：Provider 负责 binder 握手（缺失则授权弹窗永不出现），aidl 的 `IShizukuService.newProcess` 提供进程执行，api 提供授权封装。
9. **不重写上游 WebUI**——dsh web 界面处于上游快速迭代期，原生壳仅做容器 + 原生设置页，零协议维护成本。
10. **WebView 缩放走 viewport meta**——`setInitialScale` 会被页面 meta clamp、数学撑宽布局会引入横滑；唯一可靠方案是改写 meta 为 `width=device-width, initial-scale=R, min=max=R, user-scalable=no`。
11. **运行时完整性双重校验**——"版本匹配"不等于"解压完整"：覆盖升级中途失败会留下空壳目录。安装器校验入口文件 + 关键 SONAME + 包文件数，任一缺失即强制重装。
12. **Root 污染自愈**——Root 模式引擎写入的用户资产会变为 root 属主，切回低权限模式必 EACCES；监督器在非 Root 启动前自动检测并 `chown` 回应用 uid。

## 已验证能力

验证环境：Android 16 真机（arm64-v8a）· v0.1.0-m1.34.0

| 项目 | 状态 | 备注 |
|---|---|---|
| 引擎端到端 | 通过 | `engine healthy on :3080`，node 双进程常驻，W^X 放行全链路 |
| 会话持久化 | 通过 | `session.jsonl.zstd` 正常生成（含子代理会话），rename 原子发布 |
| bash / ripgrep | 通过 | SONAME 闭包闭环；rg 实测 glob/grep；pnpm/corepack 11.x/0.35 EXIT=0 |
| cordis 插件 | 通过 | 插件树全激活；`/api/loader` 可观测 API 供诊断 |
| 三级权限模型 | 通过 | Root su 提权启动 + 资产备份；Shizuku 授权弹窗 + `shz` adb 级执行；su 闸门实测拦截 |
| 无障碍模拟点击 | 通过 | 手势注入服务可开启（系统设置内手动启用，Android 安全模型要求） |
| ProfileGuardian | 通过 | 坏配置自动回滚 / 安全模式隔离归档，引擎不死循环 |
| 16KB 页对齐 | 通过 | 真机 Android 16（16KB 页内核）实测 + CI 防呆 |
| UI 完成度 | 通过 | 权限中心 / 页面缩放 / 横屏桌面 / 预览直达 / 工具栏拖动把手 / 关于页 |
| APK 体积 | 67.3 MB | M1b 依赖闭包裁剪后（自最初 ~245MB 降 72%） |
| write 工具沙箱化 | 部分 | `link()` 已降级 rename；`sandbox-local` 暂无 Android 后端，受限模式 fail-closed |

## 项目结构

```
dsh-android/
├── app/src/main/
│   ├── cpp/                    # dsh_pty.c —— JNI fork+PTY+execve
│   ├── java/app/dsh/mobile/
│   │   ├── engine/             # Supervisor / Process / Config / Privilege
│   │   │                       # RuntimeInstaller / ProfileGuardian / ShizukuHttpBridge
│   │   ├── service/            # EngineService（specialUse 前台服务）
│   │   ├── MainActivity.kt     # WebView 壳（视口缩放 / 预览 chrome / 拖动把手）
│   │   ├── SettingsActivity.kt # 权限中心（MIUI 分组卡片）
│   │   ├── OnboardingActivity.kt / AboutActivity.kt
│   │   └── DshAccessibilityService.kt
│   ├── assets/runtime/         # runtime.zip 落位处（构建时注入）
│   └── res/                    # 布局 / 图标 / 无障碍服务声明
├── scripts/
│   └── collect-termux-runtime.sh   # Termux 闭包收集 + Android 补丁（CI 与本地共用）
└── .github/workflows/
    └── android-build.yml       # 双架构矩阵：收集 → 闭包校验 → 16KB 防呆 → 构建 → Release
```

## 路线图

- [x] M0 —— 工程骨架：Gradle + PTY JNI + 监督器 + WebView 壳
- [x] M0.5 —— W^X 豁免实证（模拟器 execve bionic node）
- [x] M1 —— dsh 引擎完整集成 + bionic 兼容补丁体系
- [x] M1.5 —— arm64 真机端到端（引擎/工具链/会话/插件四项实证）
- [x] M1b —— 运行时依赖闭包裁剪（APK 118MB → 67.3MB）
- [x] M1c —— 原生 UI 体系：首启引导 / 权限中心 / 页面缩放 / 预览直达 / MIUI 风格
- [x] M1d —— 权限体系：三级模式 / su 闸门 / Shizuku shz 桥 / 无障碍点击
- [ ] M2 —— node-pty 桥接自研 libdshpty.so；会话断点续跑（dsh session resume）
- [ ] M3 —— Alpine proot 活环境（Agent 自行 `apk add` 扩展工具链）
- [ ] M4 —— 独立端口管理服务（Agent 自建 web 服务的统一预览）/ Share Target / Quick Settings Tile

## 隐私与安全

- **数据不出设备**：引擎仅监听 `127.0.0.1` 回环；会话/凭证/工作区全部存于应用私有目录；
- **最小权限默认**：默认普通模式，提升能力需用户显式选择（Root 须双重确认 + 自动备份）；
- **提权可控**：su 闸门确保"设备授权了 su"不等于"Agent 永远能用 su"；
- **无障碍只进不出**：手势注入服务仅声明动作能力，不读取屏幕内容（`canRetrieveWindowContent=false`），且必须由用户在系统设置中手动开启；
- **请知悉**：Root 模式下引擎以 uid 0 运行，具备全盘读写能力——请仅在你自己的设备上启用，并理解其风险。

## 致谢

- [DeepSeek Harness (dsh)](https://github.com/deepseek-ai/deepseek-harness) —— 本项目封装的 Agent 引擎（MIT）
- [Termux](https://github.com/termux/termux-packages) —— bionic 构建工具链与运行时组件来源
- [Shizuku](https://github.com/RikkaApps/Shizuku) —— adb 级 API 框架
- 所有在真机事故中留下宝贵报错信息的用户

## 许可证

[MIT](LICENSE)。运行时组件沿用其原始许可证（MIT / BSD / ISC / Zlib）；`@deepseek-ai/dsh` 归 DeepSeek AI 所有。

本项目为独立社区作品，与 DeepSeek 无隶属关系。
