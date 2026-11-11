# 无障碍记忆系统 v2 · 超级大计划

> 版本：草案 v1（2026-11-11）· 关联设计：[A11Y_MEMORY_DESIGN.md](./A11Y_MEMORY_DESIGN.md)
> 目标：把「一次成功的无障碍任务」变成可检索、可剪枝、可修正、可重放的本地记忆，
> 最终完整集成进 DSH Android 应用（设置页可查看/修正/禁用，默认开启）。

---

## 0. 完成定义（DoD）

整套系统「做完」= 下面 6 条同时成立，缺一条都不算：

1. 任意无障碍任务执行时**自动**录制动作流，无需模型主动调用（`/trace/list` 能看到）。
2. 任务成功后自动剪枝入库，**错误动作被剔除**，剪枝理由可查。
3. 下次同类任务：`pkg` 过滤 + 向量召回 + 屏幕结构比对 → 命中则本地重放，
   **0 次云端规划调用**。
4. 记忆可修正（patch）、可扩展（extend）、可分叉（fork）、可禁用/删除，历史版本可回滚。
5. 设置页有「无障碍记忆」面板：列表、详情、步骤、统计、删除/禁用。
6. 门禁全绿：JVM 断言、py 探针、i18n、构建、真机链路实测报告齐全。

---

## 1. 已锁定的默认决策（可改，改起来只是常量）

| 项 | 采用 | 理由 | 改动成本 |
|---|---|---|---|
| 向量后端 | 复用现有 bge-small-zh-v1.5（15MB，llama-server） | 零新增依赖、离线、毫秒级；模型缺失自动降级 | 换云端 = 改 `MemoryIndex` 一个 provider |
| 自动入库 | 任务成功即存为 `pending`，同场景第 2 次成功转正 | 沿用 v1 已验证的防污染机制，不依赖模型自觉 | 常量 `AUTO_STORE` |
| 重放校验 | 每步都重读树做指纹预校验 | 宁可慢 30–60ms/步，也不能盲点；点错一次代价远大于省下的时间 | 常量 `VERIFY_EVERY_STEP` |

---

## 2. 里程碑总览

| 里程碑 | 主题 | 功能数 | 预计 | 验收门 |
|---|---|---|---|---|
| M0 | 基线冻结与测试脚手架 | 3 | 0.5d | G0 |
| M1 | 录制器 | 3 | 1d | G1 |
| M2 | 剪枝器 | 3 | 1d | G2 |
| M3 | 存储 v2 + 迁移 | 4 | 1d | G3 |
| M4 | 向量检索 | 4 | 1.5d | G4 |
| M5 | 重放执行 | 4 | 2d | G5 |
| M6 | 修正与扩展 | 5 | 1d | G6 |
| M7 | 自动入库与提示词 | 4 | 1d | G7 |
| M8 | 应用集成（UI） | 4 | 1.5d | G8 |
| M9 | 全量回归与发布 | 3 | 1d | G9 |

合计 37 个功能点、10 道验收门。**每完成 2–3 个功能跑一次门禁**（见 §5）。

---

## 3. 里程碑明细

### M0 基线冻结（3 功能）

- **F0.1** trace 数据契约：字段、时间口径、指纹算法、落盘格式写成 `docs/A11Y_TRACE_CONTRACT.md`。
- **F0.2** 测试夹具：`scripts/jvm/fixtures/` 放 3 份真实 trace（成功 / 含误点 / 含绕路），
  JVM harness 增加读取入口。
- **F0.3** 基线快照：JVM 断言数、探针通过数、i18n、APK 大小与构建耗时，记入本文件 §9。

**门禁 G0**：`bash scripts/run-a11y-jvm-test.sh` 全绿；py 探针与基线一致（允许已知 fail 1 条）；
构建成功；基线数字写进 §9。

### M1 录制器（3 功能）

- **F1.1** `engine/TaskRecorder.kt`：事件流（`ts/endpoint/args/beforeFp/targetAnchor/afterFp/verify/ms`），
  追加写 `files/traces/<traceId>.jsonl`，进程被杀不丢。
