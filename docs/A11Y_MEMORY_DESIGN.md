# 无障碍记忆系统 v2 设计（草案 · 2026-11-11）

> 目标：一次成功的无障碍任务 → 自动录制 → 剪掉错误动作 → 向量化入库 → 下次同应用同意图直接本地重放，
> 模型只在「中置信」时介入一次。支持修正、扩展、每应用多条。

## 0. 一句话架构

```
任务开始 ──► TaskRecorder（录事件流：动作+前后指纹+校验结果）
任务结束 ──► TracePruner（按证据剪枝：失败尝试/无效动作/重复滚动/绕路）
          ──► MemoryStore v2（意图+向量+步骤序列+统计，按 pkg 分文件）
下次同类任务 ──► MemoryIndex（pkg 过滤 → bge 向量 top-k → 屏幕结构比对 → 分档）
          ──► MemoryReplay（每步指纹预校验，失败不升级云端，两次才 escalate）
```

## 1. 现状与差距（实测盘点）

**已有、可直接复用**
- `engine/SceneMemory.kt`：goal 精确匹配 + 屏幕文本集合 Jaccard ≥ 0.7，pending 二次确认才转正。
- `engine/LocalLoopExecutor.kt`：本地闭环执行（不逐步往返云端）、每步 verify、重试/升级、`when` 分支。
- `engine/LocalModel.kt`：**本地嵌入模型已就位** —— bge-small-zh-v1.5 q4_k_m（15MB），
  用 llama-server 在 127.0.0.1:3085 提供 `/v1/embeddings`，毫秒级。
- `engine/FastPicker.kt`：三层匹配（精确 → 语义余弦 → 放弃），语义层已经在调上面这个嵌入服务。
- `ScreenMask` / `ProtectedPages`：脱敏与保护页，记忆不得绕过。

**缺口**
1. 没有自动录制：现在只有 AI 主动写进 `/loop/run` 的 plan，轨迹（哪步失败、试了几次、绕了多远）不落盘。
2. 没有剪枝：plan 是「结果」，错误动作在轨迹里，一旦没录就永久丢失。
3. 检索只认字符串全等：goal 换个说法就永远 miss —— 这是「感觉没生效」的主因。
4. 每应用实际只有一条有效记忆（goal 必须全等，等价于单条）。
5. 没有修正/扩展/软删接口，只有 confirm / clear。

## 2. 数据模型（memory v2）

```jsonc
{
  "id": "m_01J8ZK...",
  "schema": 2,
  "pkg": "com.tencent.mm",
  "appVersion": "8.0.49",          // App 改版后只降置信、不删记忆
  "intent": "给张三发一条明天下午三点的会议提醒",  // AI 写入的归一化意图
  "intentVec": [0.0123, -0.044, ...],              // bge 512 维（可量化 int8 省 4 倍）
  "keywords": ["发消息", "提醒", "张三"],
  "title": "微信发提醒消息",
  "steps": [
    { "i": 1,
      "act":  { "type": "tap", "target": { "text": ["通讯录", "Contacts"] } },
      "pre":  { "fp": "…", "anchor": { "text": "通讯录", "role": "button",
                                       "bounds": [0.12, 0.31, 0.30, 0.35] } },
      "post": { "anyText": ["张三"], "pkg": "com.tencent.mm" },
      "evidence": { "verify": "ok", "ms": 420, "retries": 0 } }
  ],
  "variants": [                     // 同一意图的不同路径（有弹窗 / 没弹窗）
    { "when": { "anyText": ["允许"] }, "steps": [ /* 分支步骤 */ ] }
  ],
  "stats": { "hits": 3, "misses": 0, "lastUsed": 1794371673225, "avgMs": 5200 },
  "origin": { "traceId": "t_01J8…", "recordedAt": 1794…, "pruned": 7 },
  "state": "active | suspect | disabled",
  "history": [ { "rev": 1, "patch": "…", "at": … } ]
}
```

要点：一条记忆 = **意图 + 可重放步骤序列 + 每步前置指纹/后置校验**，不是一个裸 plan。
`pre.anchor` 是复用时「简单屏幕比对位置」的依据，`post` 是执行后自证。

## 3. 四个子系统

### A. TaskRecorder —— 自动录制（AI 不参与）

