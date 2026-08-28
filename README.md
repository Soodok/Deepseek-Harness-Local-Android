# DSH Mobile

**在手机上运行完整的 AI Agent —— 无需 Root，无需 Termux，无需电脑。**

[![CI](https://github.com/Soodok/dsh-android/actions/workflows/android-build.yml/badge.svg)](https://github.com/Soodok/dsh-android/actions/workflows/android-build.yml)
![Release](https://img.shields.io/badge/release-v1.0.0-blue)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![License](https://img.shields.io/badge/license-MIT-brightgreen)

[中文](#简介) · [English](README_EN.md)

---

## 简介

DSH Mobile 是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（DeepSeek 开源的 Agent 框架）的 Android 本地引擎壳。完整的 Node.js Agent 引擎运行在应用沙箱内，监听 `127.0.0.1` 回环——会话、凭证、工作区**全部留在手机上**，装完即用，数据不出设备。

## ✨ 在手机上能做什么

**完整的 Agent 任务执行**
派任务给 Agent：读写文件、执行 shell 命令、全文检索、管理项目——bionic bash / ripgrep / pnpm / curl 随包内置，依赖闭包经 ELF 校验，是真实执行力，不是只能聊天的壳。

**即时预览它做的网页**
Agent 起一个本地 HTTP 服务（用内置 node），给你 `http://127.0.0.1:端口` 链接，点开即预览，一键回主页。已实测：小游戏、静态站、API 服务均可。

**按需扩展 Agent 能力**
三级权限随取随用——普通（沙箱，默认）满足日常；Shizuku 模式 Agent 可执行 adb 级命令（进程管理/系统属性，无需 Root）；Root 模式全盘读写（双重确认 + 自动备份）。能力未就绪的选项自动置灰。

**操作手机屏幕**
开启无障碍服务后，Agent 可模拟点击与滑动，替你完成重复性触屏操作。

**数据绝对本地**
引擎只监听 `127.0.0.1`，会话、凭证、工作区全部存在应用私有目录——换手机、卸载应用即全部带走/清除。

**坏了能自愈**
插件配置写坏自动回滚到上次健康快照；引擎崩溃指数退避重启；前台服务保障长任务不被系统强杀。

**Agent 自我扩展**
Agent 不只会用预装的工具，还会自己装需要的：运行时缺 Python？它能自行下载安装；Root 模式下甚至实测成功自装 Android SDK 命令行工具。环境不够用，Agent 自己动手补齐。

## ⛔ 边界（请知悉）

- **没有桌面环境**：不能运行 Linux GUI 桌面应用；视觉产物通过本地 HTTP + 内置 WebView 预览
- **开箱不含 Python / gcc**：预装工具链仅 node/bash 系；但 Agent 可自行下载安装（Root 模式下已实测装成 Python 甚至 Android SDK），普通模式下仅限纯 JS 扩展
- **模拟点击是"盲"的**：无障碍服务只注入手势、不读取屏幕内容（隐私优先的设计），复杂 UI 自动化受限
- **长任务非绝对不死**：前台服务已最大规避系统回收，但用户强杀/极端省电模式仍会中断（引擎会自动重启，进行中任务需重新派发）
- **提权伴随风险**：Root 模式 AI 具全盘读写能力，误操作可能损坏系统——详见下方免责声明

## 🆚 与同类方案对比

| | Termux + 手动配置 | Termux 快照打包 | **DSH Mobile** |
|---|---|---|---|
| 安装体验 | 装 Termux、配环境、装依赖 | 装即用 | **装即用** |
| 运行时 | 活环境（可扩展） | 死快照，随包带死 | **自建 bionic 闭包，CI 收集校验** |
| 许可证合规 | — | ⚠️ 快照打包 GPL 组件，合规存疑 | **仅含 MIT/BSD/ISC/Zlib 组件** |
| 后台可靠性 | 依赖 Termux 会话保活 | 看门狗硬扛 | **specialUse 前台服务 + 指数退避监督器** |
| 权限分级 | 无 | 无 | **三级模式 + su 闸门 + Shizuku adb 桥** |
| 构建工程化 | — | 无 CI，无法从源码复现 | **双架构 CI：运行时收集 → 闭包校验 → 16KB 对齐防呆 → 出包** |

## 权限模式

| | 普通 | Shizuku | Root |
|---|---|---|---|
| 引擎身份 | 应用沙箱 | 应用沙箱 | uid 0 全盘 |
| Agent 能力 | 沙箱内命令 | + adb 级命令（`shz`） | 全盘读写 |
| 前置条件 | 无 | 安装并启动 [Shizuku](https://shizuku.rikka.app/) | 设备已 Root |
| 安全机制 | su 闸门拦截提权 | su 闸门拦截提权 | 双重高危确认 + 启动前自动备份 |

默认普通模式；能力未就绪的选项自动置灰不可选；切换后引擎自动重启生效。

## 📦 安装

**下载 Release（推荐）**：前往 [Releases](https://github.com/Soodok/dsh-android/releases) 下载 APK（手机选 `arm64-v8a`），允许安装未知来源应用后安装。

**从源码构建**（JDK 17 + Android SDK，NDK r26+、CMake 3.22.1）：

```bash
./scripts/collect-termux-runtime.sh app/src/main/assets/runtime.zip aarch64
gradle assembleDebug -Pabi=arm64-v8a
```

或 Fork 后在 GitHub Actions 运行 **android-build** 工作流云端出包。

**系统要求**：Android 8.0+ · arm64-v8a / x86_64 · 约 300MB 可用空间。

## 🚀 快速上手

1. 首启选择显示方向与权限模式（不确定就选「普通」）
2. 等待运行时解压（真实进度）与引擎启动
3. 进入对话派任务；齿轮图标进设置；点击 Agent 给的 `127.0.0.1` 链接预览成果

## ❓ FAQ

**需要 Root 吗？** 不需要。普通模式覆盖绝大多数用法，Root/Shizuku 是高级可选项。

**数据会上传吗？** 引擎/会话/工作区全在本机；数据是否出设备取决于你配置的模型服务地址。

**APK 为什么 ~70MB？** 内置完整 Node.js 运行时与依赖闭包（~69MB），这是「不依赖 Termux」的代价。

**Agent 怎么展示网页？** 让它起本地 HTTP 服务并给你 `http://127.0.0.1:端口` 链接，点开即预览。

**无障碍点不进去？** Android 要求无障碍服务必须在系统设置中手动开启，应用内按钮只负责跳转。

## ⚠️ 免责声明

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