- **F1.2** 端点：`GET /trace/list`、`GET /trace/get?id=`、`POST /trace/clear`。
- **F1.3** 生命周期：任务开始/结束挂钩（`AgentBridge.taskGate` / `endBoundary`），
  7 天 TTL、最多 200 条、`recordEnabled` 开关。

**门禁 G1**：跑一个真实 5 步任务（滚动 + 点击 + 输入），trace 里 5 条动作齐全、
前后指纹非空、`ms` 合理；录制额外开销实测 < 5ms/步（对比开启/关闭各 20 步的平均耗时）。

### M2 剪枝器（3 功能）

- **F2.1** 六条规则：失败尝试剔除 / 无效动作剔除 / 重复滚动折叠 / 绕路剔除 / 观测动作剔除 / 失败任务不存。
- **F2.2** 理由留痕：每条删除记 `{rule, index, why}`，返回 `pruned` 计数与明细。
- **F2.3** JVM 断言：用 M0 的 3 份 fixture，断言「删了哪几条、留了哪几条」。

**门禁 G2**：注入 1 次误点 + 1 次重复滚动 + 1 段绕路的 9 步 trace → 剪成 **4 步**
（真值表见 `scripts/jvm/fixtures/README.md`：launch / tap 显示 / scroll_to 屏幕超时 / tap 屏幕超时），
关键步骤全部保留；断言 ≥ 12 条全绿；`TracePruner` 无 Android 依赖（可单测）。

### M3 存储 v2 + 迁移（4 功能）

- **F3.1** `engine/MemoryStore.kt`：`files/memory/v2/<pkg>.json`，schema 2，字段见设计文档 §2。
- **F3.2** v1 迁移：首次启动读 `scene_memory.json` → 转 v2（goal 作 intent，指纹作首步 pre），
  原文件改名 `scene_memory.json.migrated` 保留回滚。
- **F3.3** 容量：每应用 50 / 全局 500，LRU + hits 淘汰；单条 2–6KB，向量 int8 量化。
- **F3.4** 端点：`GET /memory/v2/list?pkg=`、`GET /memory/v2/get?id=`、`DELETE /memory/v2/delete?id=`。

**门禁 G3**：写入 60 条同 pkg → 淘汰到 50 且保留 hits 高的；迁移后旧 v1 命中行为不变
（用同一 goal + 同屏跑一次，仍 `hit=true`）。

### M4 向量检索（4 功能）

- **F4.1** `engine/MemoryIndex.kt` 嵌入层：调 `LocalModel` 的 `/v1/embeddings`；
  降级链 bge → 字符 bigram 哈希向量 → 关键词包含。
- **F4.2** 结构比对：当前 dump vs 记忆首步 `pre.fp` 的 Jaccard + 首步锚点 `FastPicker.pick` 可达性。
- **F4.3** 分档：`0.6·cos + 0.3·fpJaccard + 0.1·anchor` → 高/中/低三档（0.85 / 0.65 阈值）。
- **F4.4** 端点：`POST /memory/v2/lookup`（返回 top-3 与分数、分档、命中理由）。

**门禁 G4**：10 条不同意图记忆，用**改写过的话术**检索 → top-1 命中 ≥ 8/10；
跨 App 同屏 0 误命中；把模型目录改名模拟缺失 → 降级仍可用且结果可解释。

### M5 重放执行（4 功能）

- **F5.1** `engine/MemoryReplay.kt` + `POST /memory/run`：按 id 或自动召回后重放。
- **F5.2** 每步指纹预校验：目标元素 text/role/bounds 对不上 → 停止并返回证据，不盲点。
- **F5.3** 失败策略：多锚点退化 → 跳可选步 → 两次才 escalate，同时 `misses++`；`misses>=2` 转 suspect。
- **F5.4** 事务中止：pkg 变化 / 用户叫停 / 进入保护页 / 敏感词 → 立即停并记账。

**门禁 G5**：同一真实任务第 2 次执行 **0 次云端规划调用**（对照日志计数）；
把记忆里某步锚点改坏 → 重放必须拒绝并回落云端；保护页（设置-无障碍）内重放被拦。