- 挂点：`AgentBridge.taskGate`（开始）/ `endBoundary`（结束）/ 每个动作端点
  （`/tap` `/gesture` `/input` `/key` `/scroll` `/launch` `/loop/run`）。
- 每次动作记一条：`{ts, endpoint, args, beforeFp, targetAnchor, afterFp, verify, ms, caller}`。
- 落盘 `files/traces/<traceId>.jsonl` 追加写（进程被杀也不丢），任务结束封存。
- 保留 7 天 / 最多 200 条，超出按时间淘汰。
- 关键：`beforeFp` 复用同一次 dump（执行链里本来就要读），额外开销 < 5ms。

### B. TracePruner —— 剪枝（纯规则，可 JVM 单测）

| 规则 | 判据 | 动作 |
|---|---|---|
| 失败尝试剔除 | 同目标多次尝试 | 只留最终成功那次 |
| 无效动作剔除 | verify = UNCHANGED 且有替代动作 | 删除 |
| 重复折叠 | 连续同向同距 scroll | 折叠成 `scroll_to(text)` 一条 |
| 绕路剔除 | A→B→A 且 B 后指纹与 A 相同 | 删 B 与回退动作 |
| 观测动作剔除 | screenshot / screen / dump / noop | 删除（这是省往返的主要来源） |
| 失败任务不存 | 任务整体失败 | 存为 `state=disabled` 负样本，供「别再这么干」 |

产出 `steps[]` + `pruned` 计数 + **每条删除的理由**（便于调参和排查）。

### C. MemoryIndex —— 检索（向量 + 结构，全本地）

1. `pkg` 精确过滤（不同 App 的同名页面不是同一件事）。
2. 意图向量：`LocalModel` bge 嵌入 → 余弦 top-3。
   降级链：bge → 字符 bigram 哈希向量 → 关键词包含（模型没下载也不阻塞）。
3. 屏幕结构比对：当前 dump 与记忆首步 `pre.fp` 的 Jaccard + 首步锚点能否 `FastPicker.pick` 到。
4. 打分：`score = 0.6·cos + 0.3·fpJaccard + 0.1·anchorFound`

| 分档 | 行为 |
|---|---|
| ≥ 0.85 | 直接重放，不惊动模型 |
| 0.65–0.85 | 把「记忆标题 + 前 3 步 + 当前屏摘要」给模型做一次 yes/no（1 次往返，结果缓存） |
| < 0.65 | 不召回，走原云端规划 |

### D. MemoryReplay —— 复用执行

复用 `LocalLoopExecutor`，独立入口 `/memory/run`，三点差异：
1. **每步先重读一次树做指纹预校验**（借鉴 dsh-Go 的「编号 + 指纹预校验」）：
   目标元素的 text / role / bounds 对不上 → 立刻停、把证据交回模型重规划，**绝不盲点**。
2. 失败不立即升级云端：先多锚点退化 → 再跳可选步 → 两次不行才 escalate，同时 `misses++`。
3. 整条记忆当事务：中途 pkg 变化 / 用户叫停 / 进入保护页 → 立即停，`misses++`；
   `misses >= 2` 自动转 `state=suspect`（不再自动命中，只作候选）。

## 4. 修正与扩展

- `POST /memory/{id}/patch`：改某一步的锚点/动作/校验（`rev++`，旧版进 `history`，可回滚）。
- `POST /memory/{id}/extend`：末尾追加步骤（「发提醒」→ 再加「附一张图」）。
- `POST /memory/{id}/fork`：从某步分叉成新记忆。
- `POST /memory/{id}/disable|enable|delete`（软删，可恢复）。
- 自动修正素材：`misses >= 2` 时把「失败那一步 + 当时屏幕摘要」存进 `failureEvidence`，
  下次 AI 修的时候不用重新读屏。
- 扩展语义：`variants[]` 承接「有弹窗/没弹窗」两条路径，正好复用 `LocalLoopExecutor` 已有的 `when` 分支。

## 5. 每应用多条

`(pkg, intent)` 唯一，一个 pkg 下 N 条。存储 `files/memory/v2/<pkg>.json`（按应用分文件，避免全局膨胀）。
容量：每应用 50 条 / 全局 500 条，LRU + hits 淘汰（复用现有 `MAX_ENTRIES` 逻辑）。
`/memory/list?pkg=…` 分页；v1 的 `scene_memory.json` 首次启动自动迁移（保留旧命中逻辑不变）。

