# DSH Mobile v1.6.0-beta.2 — 补 beta.1 之后的 59 个提交

> **发版形态**：试发行（versionCode **377**，Release 标 `prerelease: true`，不进 `releases/latest`，
> 正式版用户不会被动升级）。正式版说明仍以 [RELEASE_NOTES_v1.6.0.md](RELEASE_NOTES_v1.6.0.md) 为底稿，
> 本文只补 beta.1 之后新进包的内容。

## 1. 虚拟屏（vscreen）—— 给 AI 的第二块屏

`DisplayManager.createVirtualDisplay` 在系统内部另开一块逻辑屏（默认 720x1280），把目标 App 启到那块屏上。
AI 在那块屏上截图、点击、输入，**用户的物理屏完全不受影响**，两边可以同时用。

- 门脚本 `vscreen`：start / status / stop / shot / launch / tap / swipe / key / type / journal。
- 核心是 uid 0 的独立 `app_process`（ppid 会被 init 收养），App 被杀带不走它 —— 用完务必 `stop`。
- 只有 ROOT 模式可用：TRUSTED flag 要签名级权限，跨屏启动非导出组件要 uid 0。
- 无障碍节点操作可按 `display_id` 落到虚拟屏（`dev node` 全系 op 都支持）。

## 2. 记忆回放质量大修

- 指纹降噪、覆盖率打分、toggle 幂等（回放 tap 前先判断当前屏是否已是执行后状态）。
- pending 记忆有了出路：同一场景再成功一次才转正。

## 3. 扩展中心

- 行内「下载」按钮从 64×48dp 实心收到视觉 56×40dp 的 tonal 轻底（触控面仍 64×48dp，不降无障碍标准）。
- 前景色与版本胶囊跟随明暗主题。

## 4. 灵动岛

- 配色分暗/浅两套，运行模式浮层跟随明暗主题。

## 5. 容器与门脚本

- 容器模式门脚本链路全修：响应读取不再 fork（`read -N` 内建读），失败不再伪装成功。
- `push-via-api` 不再改写 blob 内容（二进制会被毁）。

## 6. AI 能力暴露面

- 设备路由从 108 条补到 **176 条**，目录项同步。

## 7. 已知限制

- 与 beta.1 相同，见 [RELEASE_NOTES_v1.6.0.md](RELEASE_NOTES_v1.6.0.md) §5。
- 虚拟屏只有 ROOT 模式可用；Shizuku 能建屏但拿不到 TRUSTED flag。