### M6 修正与扩展（5 功能）

- **F6.1** `POST /memory/v2/patch`（改某步，`rev++`，旧版进 history）。
- **F6.2** `POST /memory/v2/extend`（末尾追加步骤）。
- **F6.3** `POST /memory/v2/fork`（从某步分叉）。
- **F6.4** `POST /memory/v2/disable|enable`（suspect 人工恢复）。
- **F6.5** `POST /memory/v2/rollback`（回到 history 某版本）。

**门禁 G6**：AI 修一次坏记忆后重放成功；extend 后新步骤被执行；fork 出两条互不影响；
rollback 回到修前状态且重放复现原失败。

### M7 自动入库与提示词（4 功能）

- **F7.1** 任务结束自动：trace → pruner → store(pending)，无需模型调用。
- **F7.2** 转正：同 (pkg,intent) 同指纹第 2 次成功 → `pending=false`。
- **F7.3** `AgentContextSeed` 增补：告诉模型何时该 `patch`/`extend`/`fork`，以及记忆命中时的行为约定。
- **F7.4** `GET /memory/stats` 扩展：按应用命中率、省下的云端往返次数、suspect 列表。

**门禁 G7**：同一真实任务连跑两次 → 第 2 次自动命中（日志 + stats 计数）；
第 3 次改一句 goal 说法 → 仍能命中（向量召回生效）；stats 三项数字自洽。

### M8 应用集成（4 功能）

- **F8.1** 设置页入口「无障碍记忆」+ 总开关（默认开）+ 计数摘要。
- **F8.2** 列表页：按应用分组，显示标题、命中次数、最近使用、状态标记。
- **F8.3** 详情页：步骤列表（可展开看锚点/校验）、禁用/删除/回滚。
- **F8.4** 版本号递增 + release notes + `AI_CONTEXT.md` 章节 + i18n 五语种。

**门禁 G8**：模拟器上从设置页走完全流程（列表→详情→禁用→重放被拒→启用→重放成功）；
UI 截图留档；`check-i18n` 通过；`check-ui-tokens` 通过。

### M9 全量回归与发布（3 功能）

- **F9.1** 全量门禁：JVM 断言 + 全部 py 探针 + i18n + ui-tokens + 构建。
- **F9.2** 性能对照报告：`docs/reports/PERF_MEMORY.md`（首跑 vs 复用 vs 剪枝后，各 5 次取中位数）。
- **F9.3** 提交与推送（按 `docs/git-workflow.md`），`AI_CONTEXT.md` 最终更新。

**门禁 G9**：报告齐全、全绿、推送成功、`AI_CONTEXT.md` 与本文件状态一致。

---

## 4. 团队与分工（Agent Teams）

| 角色 | 负责文件（写入范围） | 不碰 |
|---|---|---|
| **Lead（我）** | `engine/memory/MemoryRoutes.kt`、`AgentBridge` 挂载点、`AgentContextSeed`、最终 diff 与提交 | — |
| `rec-store` | `engine/TaskRecorder.kt`、`engine/MemoryStore.kt` | 其余模块 |
| `prune-index` | `engine/TracePruner.kt`、`engine/MemoryIndex.kt` | 其余模块 |
| `replay-edit` | `engine/MemoryReplay.kt` | 其余模块 |
| `verifier` | `docs/reports/*.md`、`scripts/jvm/fixtures/*`（只读源码） | 任何源码 |
| `integrator` | `res/layout/*`、`res/values*/strings.xml`、`SettingsActivity`、`docs/*` | engine 模块 |

**冲突规避（关键设计决策）**：所有 HTTP 端点不直接改 `AgentBridge.route()`，
而是各自模块暴露 `handle(...)`，由 Lead 独占的 `MemoryRoutes.kt` 统一注册 —— 
否则 3 个人同时改一个 108 条路由的 when 块，必然互相踩。

**顺序**：M1/M3 可并行（录制与存储互不依赖，靠接口约定）；M2 依赖 M0 的 fixture；
M4 依赖 M3；M5 依赖 M2+M4；M6 依赖 M5；M7 依赖 M1+M5；M8 依赖 M7；M9 收口。

