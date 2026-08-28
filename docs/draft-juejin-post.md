# 发帖存档（掘金 · 用户分享口吻）

> 口吻原则：以「我自己做了个东西」的一手体验写，不官方不硬广；技术细节是真事，情绪是真实的。

---

## 标题

不想开 Termux，我把 DeepSeek Harness 塞进了一个 67MB 的 APK

## 正文

最近 DeepSeek Harness（dsh）很火，但官方只有 CLI + Web UI，想在手机上用基本只有两条路：开 Termux 敲一堆命令，或者装个 150MB+ 的 Termux 快照打包 APK。

我嫌都麻烦，干脆自己写了个壳：**DSH Mobile**，开源，MIT。

GitHub：https://github.com/Soodok/Deepseek-Harness-Local-Android
下载：https://github.com/Soodok/Deepseek-Harness-Local-Android/releases（v1.1.0，arm64 66.9MB）

### 它是什么

一个普通的 Android App（不需要 Root、不需要 Termux、不需要电脑），但里面内置了一套完整的 bionic Node.js 运行时 + bash/ripgrep/pnpm/curl 工具链。装完打开就是一个能真实干活的 DeepSeek Agent：

- 引擎跑在应用沙箱里，监听 127.0.0.1:3080，会话数据全在本机
- Agent 能真实执行 shell 命令、读写文件、全文检索
- 让它做个网页，它会起个本地 HTTP 服务，点它给的链接直接预览
- **长任务做完会自动发系统通知**（锁屏也不错过）
- 开启无障碍服务后，Agent 还能**读屏 + 按文本点击**，替你操作其他 App

### 几个我觉得有点意思的技术点

1. **runtime 是自建的 bionic 闭包**：从 Termux 仓库按 ELF NEEDED 依赖闭包收集 node/bash/rg 及全部 so，CI 逐库校验 + 16KB 内存页对齐防呆（2025+ 旗舰机是 16KB 页内核，普通编译的 so 会直接 dlopen 失败）。所以 APK 只有 67MB 还能完全离线用。
2. **su 闸门**：su 的授权是按调用方 uid 记的，设备一旦授权，任何 shell 都能提权。我在引擎 PATH 首位放了个拒绝执行的 su 遮罩，只有切到 Root 模式才移除——「设备授权了」不等于「AI 永远能用 su」。
3. **配置自愈**：AI 写坏插件配置导致引擎崩溃是高频事故，做了三层防护：上次健康快照自动回滚 → 同签名崩溃检测 → 兜底安全模式，反正不会锁死你。
4. **AGENTS.md 上下文种子**：App 启动时往 $HOME 预写一份「Android 环境说明书」，Agent 第一轮就知道自己是 bionic 不是 glibc、没有 apt、该用 node 起 HTTP 服务——省掉了每次冷启动的环境探索 token。

### 权限是分级的

普通（沙箱）/ Shizuku（adb 级）/ Root（全盘）三级，应用内一键切换。能力没就绪的选项直接置灰点不了；Root 有双重确认 + 启动前自动备份。不授权就老老实实待在沙箱里。

### 实话说说边界

- 没有 GUI 桌面，视觉产物走本地 HTTP 预览
- 预装没有 Python/gcc（但 Root 模式下实测 Agent 能自己装……甚至装了 Android SDK）
- 读屏基于无障碍节点树，对游戏画面无效

v1.1.0 刚发布，欢迎试试、提 issue、点个 star。也欢迎聊聊你在手机上跑 Agent 的姿势。

---

## 备选标题

- 给 DeepSeek Harness 做了个 Android 壳：67MB、免 Root 免 Termux、开源
- 手机上跑 DeepSeek Agent 是什么体验：我写了个开源壳（附技术细节）
- DeepSeek Harness 手机端方案对比后，我自己写了个最干净的