## 6. 落地清单

| 新文件 | 职责 | 依赖 |
|---|---|---|
| `engine/TaskRecorder.kt` | 事件流录制/封存/TTL 清理 | File |
| `engine/TracePruner.kt` | 纯规则剪枝（无 Android 依赖，可 JVM 断言） | — |
| `engine/MemoryStore.kt` | v2 存储/索引/容量/v1 迁移 | File |
| `engine/MemoryIndex.kt` | 向量召回 + 结构打分 + 分档 | LocalModel / FastPicker |
| `engine/MemoryReplay.kt` | 重放执行 | LocalLoopExecutor |

改造点：
- `AgentBridge`：`taskGate` / `endBoundary` 挂录制开关；新增 `/trace/*`、`/memory/v2/*`。
- `LocalLoopExecutor.run`：`record=true` 时把每步证据写入 recorder。
- `AgentContextSeed`：告诉模型何时 `save` / `patch` / `extend`（工具表进每个请求，注意 KV cache）。
- `SceneMemory`：保留 v1 兼容，v2 走独立入口（与 v1.7.4「新旧并存」策略一致）。

## 7. 分阶段实施（每阶段都能单独验收）

| 阶段 | 内容 | 工作量 | 验收方式 |
|---|---|---|---|
| P0 | TaskRecorder + `/trace/list|get`（只录不改执行） | 0.5–1d | 跑 5 步任务，trace 里 5 条动作 + 前后指纹齐全 |
| P1 | TracePruner + JVM 断言（真实 trace 做 fixture） | 1d | 故意 1 次误点 + 1 次重复滚动，9 步剪成 5 步且关键步保留 |
| P2 | MemoryStore v2 + MemoryIndex（bge，含降级） | 1–1.5d | 10 条不同意图，改写话术检索 top-1 ≥ 8/10；跨 App 不误命中 |
| P3 | MemoryReplay + 指纹预校验 + misses 降级 | 1.5–2d | 同任务第 2 次 0 次云端调用；锚点改坏必须拒绝并回落 |
| P4 | patch / extend / fork / disable + 种子提示词 | 1d | AI 修一次后重放成功；history 可回滚 |
| P5 | 观测：按应用命中率、省下的往返数、suspect 列表 | 0.5d | `/memory/stats` 输出上述三项 |

## 8. 性能预算（设计阶段就定死）

- 录制：复用同一次 dump，额外 < 5ms/步。
- 嵌入：bge-small-zh 单条 < 10ms；500 条线性余弦 < 3ms。
- 重放：每步多一次读树校验（30–60ms），相比云端规划 0.6–1.6s/次 净赚。
- 存储：每条 2–6KB（向量量化 int8 后 1/4）。

## 9. 速度收益的诚实账

记忆**不改变单步执行时间**（本地闭环本来就是 1.7s/步）。它省的是：

1. 首次规划的云端往返（0.6–1.6s/次，实测记录在 `SceneMemory` 注释里）；
2. 失败后的升级往返；
3. 模型反复读屏的 token（一次 dump ≈ 3000 字符 ≈ 1.5k tokens）；
4. **剪枝带来的动作变少**（重复滚动折叠、绕路删除）—— 这一项往往比前三项加起来更值。

所以：8 步任务首次 ≈ 1 次规划 + 8×1.7s ≈ 14.6s；复用 ≈ 8×1.7s + 8×0.05s ≈ 14.4s，
**纯重放只快 1–2s**；但如果剪枝把 8 步压到 5 步，就是 14.6s → 9s（−38%）。
结论：**剪枝质量决定这个系统的价值**，向量检索只是「找得准」的前提。

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| App 改版导致记忆过期 | 存 `appVersion`；首步锚点校验失败只降置信、不删 |
| 误命中造成错误操作 | 中置信强制模型确认；保护页 / 敏感词四层安全不因记忆绕过 |
| 嵌入模型不可用 | 三级降级（bge → 哈希向量 → 关键词），不阻塞主链路 |
| 剪枝剪错 | 保留 `origin.traceId` 原始轨迹 7 天可重剪；剪枝规则逐条单测 |
| 存储膨胀 | 每应用 50 / 全局 500，LRU+hits 淘汰；轨迹 7 天 TTL |
| 隐私 | 轨迹与记忆仅存本地；导出需显式操作；脱敏沿用 `ScreenMask` |

