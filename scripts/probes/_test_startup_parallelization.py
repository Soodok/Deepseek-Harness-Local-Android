#!/usr/bin/env python3
"""Startup dependency and bounded-parallelism regression guard.

This is a source-level contract test: it does not claim to replace device timing
or Android integration tests. It protects the ordering constraints that make the
three-way preflight fan-out safe.
"""
from __future__ import annotations

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SUP = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt")
MAIN = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/MainActivity.kt")

with open(SUP, encoding="utf-8") as f:
    src = f.read()
with open(MAIN, encoding="utf-8") as f:
    main = f.read()

ok = 0
fail = 0


def check(name: str, condition: bool, detail: str = "") -> None:
    global ok, fail
    if condition:
        ok += 1
        print(f"  ✓ {name}")
    else:
        fail += 1
        print(f"  ✗ {name}" + (f" -- {detail}" if detail else ""))


def pos(token: str) -> int:
    return src.find(token)


print("=== 1. Strict startup dependencies ===")
# ⚠️ 并行块的三条用 stepElapsed（各自计时），不是 step（共享墙钟差值）。
# 这里必须按新契约取锚点：早先的探针写的是 step(...)，改造后找不到锚点就
# 整段报红 —— 那种红是「探针过期」而不是「代码坏了」，会淹没真问题。
STEP_ORPHAN = 'stepElapsed("orphan-reap"'
STEP_OWNER = 'stepElapsed("home-owner-check"'
STEP_GUARDIAN = 'stepElapsed("guardian-restore"'
ordered = [
    STEP_ORPHAN,
    STEP_OWNER,
    STEP_GUARDIAN,
    'step("parallel-preflight")',
    'mark("runtime-install")',
    'mark("hardssh-install")',
    'mark("user-data")',
    'PluginRecovery.prepareForBoot(ctx)',
    'PluginKillSwitch.enforceBeforeBoot(ctx)',
    'ProfilePluginProvisioner.ensureAll(ctx)',
    'spawnEngine(bootId)',
]
positions = [pos(token) for token in ordered]
check("all dependency anchors exist", all(p >= 0 for p in positions), str(positions))
check("dependency order is preserved", all(a < b for a, b in zip(positions, positions[1:])), str(positions))
check("orphan reap precedes AgentBridge bind", pos(STEP_ORPHAN) < pos("AgentBridge.start(ctx)"))
check("ownership repair precedes home-file writes", pos(STEP_OWNER) < pos("AgentContextSeed.ensure(ctx)"))
check("runtime install starts only after preflight barrier", pos('step("parallel-preflight")') < pos("RuntimeInstaller(ctx).ensureInstalled"))

print("\n=== 1b. Parallel stages must not borrow a shared wall clock ===")
# 这一条是「归因正确性」的守卫，不是性能守卫。
#
# 背景（实测踩过）：这三步早先各调一次 step()，而 step() 的耗时是「距上一条
# step 的墙钟差值」。并行块里三条连着调，第一条吞掉整块时间、后两条记成 0~1ms，
# 于是 orphan-reap 被记成 4881ms 并当成「孤儿回收慢」去排查，方向完全错。
#
# 怎么打红：把任意一条改回 step("orphan-reap")，第二条断言立刻失败。
PARALLEL_KEYS = ["orphan-reap", "home-owner-check", "guardian-restore"]
for key in PARALLEL_KEYS:
    check(
        f"{key} reports its own elapsed time",
        f'stepElapsed("{key}"' in src and f'step("{key}")' not in src,
        "this stage shares a wall clock with its siblings; use BootTrace.stepElapsed",
    )

print("\n=== 2. Bounded independent fan-out ===")
start = src.find('mark("parallel-preflight")')
end = src.find('step("parallel-preflight")', start)
block = src[start:end] if start >= 0 and end > start else ""
check("parallel work has an explicit bounded task set", block.count("async {") == 4, f"async count={block.count('async {')}")
check("seed task is awaited", 'runParallelInit("agent-seed")' in block)
check("log sweep task is awaited", 'runParallelInit("log-sweep")' in block)
check("stale lock heal task is awaited", 'runParallelInit("stale-locks")' in block)
check("bridge task is awaited", 'runParallelInit("bridge")' in block)

# stale-locks 的安全前提：必须带「引擎还在跑就跳过」的探测参数。
# 少了它，退避重试期间上一轮引擎没死透时会删掉真活的锁 → 双写 package.json。
# 怎么打红：把 StaleLockHealer.heal(ctx) { portInUse(120) } 改成 StaleLockHealer.heal(ctx)。
check(
    "stale lock heal probes for a live engine",
    "StaleLockHealer.heal(ctx) { portInUse(" in block,
    "heal must skip when the engine is still running",
)
check(
    "stale lock heal runs before spawnEngine",
    pos('runParallelInit("stale-locks")') < pos("spawnEngine(bootId)"),
)
check("all tasks join at awaitAll", ").awaitAll()" in block)
check("task failures are collected before continuing", "initResults.firstOrNull { it.isFailure }?.getOrThrow()" in block)
check("no runtime mutation is inside the parallel block", "RuntimeInstaller" not in block and "HardsshInstaller" not in block)

print("\n=== 3. UI trace mapping ===")
# v1.6.19：阶段映射搬进 SplashController（Activity 不再持有 splash 渲染逻辑）
splash = io.open(
    os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/ui/SplashController.kt"),
    encoding="utf-8",
).read()
check(
    "parallel stage has a localized label mapping",
    '"parallel-preflight" -> R.string.boot_step_parallel_preflight' in splash,
)
check("stage label fallback remains", "else -> return key" in splash)

print("\n" + "-" * 48)
print(f"通过 {ok} / 失败 {fail}")
sys.exit(1 if fail else 0)
