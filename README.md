# DSH Mobile

**在手机上运行完整的 AI Agent —— 无需 Root，无需 Termux，无需电脑。**

[![CI](https://github.com/Soodok/dsh-android/actions/workflows/android-build.yml/badge.svg)](https://github.com/Soodok/dsh-android/actions/workflows/android-build.yml)
![Release](https://img.shields.io/badge/release-v1.0.0-blue)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![License](https://img.shields.io/badge/license-MIT-brightgreen)

[中文](#简介) · [English](README_EN.md)

---

## 简介

DSH Mobile 是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（DeepSeek 开源的 Agent 框架）的 Android 本地引擎壳。它在普通 Android 手机的应用沙箱内运行完整的 Node.js Agent 引擎：

- **数据不出设备** —— 引擎仅监听 `127.0.0.1` 回环，会话、凭证、工作区全部保存在手机本地
- **装完即用** —— Node.js 运行时与完整命令行工具链（bash / ripgrep / pnpm / curl）随 APK 内置
- **能力可递进** —— 三级运行权限（普通 / Shizuku / Root），按需放开 Agent 的能力边界
- **自愈可靠** —— 插件配置损坏自动回滚，引擎崩溃指数退避重启，前台服务长驻不被系统强杀

## 特性

- **本地引擎**：完整 dsh 引擎 + Node.js 运行时内置，离线可用，零云依赖
- **三级权限模型**：普通（沙箱）→ Shizuku（adb 级）→ Root（全盘），应用内一键切换
- **权限中心**：统一管理运行模式、Root/Shizuku 能力探测、无障碍模拟点击开关
- **无障碍模拟点击**：Agent 可模拟点击与滑动，操作手机屏幕（需在系统设置中手动开启）
- **预览直达**：点击 Agent 输出的 `http://127.0.0.1:<端口>` 链接即可实时预览它做的网页
- **页面缩放**：50%–150% 无级调节，横屏模式自动切换桌面布局

## 权限模式

| | 普通 | Shizuku | Root |
|---|---|---|---|
| 引擎身份 | 应用沙箱 | 应用沙箱 | uid 0 全盘 |
| Agent 能力 | 沙箱内命令 | + adb 级命令（`shz`） | 全盘读写 |
| 前置条件 | 无 | 安装并启动 [Shizuku](https://shizuku.rikka.app/) | 设备已 Root |
| 安全机制 | su 闸门拦截提权 | su 闸门拦截提权 | 双重高危确认 + 启动前自动备份 |

默认普通模式。能力未就绪的选项在权限中心自动置灰不可选；切换模式后引擎自动重启生效。

## 系统要求

- Android 8.0（API 26）或更高
- arm64-v8a（绝大多数手机）或 x86_64（模拟器）
- 约 300MB 可用存储空间（运行时解压后）

## 安装

### 下载 Release（推荐）

前往 [Releases](https://github.com/Soodok/dsh-android/releases) 下载 APK（手机选 `arm64-v8a`），允许安装未知来源应用后安装。

### 从源码构建

要求：JDK 17、Android SDK（NDK r26+、CMake 3.22.1）。

```bash
# 收集运行时依赖（Termux bionic 闭包）
./scripts/collect-termux-runtime.sh app/src/main/assets/runtime.zip aarch64

# 构建 APK
gradle assembleDebug -Pabi=arm64-v8a
```

也可直接 Fork 后在 GitHub Actions 运行 **android-build** 工作流，云端出包。

### 首次启动

1. 选择显示方向与运行权限模式（不确定就选「普通」）
2. 等待运行时解压（显示真实进度）与引擎启动
3. 进入对话即可给 Agent 派任务；右上角齿轮进入设置

## 常见问题

**Q: 需要 Root 吗？**
不需要。普通模式覆盖绝大多数用法；Root/Shizuku 是给高级用户放开更多系统能力的可选项。

**Q: 我的对话数据会上传吗？**
引擎、会话、工作区全部在本机。数据是否上传取决于你在应用内配置的模型服务地址，与本应用无关。

**Q: 为什么 APK 这么大（约 70MB）？**
内置了完整的 Node.js 运行时与依赖闭包（约 69MB），这是「不依赖 Termux、装完即用」的代价。

**Q: Agent 怎么给我展示它做的网页？**
让它起一个本地 HTTP 服务并给你 `http://127.0.0.1:端口` 链接，点开即预览，左上角「← 主页」返回。

**Q: 无障碍点击为什么开不了？**
Android 安全模型要求无障碍服务必须由用户在「系统设置 → 无障碍」中手动开启，应用内按钮只会跳转过去。

## 免责声明

> **请在使用前仔细阅读本节。**

1. 本软件按「现状」提供，不附带任何明示或默示的担保。作者不对因使用、滥用或无法使用本软件导致的任何直接或间接损失承担责任。
2. **Root 模式下，引擎以最高权限（uid 0）运行，AI 生成的命令具备对整台设备的完全读写能力。** AI 可能产生错误、意外或破坏性的操作——包括但不限于删除系统文件、破坏分区、导致设备无法启动。**由此造成的任何设备损坏、数据丢失、保修失效，均由用户自行承担全部责任，与作者无关。**
3. Shizuku 模式下 Agent 可执行 adb 级操作，同样存在误操作风险，请知悉并自行斟酌。
4. 请仅在**你本人拥有或获得明确授权**的设备上使用本软件；将其用于未授权设备或非法用途的后果由使用者自行承担。
5. Root/Shizuku 模式均为可选项，不开启则 Agent 被严格限制在应用沙箱内。**如果你不想承担任何风险，请保持普通模式。**

**继续安装或开启高权限模式，即视为你已阅读、理解并接受上述全部条款。**

## 反馈

遇到问题或功能建议，欢迎提交 [Issue](https://github.com/Soodok/dsh-android/issues)；崩溃类问题请附上 `logcat` 输出或应用内的引擎日志。

## 许可证

[MIT](LICENSE)。运行时组件沿用其原始许可证（MIT / BSD / ISC / Zlib）；`@deepseek-ai/dsh` 归 DeepSeek AI 所有。

本项目为独立社区作品，与 DeepSeek 无隶属关系。
