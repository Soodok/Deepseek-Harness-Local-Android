# 在 Android 上跑 AI Agent：五条路线对比

> **背景**。DeepSeek Harness（`dsh`）以 Node.js CLI + 本地 Web UI（`http://127.0.0.1:3080`）的形式发布，**没有官方 Android 版本**。想把它搬到手机上，第一件要决定的事不是「用哪个 Agent」，而是「**怎么把 Node.js 运行时送到设备上**」——路线之间的差别，基本都来自这个决定。
>
> 本文对比当前实际在用的五条路线，包含各自的代价。文档由 [DSH Mobile](https://github.com/Soodok/Deepseek-Harness-Local-Android) 项目维护，所以第 5 条写得最细；但 1–4 条的真实优势如实列出，第 5 条的短板也直说。

## 路线 1 — Termux，手动配置

**做法**。装 Termux（用 F-Droid 版本，Play 商店版本已过时），然后自建编译链并全局安装 dsh：

```bash
pkg update -y
pkg install -y nodejs clang make python binutils pkg-config cmake ninja
npm install -g @deepseek-ai/dsh
dsh web   # 手机浏览器打开 http://127.0.0.1:3080
```

实际上有几个原生模块在 Android 上装不干净，常见失败与成因：

- Node 头文件报告 `platform="android"`，触发多个 `node-gyp` 配置分支异常
- `koffi` 调用 `statx()`，部分 Android 内核/API 组合上不可用
- SELinux 策略禁止 npm 安装阶段跨目录 `link()`
- `@img/sharp` 缺原生二进制，需回退到 WASM 版本（`@img/sharp-wasm32`）

**优势**。活环境，完全可扩展。`pkg` 能触达整个 Termux 仓库，缺什么都能装或编译，一切可见可控。

**代价**。配置过程长且脆，而且不是一次性的——dsh 升级可能让同样的原生模块问题复现。需要 Termux App，并且需要一个能扛住 Android 后台回收的会话。

## 路线 2 — Termux，一键脚本

**做法**。社区脚本把路线 1 自动化：代打 Android 兼容补丁，部分还提供预编译好的原生模块压缩包，设备端无需编译。

**优势**。手工工作量比路线 1 少一个数量级，同时保留可扩展的活环境。

**代价**。底层仍然依赖 Termux App 与会话保活；你继承的是脚本作者的补丁选择，跟的是**他们的**发布节奏而不是上游的。

## 路线 3 — Termux 内跑 proot + Ubuntu

**做法**。用 `proot-distro` 在 Termux 里装一个 Linux 发行版，然后在容器里装 Node.js 和 dsh——容器内就是普通的 glibc Linux：

```bash
pkg install proot-distro
proot-distro install ubuntu
proot-distro login ubuntu
# 在 Ubuntu 内
apt update && apt install -y curl git nodejs npm
npm install -g @deepseek-ai/dsh
```

**优势**。容器系路线里兼容性最干净的：完全不需要 bionic 补丁，因为容器内就是一个正常的 Linux 用户态。最接近「和电脑上一样直接能跑」。

**代价**。多出两层要保活（Termux → proot → 发行版），性能与延迟代价随之而来；一个完整发行版的存储开销；而且 `proot` 是用户态系统调用翻译层，不是真正的内核命名空间。

## 路线 4 — APK 内置 Termux 根文件系统快照

**做法**。把预构建好的 Termux rootfs 打进 APK，首次启动时解压，并在其中启动引擎。不需要单独装 Termux App。

**优势**。装即用；由 App 自己管理进程，消除了「Termux 会话要一直活着」这个失败模式。

**代价**。环境**冻结在构建时刻**——想加一个包就得重打包快照、发新 APK。重新分发 Termux rootfs 意味着连带分发 GPL 组件，对再打包分发构成许可证合规疑问。另外 rootfs 假定 Termux 的硬编码前缀（`/data/data/com.termux/files/usr`），运行时必须把所有路径重定位。

## 路线 5 — 运行时打进 APK（DSH Mobile）

**做法**。不分发 Linux 用户态，而是只装 dsh 真正需要的东西：自建的 **bionic**（Android libc）Node.js 运行时 + 经校验的依赖闭包，一并打进 APK。引擎直接跑在应用沙箱里，监听 `127.0.0.1`。没有 Termux App、没有容器、没有 proot 层。

**优势**。

- **装完即用**——零外部依赖，首次使用前无需任何配置
- **CI 校验的运行时**——依赖闭包由双架构 CI 流水线收集与校验（ELF 闭包检查、SONAME 完整性、面向新内核的 16KB 页对齐），发布的字节可从源码复现
- **许可证干净**——只重分发 MIT / BSD / ISC / Zlib 组件，不含 GPL 载荷
- **冷启动 < 10 秒**（真机实测），多工具链并行时内存 < 400MB
- **前台服务保活** + 指数退避监督器，不依赖终端会话存活
- **一键环境扩展**——19 项工具链（Python、Go、Rust、Clang、OpenJDK、FFmpeg、ImageMagick、OpenSSH…）装进应用自己的目录，无需重打包 APK 就能扩展
- **权限分级**——默认沙箱、Shizuku 提权到 adb 级、或完整 Root，可在其间迁移

**代价**。

- **APK 约 70MB**——这是随包携带 Node.js 运行时与依赖闭包的代价
- **不是通用 Linux 用户态**——你能拿到扩展中心提供的 19 项工具链，以及 Agent 自己装的；如果需要 `apt install 任意包` 那样的覆盖度，路线 1 或 3 更合适
- **Android 平台的限制没有哪条路线能消除**：没有 Linux GUI 桌面；dsh 自带的 `bash` 沙箱后端（bubblewrap）在 Android 上通常因 SELinux 策略不可用，Agent 可能以 `danger-full-access` 在应用沙箱内运行。这一条对所有路线一视同仁

## 横向对比

| | Termux 手动 | Termux 脚本 | proot + Ubuntu | APK 快照 | **运行时打包（DSH Mobile）** |
|---|---|---|---|---|---|
| 首次使用前配置 | 长 | 脚本代劳 | 中等 | 无 | **无** |
| 运行时形态 | 活环境，可扩展 | 活环境，可扩展 | 活 glibc，可扩展 | 冻结在构建时 | **自建 bionic 闭包，CI 校验** |
| 需要外部 App | Termux | Termux | Termux | 无 | **无** |
| 环境扩展性 | 完整 `pkg` 仓库 | 完整 `pkg` 仓库 | 完整 `apt` 仓库 | 不重打包就无法扩展 | **19 项一键装 + Agent 自助安装** |
| 分发许可证姿态 | 不适用（用户自装） | 不适用（用户自装） | 不适用（用户自装） | ⚠️ 含 GPL 组件 | **仅 MIT / BSD / ISC / Zlib** |
| 抗后台回收 | 看会话 | 看会话 | 看会话 | App 自管理 | **前台服务 + 退避监督器** |
| 权限分级 | 无 | 无 | 无 | 无 | **沙箱 / Shizuku / Root** |
| 从源码可复现 | 手动 | 部分 | 手动 | 无 CI | **双架构 CI 流水线** |
| 首次启动耗时 | 数分钟 | 数分钟 | 数分钟 | 数分钟（解压） | **< 10 秒** |

## 怎么选

- **需要任意 Linux 包或完整用户态** → 路线 1 或 3
- **想要活环境但尽量少动手** → 路线 2
- **想装即用且不装 Termux App** → 路线 4 或 5。看重许可证合规与可复现构建选 5；特别想要 APK 内含 Termux 用户态选 4
- **只想零配置把 Agent 跑起来** → 路线 5

## 路线之间**不变**的东西

五条路线跑的都是**同一个 DeepSeek Harness**。插件体系、WebUI、工具调用链、会话存储格式，全部来自上游 `@deepseek-ai/dsh`。路线之间的差别只在于「**Node.js 运行时怎么送到手机上**」。

这一点在读项目介绍时很重要：**打包运行时的项目是「移植/宿主」，不是新的 Agent 框架**——它继承上游的能力与局限，而不是替换上游。

## 已知平台限制（对所有路线一视同仁）

- **没有桌面环境**。Linux GUI 应用跑不了，视觉产物通过本地 HTTP 服务预览
- **Agent 的 `bash` 沙箱通常不可用**。bubblewrap 所需的内核特性在多数设备上被 Android 的 SELinux 策略挡掉，harness 会退化为在自身沙箱内以完全访问权限执行命令。因此隔离来自**应用沙箱**，而不是 dsh 自带的沙箱后端
- **屏幕自动化是「半盲」的**。基于无障碍节点树的读取（文本 + 坐标）可用；纯图形界面不行
- **后台执行不是不死身**。用户强杀或激进省电会中断引擎；路线之间的差别只在于**恢复得优雅与否**
