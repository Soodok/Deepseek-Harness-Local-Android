#!/usr/bin/env python3
"""`stopping` 标志未复位的回归测试（v1.4.9，社区贡献 @henryz78）。

## 用户实测现象
「点重启后一直卡在等待状态」——引擎永远不启动，UI 一直转圈。

## 根因
`EngineSupervisor.stopping` 的 KDoc 写的是「任何 stop 都置位、start 清零」，
但 `start()` 里**没有清零**。于是：

  restart() → stop() 置 stopping=true → start() 拉起新监督循环
    → while (isActive && !stopping) 首轮即为假 → 一次迭代都不跑
    → 状态永久停在 restart() 设的 State.Starting

影响面远大于「重启卡住」：任何一次 stop()（服务被系统回收、通知栏退出、
权限模式切换）之后，**本进程内所有 start() 都再也跑不起来**。

## 第二层（同一贡献者发现）
仅复位 stopping 还不够：`loopJob?.cancel()` 是异步的，且
`catch (e: Exception)` 会吞掉 `CancellationException`。被取消的**旧**循环
可能在复位后继续执行收尾，用 `State.Backoff` 覆盖**新**循环刚设的
`State.Starting`。因此需要 `shouldExitLoop()` 额外检查协程活性。
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SUP = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt")

ok = 0
fail = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global ok, fail
    if cond:
        ok += 1
        print(f"  \u2713 {name}")
    else:
        fail += 1
        print(f"  \u2717 {name}" + (f"  -- {detail}" if detail else ""))


src = open(SUP, encoding="utf-8").read()

print("=== 1. stopping 在 start() 里复位 ===")
start_at = src.find("fun start(scope: CoroutineScope)")
check("找到 start()", start_at >= 0)
if start_at >= 0:
    nxt = src.find("\n    fun stop(", start_at)
    body = src[start_at : nxt if nxt > 0 else start_at + 1200]
    check("start() 里复位 userStop", "userStop = false" in body)
    check("start() 里复位 stopping",
          re.search(r"\bstopping = false\b", body) is not None,
          "缺少 stopping = false → 重启后监督循环一次都不跑")
    s_pos = body.find("stopping = false")
    l_pos = body.find("scope.launch")
    check("复位在 launch 之前", 0 <= s_pos < l_pos, f"stopping@{s_pos} launch@{l_pos}")

print("\n=== 2. shouldExitLoop 存在且检查协程活性 ===")
i = src.find("private suspend fun shouldExitLoop()")
check("定义了 shouldExitLoop", i >= 0)
if i >= 0:
    j = src.find("private suspend fun supervisionLoop()", i)
    expr = src[i : j if j > i else i + 400]
    check("包含 stopping", "stopping" in expr)
    check("包含协程活性检查",
          "currentCoroutineContext().isActive" in expr,
          "缺 isActive：被取消的旧循环会覆盖新循环状态")

print("\n=== 3. 所有退出点都用 shouldExitLoop ===")
raw = re.findall(r"if \(stopping\) break", src)
check("没有裸 `if (stopping) break`", not raw, f"还有 {len(raw)} 处未换")
uses = len(re.findall(r"if \(shouldExitLoop\(\)\) break", src))
check("退出点使用 shouldExitLoop", uses >= 3, f"只有 {uses} 处")

print("\n=== 4. stop() 仍然置位 ===")
stop_at = src.find("fun stop(graceMs: Long")
check("找到 stop()", stop_at >= 0)
if stop_at >= 0:
    tail = src[stop_at : stop_at + 1500]
    check("stop() 置 stopping = true",
          re.search(r"\bstopping = true\b", tail) is not None)

print("\n=== 5. while 条件仍然检查 stopping ===")
check("while 条件含 !stopping",
      re.search(r"while \([^)]*!stopping", src) is not None,
      "循环条件必须保留，否则 stop 无法让循环退出")

print("\n" + "-" * 44)
print(f"通过 {ok} / 失败 {fail}")
sys.exit(1 if fail else 0)