## 11. 待拍板

1. **向量后端**：复用现有 bge-small-zh（推荐，零新增依赖、离线）还是接云端 embedding（语义更强、要联网）？
2. **自动保存触发**：任务成功即自动存（省事、有噪声）还是 AI 判断值得才存（准、依赖模型自觉）？
   建议：自动存 + 复用现有 `pending` 机制，第 2 次同场景成功转正。
3. **重放校验强度**：每步都重读树（安全，+30–60ms/步）还是只校验关键步（快，风险高）？建议默认每步、可配。

---

## 12. 逐步截图（2026-11-20 追加）

### 12.1 补的是哪一块缺口

v2 的记忆只有文本：意图 + 每步锚点 + 前置指纹 + 后置校验。它能回答「该点哪个」，
回答不了「这个页面长什么样」。两类场景会因此吃亏：

- **文本稀疏的界面**（图标墙、自绘控件、纯图片列表）：锚点文本取不到，模型只能靠坐标猜。
- **记忆失效**（App 改版、页面挪位）：文本层面只表现为 `verify-failed`，模型得重新 dump
  一遍才知道为什么；有图就能直接看出「按钮挪到下面了」。

所以：**每一步留一张缩略图，入库时拼成一张总览图（contact sheet）**，模型一次看图就
掌握整条任务的界面走向，不必逐步取帧。

### 12.2 为什么不阻塞动作路径

录制当前已 +40ms/动作（见 PLAN §9.1，是待优化首位），而一次 `takeScreenshot` 是
100–400ms 还可能撞系统节流。同步取帧等于把任务变慢一倍。

做法：单线程 worker + 有界队列（容量 3）+ 丢弃策略。动作线程只做一次入队；
队列满就丢新的那张 —— **宁可少一张图，也不能让界面等**。

丢弃不是无记录：`StepShots.stats()` 给出 `dropped` 计数，录制里图少了先看它。

### 12.3 体积账（都实测过）

| 项 | 值 | 说明 |
| --- | --- | --- |
| 缩略图宽 | 320px | WebP q55，单张约 18–22KB |
| 单条轨迹上限 | 24 张 | 再多格子细到认不出 |
| 单格宽 | 200px | 四列约 820px，与一屏信息量相当 |
| 全部预算 | 48MiB | 超了按目录 mtime 删最旧的，**至少留一份** |

8 步任务 ≈ 9 张 ≈ 180KB；500 条记忆全满约 90MB，靠预算兜底。
对比：直接存 1080×2280 PNG 是 300KB–1MB/张。

### 12.4 生命周期

```
traces/<id>.shots + traces/shots/<id>/   ← 录制期（7 天 TTL，随轨迹一起清）
        │ learn（剪枝后按 keptIndices 挑图）
        ▼
memory/v2/shots/<memoryId>/<index>.webp  ← 记忆资产（跟着记忆活）
memory/v2/shots/<memoryId>/strip.webp    ← 拼接总览图
```

- **入库时拷贝**而不是引用轨迹图：轨迹 7 天就没，记忆是长期资产。
- 删记忆 → 删图目录；重建（fork / 重新学习）→ 先清再写，避免残留孤儿图。
- 轨迹 TTL 清理时连带清侧车与图目录（`pruneOrphanTraces`，按主文件是否还在判断）。

### 12.5 模型怎么用

`GET /memory/v2/get?id=` 现在回三样：`strip`（拼图绝对路径）、`startShot`（起手屏）、
`images`（逐步图，与 `steps` 等长）。模型的做法应当是：

1. 看一眼 `strip`，对整条任务的界面走向有概念（**只读一次**，别无脑逐张读）；
2. 重放到某一步时如果指纹校验失败，再针对性看 `images[i]` 对比当下屏幕；
3. `hasImages=false` 是正常状态（抖音这类禁止截图的 App、或用户关了开关），不要重试。

逐张读图的代价：一张 320 宽的 WebP 进上下文约 200–300 token，8 步就是 2k+，
而拼图一次约 400 token。这就是「拼在一起」的意义。

### 12.6 隐私

