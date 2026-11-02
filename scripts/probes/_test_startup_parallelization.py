#!/usr/bin/env python3
"""Startup dependency and bounded-parallelism regression guard.

This is a source-level contract test: it does not claim to replace device timing
or Android integration tests. It protects the ordering constraints that make the
three-way preflight fan-out safe.
"""
from __future__ import annotations

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
ordered = [
    'step("orphan-reap")',
    'step("home-owner-check")',
    'step("guardian-restore")',
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
check("orphan reap precedes AgentBridge bind", pos('step("orphan-reap")') < pos("AgentBridge.start(ctx)"))
check("ownership repair precedes home-file writes", pos('step("home-owner-check")') < pos("AgentContextSeed.ensure(ctx)"))
check("runtime install starts only after preflight barrier", pos('step("parallel-preflight")') < pos("RuntimeInstaller(ctx).ensureInstalled"))

print("\n=== 2. Bounded independent fan-out ===")
start = src.find('mark("parallel-preflight")')
end = src.find('step("parallel-preflight")', start)
block = src[start:end] if start >= 0 and end > start else ""
check("parallel work has an explicit bounded task set", block.count("async {") == 3, f"async count={block.count('async {')}")
check("seed task is awaited", 'runParallelInit("agent-seed")' in block)
check("log sweep task is awaited", 'runParallelInit("log-sweep")' in block)
check("bridge task is awaited", 'runParallelInit("bridge")' in block)
check("all tasks join at awaitAll", ").awaitAll()" in block)
check("task failures are collected before continuing", "initResults.firstOrNull { it.isFailure }?.getOrThrow()" in block)
check("no runtime mutation is inside the parallel block", "RuntimeInstaller" not in block and "HardsshInstaller" not in block)

print("\n=== 3. UI trace mapping ===")
check("parallel stage has a localized label mapping", '"parallel-preflight" -> getString(R.string.boot_step_parallel_preflight)' in main)
check("stage label fallback remains", "else -> key" in main)

print("\n" + "-" * 48)
print(f"通过 {ok} / 失败 {fail}")
sys.exit(1 if fail else 0)
