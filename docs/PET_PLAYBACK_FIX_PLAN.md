# 桌宠动作播放修复 + 桥插件门禁下沉 —— 实施计划

> **2026-10-27 更新**：本文档中的 `allowWithoutBridge` 软旁路在 P3 落地之后已
> **整体删除**（用户明确要求：无资源包/无插件/无权限一律不能开启桌宠、不能跳过）。
> 现行门禁口径见 `docs/PET_OVERLAY.md` §5.3 与 `PetPluginDetector.canEnable()`；
> 本文其余内容保留作历史记录。

- 版本：v1（2026-10-27）
- 分支：`feat/pet-packs`
- 设备基线：MuMu `127.0.0.1:5555`，3840×2160 @540dpi，资源包 `dsh-pet-480p24`，尺寸 480dp（窗口 632×725）
- 状态：**计划待批准，未改代码**

---

## 0. 结论摘要（本轮排查到的三件事）

| 问题 | 结论 |
|---|---|
| 装的是不是 main 最新代码 | 分支已包含 main 全部提交（`HEAD..main` = 0）；但工作区有 13 个未提交改动，设备上的 APK 是 23:22 构建的工作区版本，**不是 commit 快照** |
| 没装桥插件也能开桌宠 | 门禁从来没被"去掉"，它只存在于设置页开关那一条路径，且是**软门禁**（有「仍然开启」）。`restoreIfEnabled()`（App 每次 onResume）与服务 `START_STICKY` 重建**完全不查桥插件** |
| 长按选动作不起作用 | 点击链路是通的（logcat 实测 `play 悠闲哼歌 once=true mode=ONCE`，画面 0.7s 内开始变化）。但存在两个真缺陷：①`repeatCount=1` 让一次性动画播两遍而链按一遍推进；②`play()` 失败分支静默 return / 静默清屏，且抓到过一次 **≥3.4s 零像素变化**的冻结，现有日志无法归因 |

### 关键证据
- `git rev-list --count HEAD..main` → `0`；`git status --porcelain` → 13 项；APK mtime 23:22，设备 `lastUpdateTime=23:23:06`
- `PetSettingsActivity.kt:247-274` 软门禁；`PetOverlayService.kt:1395-1401` `restoreIfEnabled()` 无桥检查；`onStartCommand` 只在 `enabled`/权限上拦截
- `PetView.kt` `Api28.restart()`：`repeatCount = if (loop) REPEAT_INFINITE else 1`
- 实测冻结：连拍 3 张 ×1.2s，宠物窗口 632×725 区域**逐对差分 bbox = None**；同一时段 logcat 出现 7 条 `A resource failed to call close`（指向解码失败/重试的 fd 泄漏）
- 清单时长：该包 106 段动画，每段约 **10.27s**（`durations` 5033~10282ms）

---

## 1. 目标与非目标

**目标**
1. 一次性动画（点击回应 / 菜单动作 / 工作态单段）**播一遍就结束**，且结束点与动画链推进点对齐。
2. 播放失败与卡帧**可观测**（日志 + 状态），并能在下一版自愈。
3. 桥插件门禁覆盖**所有**启动路径，用户在宠物不显示时能看到明确原因。

**非目标**
- 不改气泡样式/位置（上一轮已验收）。
- 不改资源包格式、不动解码分辨率策略。
- 不改随机链的权重与选段逻辑。

---

## 2. 工作包与改动清单

### P0 基线（需你确认，0.5 步）
工作区现有 13 个未提交改动（气泡重做 + 窗口裁形 + 过滤上屏）。建议先提交一次，使本次修复成为独立 commit、可单独 revert。

```
feat(pet): 气泡重做 + 窗口按角色矩形收窄 + 过滤上屏
```

> 不擅自提交；等你确认后执行。

---

### P1 可观测性 + 卡帧检测（`PetView.kt`）

**P1.1 失败分支不再静默**