- 保护页（密码框、支付页）不采样：由 `ProtectedPages.isProtectedFast` 判，命中即跳过。
- 图只在本机 `filesDir` 内，**不随记忆导出**，端点也只回路径不回 base64。
- 截图复用 `DshAccessibilityService.screenshotArgb`，它已处理「截前临时隐藏自己的悬浮窗」，
  所以拼图里不会出现状态条。

### 12.7 已知取舍

- **事后取帧**：第 k 张图是「第 k 步做完」而非「第 k+1 步之前」。界面自己还在动
  （转圈、视频、键盘弹出）时两者有细微差异。换「事前取帧」要多一次取帧或在动作前阻塞，
  都不划算。
- **不做图像比对**：拼图是给人/模型看的证据，不参与打分。指纹仍是文本，
  避免引入「图像相似度算错反而误导重放」这类新风险。
- **保护判定的时间差（残余窗口）**：调用方在**入队那一刻**判保护页，截图发生在
  0–1.6 秒后（队列延迟 + 取帧耗时）。`captureInner` 取帧前会再查一次**整包**保护
  （安装器 / 权限控制器 / OEM 安全中心），但这盖不住「同一 App 内晚渲染出来的敏感页」
  （某 App 自己的密码框、标题词晚于 dump 出现）。这是事后取帧架构的固有代价，
  不是可修的 bug。
- **快节奏 loop 会连丢中段帧**。判据是「不是最新请求的步就丢」：一次取帧 100–400ms
  （撞节流可到 1.2s），而 fastVerify 乐观步 200–400ms/步时，帧几乎必然在
  取完前就过期。结果是「留下的是每个喘息点后的帧」——宁可少图，不拍错图。
- **起手帧的宽限窗口**（`START_GRACE_MS = 3s`）。起手帧入队后首动作立刻开始，
  `latestAsked` 随即被改写，按上面的严格规则会**系统性**丢掉「这条记忆从哪个界面开始」
  （首动作 tap 200–800ms，取帧 100–400ms，重叠区很大；早期评估量级为 30–60% 的任务）。
  起手屏不参与重放判定（只是拼图第一格），所以给它 3 秒宽限：晚几百毫秒的起点图仍有价值，
  超过 3 秒才丢。这是个**刻意的例外**，不是判据松动。
- **队列满时静默丢帧**。取帧走单线程 + 有界队列（容量 3），满了直接丢这一帧并记
  `dropReasons["queue-full"]`。不用 `CallerRunsPolicy`（会把取帧压回动作线程），
  也不用 `DiscardPolicy`（它不抛异常也不执行任务，而 `pending` 计数与 per-trace 名额
  已经加过，没人补偿 → `pending` 永不归零，`awaitIdle` 每次白等 2.5 秒、名额烧光后整条
  轨迹停止截图）。所以走自定义拒绝处理器，丢帧时**把两笔账都还回去**。
- **图目录的三种孤儿清理**。① 轨迹侧：`pruneOrphanTraces` 按「主文件还在不在」扫；
  ② 记忆侧：`sweepOrphanMemoryDirs` 按「目录名 ∈ 存活记忆 id」扫（容量淘汰时没人删图，
  `enforceBudget` 只按总量兜底、删谁与记忆存活无关）；③ 清空：`/trace/clear` 先
  `invalidate`（写 tombstone + 删侧车与图）再删文件 —— 顺序反了会「清空返回成功但磁盘
  又长图」。`listIds` 只看现存 `.jsonl` 会漏掉「主文件已被 TTL 剪掉、图目录还赖着」的孤儿，
  所以清空要**合并**`TaskRecorder.listIds` 与 `StepShots.listTraceIdsWithShots` 两个来源。
- **traceId 复用撞上 tombstone（概率极低，未堵）**。`/trace/clear` 后 30 秒内，若新轨迹
  恰好生成同毫秒 + 同 4 位十六进制后缀的 id（`newId` 有 8 次重试，概率约 10⁻⁸ 量级），
  `onTaskBegin` 会清掉那条 tombstone，此时旧轨迹残留在队列里的**起手帧**（index=-1、
  不受 stale 判据约束）会被写进新轨迹的 `start.webp`。代价是一张错图，堵它要额外记录
  「帧入队时刻 vs 该 trace 最近一次 onTaskBegin 时刻」，性价比不足，故只记录不修。