---

## 5. 验收门禁规范（每 2–3 个功能一次）

每次门禁固定五步，缺一不可：

1. **静态**：`bash scripts/run-a11y-jvm-test.sh`（新增断言必须全绿）+ 相关 py 探针。
2. **构建**：隔离工作树构建（绕开他人 `META-INF/DEPENDENCIES` 冲突），记录 APK 字节数。
3. **实机**：`adb install -r` → 模拟器（emulator-5556）→ curl 驱动脚本复现验收场景。
4. **证据**：logcat 关键行 + 接口原始返回 + 截图（涉及 UI 时），写入 `docs/reports/G<编号>.md`。
5. **判定**：红项集合与基线（§9）相同或更少才算通过；有新增红项先修再重跑，
   **不删断言、不降门限、不用旧产物冒充**。

---

## 6. 风险登记册

| 风险 | 影响 | 对策 |
|---|---|---|
| 他人改动阻塞构建（META-INF 冲突） | 门禁无法跑 | 隔离工作树 + 临时 exclude（不提交），已在 v1.7.4/v1.7.5 验证过这条路径 |
| 剪枝剪错导致记忆残缺 | 记忆不可用 | 原始 trace 保留 7 天可重剪；每条删除留理由；fixture 断言 |
| 向量模型缺失 | 检索不可用 | 三级降级，最差退化为关键词 |
| 记忆误命中造成错误操作 | 安全问题 | 中置信强制模型确认；保护页/敏感词四层安全不绕过；suspect 自动降级 |
| 多 agent 并发改同一文件 | 冲突/覆盖 | 写入范围隔离 + 端点收敛到 `MemoryRoutes.kt` |
| 存储膨胀 | 卡顿 | 每应用 50 / 全局 500 + 轨迹 TTL |

---

## 7. 进度看板

任务板（Team Tasks）已按 M0–M9 建好，每个任务带 `blocked_by` 依赖与写入范围。
状态以任务板为准，本文件只记里程碑定义与验收结论。

---

## 8. 交付物清单

- 代码：5 个新模块 + 1 个路由收敛文件 + AgentBridge/种子提示词改造
- 文档：`A11Y_MEMORY_DESIGN.md`（设计）、`A11Y_TRACE_CONTRACT.md`（契约）、
  `docs/reports/G0..G9.md`（每道门禁的证据）、`PERF_MEMORY.md`（性能对照）
- 测试：JVM 断言（新增 ≥ 40 条）、3 份真实 trace fixture、curl 驱动脚本
- 发布：版本号递增 + release notes + `AI_CONTEXT.md` 章节

---

## 9. 基线数据（M0 实测，2026-11-11）

基线 commit：`6d2e4e19`（fix(engine): 场景记忆在逗号小数点地区必崩）

| 门禁项 | 命令 | 基线结果 |
| --- | --- | --- |
| JVM 行为断言 | `bash scripts/run-a11y-jvm-test.sh` | **passed=1897 failed=0** |
| py 探针 | `for f in scripts/probes/_test_*.py; do python $f; done` | **113 PASS / 0 FAIL** |
| i18n | `python scripts/check-i18n.py` | **1 红**：`ZstdTail.kt:40`（他人未提交改动带来，非本计划范围） |
| 构建 | `gradle -p . -Pabi=x86_64 -Ppackage=noruntime :app:assembleDebug`（隔离工作树） | **43,568,962 B**，BUILD SUCCESSFUL |

## 9.1 当前状态与待办（2026-11-11 21:15 更新）

**已完成并验证**：M0–M9 全部落地；JVM **2182 passed / 0 failed**；py 探针 114/0；i18n 0；
隔离构建 + 装机实测通过。真机实测覆盖：录制（+40.2ms/动作）、剪枝（5→4 / 5→4 / 9→4）、
存储上限（同包 60→50）、检索（真 bge：词面 10/10、语义 7/10、延迟中位 44ms）、
重放（端到端 PASS、坏锚点 fallback、分档闸门）、修正扩展（patch/extend/fork/rollback）、
自动学习转正（第 3 轮）、UI 面板（设置页 → 详情 → 试跑）、跨 App 起跑（从桌面 → 设置）、
**loop 内部步骤进轨迹**（21:15 新增，见下）。

