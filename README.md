# dsh-android

> DeepSeek Harness (`dsh`) 的 Android 本地引擎壳 —— 手机本身就是一台 DSH 主机。
>
> **版本 v0.1.0-m1.5 · 构建于 2026-08-27** · 状态：**arm64 真机端到端跑通**——引擎 Healthy、bash/rg 可用、会话持久化正常、cordis 插件可安装。

English | 中文

## 这是什么

在普通 Android 手机上（无需 root、无需 Termux、无需电脑）完整运行
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的开源壳应用：

- **本地优先**：引擎跑在手机上，`127.0.0.1` 回环监听，会话数据不出设备
- **免 Termux**：bionic Node 运行时随 APK 内置，装完即用
- **完整工具链**：内置 bionic bash + ripgrep + pnpm/curl 包装，Agent 可真实执行命令与搜索文件
- **活环境路线图**：后续接入 Alpine proot，让 Agent 自己 `apk add` 扩展工具链

## 与同类项目的差异化

| | kelai141/dsh-mobile-apk | **dsh-android (本项目)** |
|---|---|---|
| 运行环境 | Termux 快照（死快照） | 自建 bionic node（许可证干净，可扩展为 Alpine 活环境） |
| 许可证 | 打包 GPL 终端组件（存疑） | MIT，仅再分发 MIT/BSD/ISC/Zlib 组件 |
| 工具链 | 随 Termux 快照带死 | 显式闭包：node/bash/rg + pcre2/readline/ncurses/iconv，ELF NEEDED 全校验 |
| 后台可靠性 | 看门狗硬扛 | `specialUse` 前台服务 + 指数退避监督器 |
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
│ bionic runtime (Termux 构建)                 │
│   bin/: node bash rg pnpm curl               │
│   lib/: openssl icu sqlite readline ncurses  │
│         iconv pcre2 c-ares zlib libc++       │
│   lib/node_modules/: @deepseek-ai/dsh        │
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

要求：JDK 17、Android SDK（NDK r26+、CMake 3.22）、Gradle 8.9、Git Bash（Windows 下跑收集脚本）、Python 3（重打包工具可选）。