在 `PetView.play()` 的 4 个早退分支加日志（`Log.isLoggable(TAG, Log.WARN)` 包裹，新增 `private const val TAG = "DshPetView"`）：

| 分支 | 现状 | 改为 |
|---|---|---|
| `!cfg.isAvailable(slug)` | 静默 `return` | `Log.w` + 上报 `unavailable`，不动画面 |
| `!file.isFile` | 静默 `return` | `Log.w` + 上报 `no_asset`，不动画面 |
| API28 解码 `d == null` | `retireAll()` 清屏 | `Log.w` + 上报 `decode_failed`，**保留当前帧** |
| API<28 `movie == null` | `retireAll()` 清屏 | 同上 |

新增回调：

```kotlin
/** 播放失败上报（slug, reason）；Service 写 PetStatusBus.lastError 并限流。 */
var onPlaybackError: ((slug: String, reason: String) -> Unit)? = null
```

Service 侧在 `attachPet()` 接线，同 slug 5s 内只报一次（`HashMap<String, Long>` 限流）。

**P1.2 帧计数**

`onDraw(...)` 末尾：`frameSeq++`、`lastFrameAt = SystemClock.uptimeMillis()`。

**P1.3 卡帧检测（第一版只观察，不自愈）**

```kotlin
// 500ms 一次；只在"应当有动"时判定
if (!paused && front.drawable is AnimatedImageDrawable && now - lastFrameAt > STALL_MS) {
    Log.w(TAG, "stalled ${front.slug} ${now - lastFrameAt}ms loop=${front.loop}")
    lastFrameAt = now          // 防刷屏
}
```

常量 `STALL_MS = 2000L`、`STALL_CHECK_MS = 500L`。**第一版不改画面**，目的只是拿到"冻在哪一段、冻多久"的一手证据；P4 验证无误报后再打开自愈（`invalidate()` → `Api28.restart(front.drawable, front.loop)`）。

**P1.4** `pauseDrawing(false)` 里重置 `lastFrameAt`，避免息屏恢复后误判。

**P1.5** `release()` 清 `onPlaybackError`（与现有 `onPetClick`/`onPetLongPress` 一致）。

---

### P2 一次性动画时长对齐（`PetView.kt`）

**P2.1** `Api28.restart()`：

```kotlin
d.repeatCount = if (loop) AnimatedImageDrawable.REPEAT_INFINITE else 0   // 原为 1
```

`repeatCount` 是"重复次数"：`1` = 播完再重播一轮（共两遍），而 `scheduleOneShot` 只按一遍的时长推进链 → 动作被截断，或定时器晚到导致"播完又从头开始"。

**P2.2** `scheduleOneShot` 时长口径统一为 `durationMs.coerceIn(400L, 30_000L)`，并在日志里打 `expected=${durationMs}ms`，便于 V2 用实测帧差核对"实际结束时刻 ≈ 定时器时刻"。

**P2.3** `land()` 补一条 debug 日志（slug / loop / token），用于区分"没落地"发生在解码前还是上屏前。

---

### P3 桥插件门禁下沉

**P3.1 `PetPrefs`** 新增 `allowWithoutBridge`（默认 `false`），仅在设置页软门禁点「仍然开启」时置 `true`。

**P3.2 `PetOverlayService`**

```kotlin
private fun bridgeReady(): Boolean = PetPluginDetector.detect(this).bridgeReady
```

`onStartCommand` 在 `enabled` / 悬浮窗权限检查之后插入：

```kotlin
if (!bridgeReady() && !PetPrefs.allowWithoutBridge(this)) {
    startAsForeground()                       // 前台服务照跑：桥接端口、状态查询不中断
    PetStatusBus.update { it.copy(running = true, bridgeMissing = true) }
    updateNotification()                      // 通知栏："桌宠已暂停：桥插件未就绪"
    return START_STICKY                       // 不 attachPet → 不画角色
}
```

