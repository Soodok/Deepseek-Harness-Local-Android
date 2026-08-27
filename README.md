# dsh-android

> DeepSeek Harness (`dsh`) 的 Android 本地引擎壳 —— 手机本身就是一台 DSH 主机。
>
> **版本 v0.1.0-m0.2 · 构建于 2026-08-27** · 状态：**CI 编译验证全绿**，debug APK 已产出，待真机 spike（见下文"验证状态"）

English | 中文

## 这是什么

在普通 Android 手机上（无需 root、无需 Termux、无需电脑）完整运行
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的开源壳应用：

- **本地优先**：引擎跑在手机上，`127.0.0.1` 回环监听，会话数据不出设备
- **免 Termux**：bionic Node 运行时随 APK 内置，装完即用
- **活环境路线图**：后续接入 Alpine proot，让 Agent 自己 `apk add` 扩展工具链

## 与同类项目的差异化

| | kelai141/dsh-mobile-apk | **dsh-android (本项目)** |
|---|---|---|
| 运行环境 | Termux 快照（死快照） | 自建 bionic node（许可证干净，可扩展为 Alpine 活环境） |
| 许可证 | 打包 GPL 终端组件（存疑） | MIT，仅再分发 MIT/BSD 组件 |
| 后台可靠性 | 看门狗硬扛 | `specialUse` 前台服务 + 指数退避监督器 + 断点续跑（路线图） |
| targetSdk | 未公开 | 28（刻意决策：豁免 W^X，sideload 合法执行 filesDir 二进制） |

## 架构

```
┌─────────────────────────────────────────────┐
│ MainActivity (WebView 壳)                    │
│   └── 加载 http://127.0.0.1:3080 官方 WebUI │
├─────────────────────────────────────────────┤
│ EngineService (specialUse 前台服务)           │
│   └── EngineSupervisor 状态机                │
│         Idle→Installing→Starting→Healthy    │
│              ↖___Backoff(退避重启)___↙       │
├─────────────────────────────────────────────┤
│ EngineProcess                                │
│   ├── dsh_pty.c  fork+PTY+execve(JNI)        │
│   ├── 日志泵 → logcat + engine.log           │
│   └── 退出监听 → 触发退避重启                 │
├─────────────────────────────────────────────┤
│ RuntimeInstaller                             │
│   assets/runtime.zip 或 URL(SHA256 校验)     │
│   → 解压 engine/ + chmod + 版本戳            │
├─────────────────────────────────────────────┤
│ bionic node (Termux 构建) + @deepseek-ai/dsh │
└─────────────────────────────────────────────┘
```

## 快速开始

### 方式一：GitHub Actions 云构建（推荐，本机零环境）

1. Fork 本仓库
2. Actions 页运行 **android-build** 工作流
3. Artifacts 下载 APK 安装（允许未知来源）

> 首次运行若 collect-runtime 失败，多为 Termux 包名调整所致，
> 按报错修正 [scripts/collect-termux-runtime.sh](scripts/collect-termux-runtime.sh) 的 PKGS 清单即可。

### 方式二：本地构建

要求：JDK 17、Android SDK（NDK r26+、CMake 3.22）、Gradle 8.9

> 2026-08-26 本地探测：JDK 17.0.12 ✅、Android SDK(E:\Android\Sdk platforms 33/36/36.1 + build-tools 36.x) ✅、adb 36.0.1 ✅
> **仍需安装**：NDK (Side by side) r26+、CMake 3.22.1、Android SDK Command-line Tools、Gradle 8.9（或 `gradle wrapper --gradle-version 8.9`）

```bash
# 1. 先用 SDK Manager 补全 NDK/CMake/cmdline-tools（或 Android Studio GUI 装）
# 2. 生成 Gradle wrapper（仓库不含 gradle-wrapper.jar）
cd dsh-android
gradle wrapper --gradle-version 8.9

# 3. 准备 runtime.zip（或从 CI Artifacts/Release 下载后放入 assets）
./scripts/collect-termux-runtime.sh app/src/main/assets/runtime.zip

# 4. 构建并安装
.\gradlew.bat :app:assembleDebug        # Windows
# ./gradlew :app:assembleDebug           # Linux/macOS
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 验证状态（诚实声明）

| 项目 | 状态 | 备注 |
|---|---|---|
| **CI 全链路编译**（GitHub Actions ubuntu-24.04） | ✅ **通过 · 2026-08-27** | Kotlin 编译 + NDK/CMake 交叉编译 libdshpty.so + APK 打包全绿 |
| Termux runtime.zip 收集脚本 | ✅ CI 端到端跑通 | nodejs-lts 24.18 + 依赖闭包 69MB；已修正 icu→libicu、补 libsqlite、适配 deb 绝对路径布局 |
| PTY JNI (C11) | ✅ NDK 交叉编译通过 | dsh_pty.c → libdshpty.so (arm64-v8a)；运行时行为待真机 |
| 监督器状态机 / 安装器 / 服务 | ✅ 编译通过 | 运行时行为（退避重启/zip-slip 防护实际触发）待真机 |
| debug APK artifact | ✅ 已产出 (70.4MB) | Actions 页 `dsh-android-debug-apk`，含内置 runtime.zip |
| dsh npm 包集成进运行时 | ❌ M1 未开始 | 当前 zip 仅含 node 本体；真机首启会在启动后因缺入口退出进 Backoff（预期行为） |
| 真机端到端 spike | ❌ M0.5 待做 | execve bionic node + RuntimeInstaller installed = spike 判定标准 |

<details>
<summary>历史验证记录</summary>

- 2026-08-26 本地静态验证：JNI 签名/Kotlin 引用链人工审查；修复 JNI double-free、uiScope 泄漏
- 2026-08-27 CI 调参过程修复：Termux 包名（libicu/libsqlite）、deb 内部绝对路径平铺、kotlinx.coroutines.cancel import、findViewById 显式泛型

</details>

## 路线图

- [x] M0 骨架：Gradle 工程 + PTY JNI + 监督器 + WebView 壳
- [ ] M0.5 spike：真机验证 execve bionic node + dsh web 响应 200
- [ ] M1：CI 全链路出包；npm 依赖树裁剪（目标 ≤35MB）
- [ ] M2：断点续跑（利用 dsh session resume）；热管理降频
- [ ] M3：Alpine proot 活环境（Agent 可自装依赖）
- [ ] M4：Share Target / Quick Settings Tile / 多模型网关

## 关键技术决策记录

1. **targetSdk 28**：Android 10+ 的 W^X 政策按 targetSdk 分域执法；
   sideload 应用定在 28 即可合法 execve 私有目录二进制。未来若被强制移除，
   迁移路径为 jniLibs `lib*.so` 伪装（nativeLibraryDir 路线），只动打包层。
2. **specialUse 前台服务**：规避 Android 14 对 dataSync 类型的 6 小时强杀。
3. **不重写 UI**：上游 developer preview 协议不稳，WebView 加载官方 WebUI 保持零维护成本。
4. **16KB 内存页**：2025+ 新旗舰内核页大小变更，引入预编译 `.node` 原生模块时必须
   校验 `-Wl,-z,max-page-size=16384` 链接对齐。

## 许可证

MIT。`@deepseek-ai/dsh` 为 DeepSeek AI 所有（MIT）。
Node.js 运行时来自 Termux 社区构建（各组件 MIT/BSD/ISC/Zlib）。

本项目为独立社区作品，与 DeepSeek 无隶属关系。
