# 无障碍轨迹契约（Trace Contract v1）

> 归属：M0 / F0.1 · 上游设计：[A11Y_MEMORY_DESIGN.md](./A11Y_MEMORY_DESIGN.md)
> 这份文件是 `TaskRecorder` 与 `TracePruner` 之间**唯一的接口约定**。
> 任何一方改字段，必须同时改这里 + `scripts/jvm/fixtures/` 的样本 + 剪枝断言。

## 1. 文件布局

```
files/traces/<traceId>.jsonl      # 一次任务一个文件，追加写，行式 JSON
files/traces/_index.json          # 轻量索引（可重建，丢了不影响）
```

- `traceId` 格式：`t_<epochMillis>_<4位随机>`，进程重启不会撞。
- 追加写：每发生一次动作就 flush 一行，**进程被杀也不丢已完成的部分**。
- TTL：7 天；条数上限 200，超出按 `startedAt` 淘汰（保留最近）。

## 2. 行类型

### 2.1 头行（每个文件第 1 行，必须）

```jsonc
{
  "type": "trace",
  "traceId": "t_1794371673225_a1b2",
  "startedAt": 1794371673225,      // epoch ms，录制开始
  "pkg": "com.android.settings",   // 任务开始时的前台包名
  "goal": "打开深色主题",            // 任务目标（调用方给的，可为空）
  "device": { "w": 1080, "h": 2280, "density": 440 },
  "schema": 1
}
```

### 2.2 事件行（0..N 行）

```jsonc
{
  "type": "step",
  "ts": 1794371674100,             // epoch ms，动作**发起**时刻
  "endpoint": "tap",               // tap|tap_id|gesture|input|key|scroll|launch|loop_step|noop
  "args": { "x": 540, "y": 1520 }, // 端点原始参数（脱敏后）
  "beforeFp": ["设置", "显示", "v:深色主题", "pkg:com.android.settings"],
  "afterFp":  ["设置", "显示", "v:深色主题", "pkg:com.android.settings"],
  "anchor": {                      // 动作目标元素（复用时靠它定位）
    "text": "深色主题",
    "role": "switch",
    "viewId": "android:id/switch_widget",
    "bounds": [0.12, 0.31, 0.88, 0.35]   // 归一化 0..1
  },
  "verify": {                      // 这一步的判定结果
    "status": "ok",                // ok|unchanged|failed|timeout|skipped
    "via": "anyText",              // 判定依据：anyText|frameChanged|foregroundPkg|none
    "ms": 420,
    "retries": 0
  },
  "caller": "loop",                // loop|model|user|memory
  "protected": false               // 是否命中保护页（true 的步骤绝不进记忆）
}
```

### 2.3 结束行（可选，任务正常结束时写）

```jsonc
{ "type": "end", "ts": 1794371679000, "ok": true, "reason": "completed" }
```

## 3. 指纹（beforeFp / afterFp）

- 算法：`SceneMemory.fingerprint(dump, foregroundPkg)` —— 屏幕文本集合，
  勾选态加 `v:`/`o:` 前缀，前台包名加 `pkg:` 前缀。
- **必须含 `pkg:`**：不同 App 的同名页面不是同一场景（v1.2.139 的教训）。
- 空集合合法（页面读不到时），但 `afterFp` 为空且 `status=ok` 视为可疑，剪枝时降权。

## 4. 时间口径

- 全部用 `System.currentTimeMillis()` 的 epoch ms，**不用单调时钟**：轨迹要跨进程/跨设备比对。
- `verify.ms` 是「从动作发起到判定完成」的墙钟时长，用于性能对照，不参与逻辑。

## 5. 剪枝依赖的证据

剪枝器只允许用下面这些字段做判断，不得引入额外推断：

| 规则 | 依赖字段 |
| --- | --- |
| 失败尝试剔除 | `verify.status` + `anchor.text` 相同 |
| 无效动作剔除 | `verify.status=unchanged` + 前后有替代动作 |
| 重复滚动折叠 | `endpoint=scroll` + `args.direction` 相同 + `beforeFp/afterFp` 递进 |
| 绕路剔除 | `beforeFp == afterFp` 的 A→B→A 序列 |
| 观测动作剔除 | `endpoint in {noop, screen, dump, screenshot}` |
| 保护页步骤 | `protected=true` → 整条记忆不得入库 |

## 6. 兼容与演进

- 加字段：只加不删，读取方必须容忍未知字段。
- 改语义：`schema` 递增，旧轨迹保留原样、不迁移（只影响新录制）。
- 轨迹是**只追加**的：任何"修正"都写新事件行（`type=step` 带 `replaces`），不改旧行。