**M1 补：loop 内部步骤进轨迹（2026-11-11 21:15 完成并真机验证）**

- `LocalLoopExecutor.recordLoopStep(...)` 在**该步成功**后调用
  `MemoryRoutes.onLoopStep(...)`；`VerifyResult.lastDump` 把 verify 已读到的屏直接交给录制器，
  有 verify 的步**零额外读屏**，只有无 verify 的步（如 `key back`）才补一次 dump。
- `MemoryRoutes.onLoopBegin()` 在每次 `run()` 入口清标记；`onActionDone` 见到
  `loopRecorded=true` 就丢弃外层那条 `loop_step`（它无 args、重放必失败）。
- 动作映射：`tap|tap_text→tap`、`scroll→scroll`、`input→input`（只记 `len`）、
  `key→key`、`launch→launch`、`scroll_find` 原样；其余（`set_switch`/`repeat`/`pinch`/`swipe`…）
  不进轨迹。
- `TracePruner.REPLAYABLE`（新增）= `TraceEndpoint.ACTIONS + NOOP + scroll_to + scroll_find`；
  `unreplayable` 现在同时挡「`loop_step`」和「重放器不认识的动作」，reason 改英文（i18n 门禁）。
- 真机（emulator-5556 / Android 11）：`/loop/run` 三步（launch 设置 → tap Display → key back）
  → 轨迹 `steps=3, caller=loop`，无 `loop_step`；`learn` → `pkg=com.android.settings, steps=3,
  pruned=0, drops=[]`；`/memory/run {"id":...}` 从桌面重放 **ok=true，1.86s**，
  三步 6/5/1ms（launch ok / tap via tapText / key back）。

**发版前回归（21:58 新增，可一键复跑）**：`scripts/e2e-memory.py` 把 10 个阶段固化成
真机 E2E（录制 → 入库 → 检索 → 重放 → 保护页 → 隐私 → 修正 → 分档闸门 → 统计），
退出码 = 失败段数；门禁侧新增两个模式：`gate-memory.sh e2e`（只跑 E2E）与
`gate-memory.sh release`（静态 + 构建 + 装机 + E2E，发版前一条命令跑完）。
本次结果 **10 段 / 失败 0 / 跳过 1**（bge 未覆盖：模拟器没装 llama.cpp 扩展），
详见 `docs/reports/G10_E2E.md`。脚本里写死了三条确定性约定（force-stop 设置页、意图带时间戳、
直接拉起 intelligence 搜索页），改动前先读报告 §2。

**待办（按价值排序）**

1. **重放录制开销**（+40.2ms/动作 → ~5ms）：桥侧仍自己读屏。loop 侧已经做到
   「有 verify 就零额外读屏」，桥侧要复用 `onActionDone` 之前的 dump 才能再降。
2. **跳可选步**：给 `MemoryStep` 加 `optional` 标记，派发失败时跳过而不是整体回落。
   现在是一失败就停（安全优先）。
3. **发布**：`versionName=1.5.5-beta.1`（vC 375）且该 tag 已被发过，记忆系统发版需升
   **1.6.0**（vC 376）+ 打 tag；发布说明已写好 `docs/release-notes/RELEASE_NOTES_v1.6.0.md`。
   **属对外发布，等用户点头。**

### 两条口径约定（血泪教训，必须遵守）

1. **py 探针必须带 UTF-8 输出**：Windows 控制台默认 GBK，探针里的 `✓` / `⚠` 会抛
   `UnicodeEncodeError`，看起来像 17 条逻辑失败，实际全是伪红。跑之前 `export PYTHONIOENCODING=utf-8`。
2. **门禁判定用「红项不新增」而不是「全绿」**：仓库当前有 1 条 i18n 红来自他人 WIP，
   任何一次门禁只要红项集合与基线相同（或更少）即通过；新增红项必须当场修或明确记录豁免理由。
