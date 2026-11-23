#!/usr/bin/env python3
"""Boot hotspot regression guard.

This is a source-level contract test, not a timing test.

## Why it exists

The other startup probes guard *order* (`_test_startup_parallelization`) and
*attribution* (`_test_boot_trace`), but none of them can go red when someone
reintroduces a full-tree walk or a per-package directory listing on the cold
start path — the boot simply gets slower and every gate stays green.  That is
exactly how `log-sweep` (537ms) and `guardian-restore` (247ms) accumulated.

Each check below corresponds to one measured hotspot that was fixed.  Every
one can be made red by reverting a single concrete line.
"""
from __future__ import annotations

import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile")


def read(rel: str) -> str:
    with open(os.path.join(SRC, rel), encoding="utf-8") as f:
        return f.read()


ok = 0
fail = 0


def check(name: str, condition: bool, detail: str = "") -> None:
    global ok, fail
    if condition:
        ok += 1
        print(f"  \u2713 {name}")
    else:
        fail += 1
        print(f"  \u2717 {name}" + (f" -- {detail}" if detail else ""))


janitor = read("engine/LogJanitor.kt")
guardian = read("engine/ProfileGuardian.kt")
mode_sel = read("engine/EngineModeSelector.kt")
app = read("DshApp.kt")

print("=== 1. log-sweep must not walk dependency / cache trees ===")
# 实测：dsh-home 2053 条目里 .node-compile-cache-native 独占 1086 个（53%），
# 全是 Node 字节码缓存，绝不会有 .log。不剪时 log-sweep 稳定 420~510ms。
check("a noise-dir denylist exists", "NOISE_DIRS" in janitor)
for noise in ("node_modules", ".node-compile-cache-native", ".git"):
    check(f"denylist covers {noise}", f'"{noise}"' in janitor)
# 两个遍历器都必须剪：walkLogFiles 找 .log，findDirsNamed 找 .plugin-manager。
check(
    "walkLogFiles prunes before recursing",
    "f.isDirectory && f.name in NOISE_DIRS" in janitor,
    "the .log walker recurses into node_modules again",
)
check(
    "findDirsNamed prunes before recursing",
    "f.name !in NOISE_DIRS" in janitor,
    "the .plugin-manager finder recurses into node_modules again",
)

print("\n=== 2. guardian-restore must not list every builtin package ===")
# 实测 engine 内置 338 个 @deepseek-ai 包；原实现对每个包 listFiles 两次，
# 247ms，而其中被隔离过的通常一个都没有。
check(
    "existence is checked with a single stat",
    'File(pkg, "cordis.patch.yml").isFile' in guardian,
    "guardian lists directories just to discover the file is present",
)
marker = 'File(pkg, "cordis.patch.yml").isFile'
head = guardian[: guardian.find(marker)] if marker in guardian else guardian
check(
    "no directory listing precedes the stat",
    "listFiles()" not in head.split("dshAi.listFiles()")[-1],
    "a listFiles() call happens before the cheap existence check",
)

print("\n=== 3. mode detection must not wait for the boot queue ===")
# 用户原话：「启动 1.4 秒之后运行环境才检测出来」。检测本身 0~6ms，
# 慢的是它在 spawnEngine 里排到 1.2~2.4s 才轮到。
check("resolveEarly exists", "fun resolveEarly" in mode_sel)
check(
    "resolveEarly is called where it can run first",
    "resolveEarly" in app,
    "nothing calls resolveEarly from the Application, so the splash still says detecting",
)
check(
    "resolveEarly only short-circuits the cheap case",
    "if (isLinuxEnabled(ctx)) return false" in mode_sel,
    "resolveEarly must defer to the full readiness probe when the container is on",
)

print("\n" + "-" * 48)
print(f"通过 {ok} / 失败 {fail}")
raise SystemExit(1 if fail else 0)