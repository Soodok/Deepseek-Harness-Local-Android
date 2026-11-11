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
