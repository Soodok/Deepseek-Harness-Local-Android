# 轨迹样本（Trace Fixtures）

> M0 / F0.2 产出。格式见 [../../docs/A11Y_TRACE_CONTRACT.md](../../docs/A11Y_TRACE_CONTRACT.md)。
> 这三份是 `TracePruner` 的**验收基准**：M2 的 JVM 断言必须逐条对上下面这张表。

## 文件与期望剪枝结果

| 文件 | 行数 | 原始步骤 | 剪枝后应保留 | 应删除 |
| --- | --- | --- | --- | --- |
| `trace_ok.jsonl` | 7（1 头 + 5 步 + 1 尾） | launch, tap显示, tap深色主题, tap开关, noop | **4** | noop（观测动作） |
| `trace_misclick.jsonl` | 7（1 头 + 5 步 + 1 尾） | launch, tap显示, tap亮度级别(unchanged), tap深色主题, tap开关 | **4** | 亮度级别（误点，verify=unchanged 且有替代动作） |
| `trace_loop_detour.jsonl` | 11（1 头 + 9 步 + 1 尾） | launch, tap显示, tap电池, key返回, scroll×3, noop, tap屏幕超时 | **4** | tap电池 + key返回（绕路，A→B→A 且回退后指纹回到 A）、3 次滚动折叠成 1 条 `scroll_to`、noop |

## 断言要点（M2 必须覆盖）

1. **保留动作的顺序不变**：剪枝只能删/折叠，不能重排。
2. **折叠后的 scroll 带目标**：`{type:"scroll_to", text:"屏幕超时"}` —— 目标取「折叠区间之后第一个 tap 的 anchor.text」。
3. **理由可查**：每条删除产出 `{rule, index, why}`，`rule` 取值见契约 §5 的六条。
4. **保护页不剪只拦**：`protected=true` 的步骤不算删除，而是整条记忆 `state=disabled`。
5. **失败任务不存**：`end.ok=false` 的轨迹剪枝后只产出负样本标记，不产出可执行步骤。

## 怎么加新样本

1. 复制任意一份，改 `traceId`（必须唯一）与步骤；
2. 在**本表**加一行，写清「原始步骤 → 剪枝后」；
3. 同步在 `scripts/jvm/BehaviourTest.kt` 加断言 —— 表与断言不一致视为门禁失败。