```powershell
# 1. 补齐 SDK 组件（或 Android Studio GUI 装）
sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"

# 2. 准备 runtime.zip
.\scripts\collect-termux-runtime.sh app\src\main\assets\runtime.zip aarch64   # Git Bash

# 3. 构建并安装
$env:ANDROID_HOME="E:\Android\Sdk"
gradle assembleDebug -Pabi=arm64-v8a
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

> 注意 ColorOS 类定制系统拒绝 shell uid 发起的安装（`pm install` 返回 -99），
> 请改推到 `/sdcard/Download/` 后由手机安装器手动安装。

## 验证状态（诚实声明 · 2026-08-27 · v0.1.0-m1.5 arm64 真机）

| 项目 | 状态 | 备注 |
|---|---|---|
| **引擎端到端（Android 16 真机）** | ✅ **通过** | `engine healthy on :3080`；node 双进程常驻；W^X AVC granted 全链路放行 |
| 会话持久化 | ✅ 通过 | `session.jsonl.zstd` 正常生成（含子代理会话）；link→rename 原子发布 |
| bash 可用 | ✅ 通过 | SONAME 别名闭环（libreadline.so.8/libncursesw.so.6），pwd/mv 实测 |
| ripgrep glob/grep | ✅ 通过 | Termux 原生 bionic rg，实测 52 处匹配；`rgPath → $PREFIX/bin/rg` |
| cordis 插件安装 | ✅ 四层验证通过 | `@deepseek-ai/cordis-plugin-group` 注册成功；`dsh plugin add @cordisjs/plugin-logger` exit 0 |
| dsh web UI | ✅ 可用 | WebView 正常加载；桌面布局在窄屏有侧栏挤压问题（UI 特调待做） |
| 16KB 内存页对齐 | ✅ 已固化 | CMake `-Wl,-z,max-page-size=16384` + CI 逐 so 对齐防呆步 |
| 运行时体积裁剪（≤35MB 目标） | ⏳ 未做 | 当前 APK ~245MB（SONAME 别名副本 + 完整闭包）；功能优先，裁剪延后 |
| write 工具沙箱化写入 | ⚠️ 部分 | `link()` 已 Android 降级 lstat+rename（保持 createIfAbsent 语义）；sandbox-local 无 Android 后端，受限模式 fail-closed |

<details>
<summary>历史踩坑史（每条都对应一次真机事故）</summary>

- **v0.1.0-m0.x (2026-08-26~27)**：JNI double-free、lateinit 时序、npm OOM(--max-old-space-size=5632)、Termux 包名(deb 内绝对路径)、sandbox-local 死 import 外科手术摘除
- **v0.1.0-m1 (2026-08-27)**：真机事故 #1——自建 libdshpty.so `p_align=4096` 在 16KB 页内核 dlopen 失败 → 监督器死循环。修复：CMake 显式 `-Wl,-z,max-page-size=16384` + CI 对齐防呆步
- **v0.1.0-m1.1 (2026-08-27)**：真机事故 #2——Termux 编译的 node 把 OPENSSLDIR 硬编码为 `/data/data/com.termux/files/usr/etc/tls`；设备共存真实 Termux 时 fopen EACCES → node 启动即退。修复：EngineConfig 注入 `OPENSSL_CONF`/`SSL_CERT_FILE` 指回自带 etc/tls
- **v0.1.0-m1.2~m1.3 (2026-08-27)**：真机事故 #3——session-persistence-jsonl 用 `fs.promises.link()` 原子发布被 SELinux 拒（EACCES）。修复：Android 下 `link→rename`（同目录 rename 具同等原子性）
- **v0.1.0-m1.4~m1.5 (2026-08-27)**：真机事故 #4/#5——bash 缺 libreadline.so.8（SONAME 别名缺失）；ripgrep 是 Ubuntu x64 二进制非 Android arm64。修复：显式打包 readline/ncurses/libiconv/pcre2 闭包 + SONAME cp 别名副本 + Termux 原生 rg + `@vscode/ripgrep` Android resolver
- **v0.1.0-m1.5-local3 (2026-08-27)**：write 工位同类事故——dsh-fs-local 的 `createIfAbsent` 分支同样走 link()；降级为 lstat+rename 保持"不覆盖创建"语义
- **v0.1.0-m1.5-local5 (2026-08-27)**：corepack shebang 指向 Termux、系统 curl 链接旧 OpenSSL 缺 EVP_MD_CTX_create。修复：bin/pnpm (sh wrapper exec node corepack)、bin/curl (node fetch)

</details>

## 路线图

- [x] M0 骨架：Gradle 工程 + PTY JNI + 监督器 + WebView 壳
- [x] M0.5 spike：模拟器实证 execve bionic node（W^X 豁免成立）
- [x] M1：@deepseek-ai/dsh 完整集成 + bionic 兼容补丁
- [x] M1.5：arm64 真机端到端（bash/rg/session/plugin 四项实证）
- [ ] M1b：运行时体积裁剪（~245MB → ≤35MB）
- [ ] M1c：UI 特调（窄屏移动适配，仅 WebView 注入不改上游）
- [ ] M2：node-pty 桥接自研 libdshpty.so；断点续跑（dsh session resume）
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
5. **Android 禁止硬链接**：SELinux 对 app_data_file 默认禁 `link()`（普通 rename/open 不限）。
   所有"临时文件+原子发布"类逻辑必须走 `rename()` 而非 `link()`；共享库别名必须 `cp` 复制而非 symlink。
6. **显式依赖闭包**：apt-get download 不递归依赖，PKGS 清单必须人工对照 Termux Packages
   索引列全（bash→readline→ncurses、bash→libiconv、ripgrep→pcre2），并在 CI 校验 SONAME 别名实体。
7. **自建工具包装而非修补系统**：系统 curl 链接旧 OpenSSL 缺符号、corepack shebang 指向 Termux；
   解法是打 `#!/system/bin/sh` 包装脚本 exec node/fetch，而不是替换系统组件。

## 许可证

MIT。`@deepseek-ai/dsh` 为 DeepSeek AI 所有（MIT）。
Node.js / bash / ripgrep 运行时来自 Termux 社区构建（各组件 MIT/BSD/ISC/Zlib）。

本项目为独立社区作品，与 DeepSeek 无隶属关系。
