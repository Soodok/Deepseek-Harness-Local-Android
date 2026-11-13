#!/usr/bin/env python3
"""issue #104：TurnWatcher 不得把解压后的明文读进 Java 堆。

## 防的是什么

`ZstdTail.read()` 里 `val out = p.inputStream.readBytes()` 无上限，而
`TurnWatcher` 为了判断「有没有新内容，以及新增里几个 turn/end」传了
`keepFrames = 0` 全解。会话一大就必崩：

```
FATAL EXCEPTION: dsh-turn-watcher
java.lang.OutOfMemoryError: Failed to allocate a 268435472 byte allocation
    at java.io.ByteArrayOutputStream.grow(ByteArrayOutputStream.java:121)
    at kotlin.io.ByteStreamsKt.readBytes(IOStreams.kt:136)
    at app.dsh.mobile.engine.ZstdTail.read(ZstdTail.kt:146)
    at app.dsh.mobile.engine.TurnWatcher.readZstd(TurnWatcher.kt:208)
```

报告者实测：压缩后 38.38 MB / 21,788 帧 → 解压后 128.3 MB，
`ByteArrayOutputStream` 按 2 倍扩容去申请 256 MB，402 MB 堆里只剩 163 MB。

## 判据

它不是「加个上限」就能算修好 —— 上限会让大会话静默失去 turn/end 检测
（放弃增量 = 通知从此不响，比崩溃更难发现）。真正要守的是：**Java 侧
永远只接收统计结果，不接收明文**。只有这条同时保证「不 OOM」和「功能还在」。

所以断言锚在调用关系上：
- `TurnWatcher.readZstd` 必须调 `ZstdTail.stats`，且**不得**再调 `ZstdTail.read`；
- `ZstdTail.stats` 必须走 count 模式（`"count"` 作为子进程参数传给脚本）；
- count 模式下脚本必须输出 `{"bytes":..,"hits":..}`，不得 `Buffer.concat`；
- 增量语义必须是「累计差值」（`turnEnds - prevStats.turnEnds`），
  不能退回「拿明文字符串切片数行」（那还会跨行截断）。
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
ZSTD = os.path.join(ENGINE, "ZstdTail.kt")
WATCHER = os.path.join(ENGINE, "TurnWatcher.kt")

fails = []
def check(name, ok, hint=""):
    if not ok:
        fails.append(f"[{name}] {hint}")


def body_of(sig, text):
    m = re.search(sig, text)
    if not m:
        return ""
    i = text.find("{", m.end())
    if i < 0:
        return ""
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i + 1:j]
    return ""


zstd = open(ZSTD, encoding="utf-8").read()
watch = open(WATCHER, encoding="utf-8").read()

stats_body = body_of(r"fun stats\s*\(", zstd)
check("A1", stats_body != "", "ZstdTail.stats 不存在 —— 明文还是唯一出口")

# ---- 核心：count 模式确实存在且被 stats 采用 ----
if stats_body:
    check("A2", '"count"' in stats_body or '"count"' in zstd,
          "脚本没有 count 模式")
    check("A3", "count" in stats_body,
          "stats() 没走 count 模式 —— 那它拿到的还是明文")
    check("A4", "readBytes" in stats_body,
          "stats() 完全没读子进程输出 —— 那统计值从哪来？")

# 脚本本体：count 分支必须只输出 JSON，不能 concat 明文
script_m = re.search(r'private const val SCRIPT = """(.*?)"""', zstd, re.S)
check("B1", script_m is not None, "脚本常量没找到")
if script_m:
    js = script_m.group(1)
    cnt_i = js.find('mode === "count"')
    check("B2", cnt_i >= 0, "脚本里没有 count 分支")
    if cnt_i >= 0:
        # count 分支的边界必须按它自己的收尾锚定，不能用固定长度窗口 ——
        # 2200 字符会越界到 text 模式的 Buffer.concat，把正确的实现判成缺陷（实测踩过）。
        end_marker = "process.exit(0);"
        end_i = js.find(end_marker, cnt_i)
        branch = js[cnt_i:end_i + len(end_marker)] if end_i > 0 else js[cnt_i:]
        check("B3", "JSON.stringify" in branch,
              "count 分支没输出 JSON —— 调用方解析不到 bytes/hits")
        check("B4", "Buffer.concat" not in branch,
              "count 分支仍在拼接明文")

# ---- 消费端：TurnWatcher 必须只用 stats ----
readzstd = body_of(r"private fun readZstd\s*\(", watch)
check("C1", readzstd != "", "TurnWatcher.readZstd 找不到")
if readzstd:
    check("C2", "ZstdTail.stats" in readzstd,
          "readZstd 没调 ZstdTail.stats —— 又回到明文路径了")
    check("C3", "ZstdTail.read" not in readzstd,
          "readZstd 仍在调 ZstdTail.read（传 keepFrames=0 就是又要把明文读进堆）")
    # v1.6.0 起改成**按帧增量**：脚本只解 [上次帧数, 现在帧数)，回传的 turnEnds
    # 已经是本次新增帧里的个数，所以不再需要「累计值相减」。锚点随之改成
    # 「必须按 fromFrames 传增量基准」+「必须直接用 stats.turnEnds」。
    check("C4", "fromFrames" in readzstd and "stats.turnEnds" in readzstd,
          "没把帧序号当增量基准（fromFrames）或没直接用增量 hits")
    # 不得再出现凭明文长度切片
    check("C5", "substring(" not in readzstd,
          "readZstd 里还有 substring —— 说明仍在切明文")
    check("C6", "text.length" not in readzstd,
          "readZstd 还在用 text.length —— 明文仍在手上了")

# ---- 状态容器也必须是统计结构 ----
check("D1", "HashMap<String, ZstdTail.Stats>" in watch,
      "zstdSeen 仍存 Int（解压后字符数）—— 那个数只能从明文拿")

# ---- 反向自检：证明判据能区分「旧实现」 ----
old_watcher = '''
val text = ZstdTail.read(ctx, f, keepFrames = 0).first ?: return
val prevLen = synchronized(zstdSeen) { zstdSeen[key] }
synchronized(zstdSeen) { zstdSeen[key] = text.length }
if (text.length <= prevLen) return
val delta = text.substring(prevLen.coerceAtMost(text.length))
'''
check("E1", ("ZstdTail.read" in old_watcher) and ("text.length" in old_watcher),
      "反向自检失效：旧写法没被识别为含 read/length")
check("E2", "ZstdTail.stats" not in old_watcher,
      "反向自检失效：旧写法被判成用了 stats")

print("turnwatcher oom checks done")
if fails:
    print("FAILED (%d):" % len(fails))
    for f in fails:
        print("  " + f)
    sys.exit(1)
print("OK")