**P3.3 `PetStatus`** 增加 `val bridgeMissing: Boolean = false`；`restoreIfEnabled()` 保持现状（照常拉起服务，由服务决定画不画），这样用户能看到明确原因而不是宠物莫名消失。

**P3.4 `PetSettingsActivity.render()`**：开关卡片下方新增一行只读提示（`bridgeMissing` 时显示，warning 色），点击跳插件中心。

**P3.5 字符串**：3 语各新增 2 条（`pet_status_bridge_missing`、通知文案），`check-i18n.py` 必须全绿。

---

### P4 回归、自愈开关与文档

- **P4.1** 打开 P1.3 的自愈分支（阈值、restart、一次重播兜底），保留 `stalled` 日志。
- **P4.2** `scripts/probes/_test_pet_overlay.py` 新增断言：
  - `repeatCount` 用 `0`（且不再出现 `else 1`）
  - `DshPetView` TAG、`onPlaybackError`、`stallCheck`/`STALL_MS`
  - 服务里 `bridgeReady()`、`bridgeMissing`、`allowWithoutBridge`
  - 三语新串存在且不含占位词
- **P4.3** `docs/PET_OVERLAY.md` 新增两段：「动画播放与卡帧检测」「桥插件门禁」；`AI_CONTEXT.md` 同步。
- **P4.4** `.workbuddy/memory/2026-10-27.md` 记录根因、改法、实测数据与踩坑。

---

## 3. 验证方案

| # | 操作 | 判据 |
|---|---|---|
| V1 | `setprop log.tag.DshPetView DEBUG`，长按→动作→小动作，连点 5 个不同动作 | 每次都有 `play <slug> once=true` + `land`；**不出现** `decode_failed` / `stalled` |
| V2 | 连拍 8 帧 ×0.6s 算逐帧差；同时比对 `expected` 日志 | 除静态保持段外不允许连续 2s 零变化；时长 ≈ 10.27s（±0.5s）；**只播一遍** |
| V3 | 待机 / 点击回应 / 动作 三类各点 2 个 | 三类都生效；播完自动回随机链（日志出现下一次 `play ... mode=RANDOM`） |
| V4 | 人为注入卡帧（暂停解码线程 / 冻结 drawable） | 看门狗 2s 内打 `stalled` 并自愈；正常播放时 0 条误报 |
| V5 | 改坏 `node_modules/@xiaopb/dsh-pet-bridge/package.json` → 重启 App → `am force-stop` 再启动 | 不画角色；设置页显示"桥插件未就绪"；通知栏同文案；`allowWithoutBridge=true` 恢复旧行为 |
| V6 | 回归 | `_test_pet_overlay.py` 全绿（现 182 条 + 新增）；`check-i18n.py` 全绿；`logcat -b crash` 空；无 `Skipped frames` |

---

## 4. 风险与回滚

| 风险 | 处置 |
|---|---|
| 看门狗误报（静态保持段被当成卡死） | 第一版只 log 不自愈；阈值取 2s（远大于 24fps 的正常帧间隔）；确认无误报再开自愈 |
| "解码失败保留旧帧"导致卡在上一帧 | 比现状（清屏/黑掉）更可诊断；V1 日志能直接指出是哪一段、哪一步失败 |
| 门禁下沉后老用户宠物"消失" | 设置页与通知栏都有明确原因 + 一键跳插件中心；`allowWithoutBridge` 保留调试后门 |
| 改动面大 | P1~P4 各自独立 commit，可单独 revert；不动气泡/裁窗/资源包格式 |

---

## 5. 执行顺序

```
P0（可选提交） → P1 → 构建装机 → V1（拿冻结证据）
              → P2 → V2 / V3
              → P3 → V5
              → P4.1 开自愈 → V4
              → P4.2~P4.4 → V6
```

---

## 6. 待你确认

1. **P0**：是否先提交现有 13 个改动，让本次修复成为独立 commit？
2. **P1 第一版**：是否同意"只观察不自愈"，先拿到冻结证据再打开自愈？
