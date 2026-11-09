#!/usr/bin/env python3
"""原生模式 Node 编译缓存接线探针。

守住四件事，它们都不会自己报错：

A. `buildEnv()` 必须注入 `NODE_COMPILE_CACHE`
   缺了这条，原生模式（默认模式）就完全拿不到编译缓存，冷启白慢约 1 秒，
   而且**没有任何症状** —— 引擎照常启动，只是慢。

B. 原生与 Linux 的缓存根目录必须**不同**
   `LinuxEnvironmentManager.pruneCompileCache()` 会删掉缓存根下所有「不属于当前
   node 版本」的子目录，而两个模式跑的是两个 node（bionic v24.x / musl v22.x）。
   共用根目录 → 每切一次模式对方就把自己那份删光，来回切等于永久无缓存。
   这条最隐蔽：单次启动看起来都正常，只有切模式才吃亏。

C. 原生 spawn 必须注入 flusher（`--require <cjs>`）
   `NODE_COMPILE_CACHE` 懒写盘，只有优雅退出才落盘；App 停引擎用 kill -9，
   不注入刷盘器则缓存一次都写不进去 —— 变量设了，收益为零。

D. flusher 必须排在入口 JS **之前**
   `--require` 是 node 自己的 flag，跟在 `web` 之后会被 web app 的解析器当成
   未知选项（实测 0.2.1 报 unknown option）。而 `extraFlags` 在
   `add(entryJs.absolutePath)` 之前插入，这条断言守的就是这个顺序。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENGINE = ROOT / "app/src/main/java/app/dsh/mobile/engine"
CONFIG = ENGINE / "EngineConfig.kt"
SUPERVISOR = ENGINE / "EngineSupervisor.kt"
LINUX_LAUNCHER = ENGINE / "LinuxEngineLauncher.kt"
LINUX_ENV = ENGINE / "LinuxEnvironmentManager.kt"
PROCESS = ENGINE / "EngineProcess.kt"

checks = 0
fails: list[str] = []


def ok(cond: bool, label: str) -> None:
    global checks
    checks += 1
    if not cond:
        fails.append(label)


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace")


def fn_body(src: str, signature: str, max_len: int = 4000) -> str:
    """取一个 Kotlin 函数的函数体（按缩进配对，限定最大长度防跑飞）。

    为什么不用正则 `\\{...\\}`：Kotlin 字符串里也有花括号，非贪婪会提前截断，
    贪婪则一路吃到文件尾。这里按行数数括号深度，遇到深度回 0 就停。
    """
    i = src.find(signature)
    if i < 0:
        return ""
    j = src.find("{", i)
    if j < 0:
        return ""
    depth = 0
    end = min(len(src), j + max_len)
    k = j
    while k < end:
        c = src[k]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return src[j : k + 1]
        k += 1
    return src[j:end]


cfg = read(CONFIG)
sup = read(SUPERVISOR)
linux = read(LINUX_LAUNCHER)
lenv = read(LINUX_ENV)
proc = read(PROCESS)

# ---------- A. buildEnv 注入 NODE_COMPILE_CACHE ----------
build_env = fn_body(cfg, "fun buildEnv(")
ok(len(build_env) > 800, "A1 buildEnv 函数体可定位（%d 字符）" % len(build_env))
ok(
    "NODE_COMPILE_CACHE=" in build_env,
    "A2 buildEnv 注入 NODE_COMPILE_CACHE",
)
ok(
    "nativeCompileCacheDir(ctx)" in build_env,
    "A3 缓存目录走 nativeCompileCacheDir(ctx) 而非字面量",
)

# ---------- B. 两模式缓存根不同 ----------
ok(
    "fun nativeCompileCacheDir(" in cfg,
    "B1 EngineConfig 定义 nativeCompileCacheDir",
)
m = re.search(
    r"fun nativeCompileCacheDir\([^)]*\)[^=]*=\s*\n?\s*File\(\s*dshHome\(ctx\)\s*,\s*\"([^\"]+)\"",
    cfg,
    re.S,
)
ok(m is not None, "B2 nativeCompileCacheDir 用 File(dshHome(ctx), \"...\")")
nat_name = m.group(1) if m else ""

# Linux 侧用的目录名：从 prune/seed 的实现里取，不猜
ln = re.search(r'val root = File\(EngineConfig\.dshHome\(ctx\),\s*"([^"]+)"\)', lenv)
linux_name = ln.group(1) if ln else ""
ok(linux_name != "", "B3 取到 Linux 侧缓存目录名（%s）" % (linux_name or "<无>"))
ok(nat_name != "", "B4 取到原生侧缓存目录名（%s）" % (nat_name or "<无>"))
ok(
    nat_name != "" and linux_name != "" and nat_name != linux_name,
    "B5 两模式缓存根不同名（原生=%s / Linux=%s）" % (nat_name, linux_name),
)
ok(
    "不同" in cfg or "分开" in cfg or "prune" in cfg,
    "B6 源码注释说明了为何必须分开（防后人合并回去）",
)

# ---------- C. 原生 spawn 注入 flusher ----------
# 原生 spawn = 文件里最后一个 EngineProcess.spawn( 调用。
# ⚠️ flusher 变量声明在 `return EngineProcess.spawn(` 之前（要先拿到可能为 null
# 的路径再决定传什么），所以截取范围必须**往前多含一段**，否则只能看到
# `extraFlags = nativeFlusher...` 而看不到 `deployCompileCacheFlusher` 的调用。
idx = sup.rfind("EngineProcess.spawn(")
native_spawn = sup[max(0, idx - 900) :] if idx >= 0 else ""
ok(len(native_spawn) > 100, "C1 定位到原生 spawn 段（含前置 flusher 声明）")
ok(
    "extraFlags" in native_spawn,
    "C2 原生 spawn 传 extraFlags",
)
ok(
    "deployCompileCacheFlusher" in native_spawn,
    "C3 原生 spawn 段调用 deployCompileCacheFlusher",
)
ok(
    '"--require"' in native_spawn or "'--require'" in native_spawn,
    "C4 注入 --require",
)
ok(
    re.search(r"runCatching\s*\{[^}]*deployCompileCacheFlusher", native_spawn) is not None,
    "C5 deployCompileCacheFlusher 被 runCatching 包裹（部署失败不挡启动）",
)
ok(
    "getOrNull()" in native_spawn,
    "C6 flusher 为 null 时降级为无缓存",
)
# ★实测踩到的真 bug：deployCompileCacheFlusher 曾无条件返回容器内路径 "/eng/..."，
# 而原生模式没有 /eng 挂载点 → node --require 预加载失败 →
#   Error: Cannot find module '/eng/node-compile-cache-flusher.cjs'
# 引擎完全起不来，App 只显示 "Engine failed to start / Cause not identified"。
# 这条断言守的就是「原生分支必须显式要宿主路径」。
ok(
    re.search(
        r"deployCompileCacheFlusher\(\s*ctx\s*,\s*forContainer\s*=\s*false\s*\)",
        native_spawn,
    )
    is not None,
    "C7 原生 spawn 调用处显式传 forContainer = false（不是只写在注释里）",
)
ok(
    '"/eng/' not in native_spawn,
    "C8 原生 spawn 段不出现 /eng/ 容器路径",
)

# ---------- D. flusher 排位：--require 必须在入口 JS 之前 ----------
spawn_body = fn_body(proc, "fun spawn(")
ok(len(spawn_body) > 1000, "D1 spawn 函数体可定位（%d 字符）" % len(spawn_body))
i_extra = spawn_body.find("addAll(extraFlags)")
i_entry = spawn_body.find("add(entryJs.absolutePath)")
ok(i_extra > 0, "D2 addAll(extraFlags) 存在")
ok(i_entry > 0, "D3 add(entryJs.absolutePath) 存在")
ok(
    i_extra > 0 and i_entry > 0 and i_extra < i_entry,
    "D4 extraFlags 排在入口 JS 之前（%d < %d）" % (i_extra, i_entry),
)

# ---------- E. Linux 侧没被这次改动破坏 ----------
ok(
    "NODE_COMPILE_CACHE=/dsh/" in linux,
    "E1 Linux 侧仍注入自己的 NODE_COMPILE_CACHE",
)
ok(
    "pruneCompileCache" in lenv and "seedCompileCache" in lenv,
    "E2 Linux 侧 prune/seed 仍在",
)
# 原生侧的 flusher 不应该被塞进 Linux 分支（那边有自己的）
lin_start = linux.find("fun buildEngineArgv(")
ok(lin_start >= 0, "E3 LinuxEngineLauncher.buildEngineArgv 可定位")

# ---------- F. 配置文件里不能残留调试埋点 ----------
ok(
    "println(" not in cfg and "println(" not in sup,
    "F1 无 println 调试残留",
)
ok(
    "NODE_COMPILE_CACHE" in cfg,
    "F2 EngineConfig 内有 NODE_COMPILE_CACHE 字样（注释或代码）",
)

print()
if fails:
    print("FAILED (%d/%d)" % (len(fails), checks))
    for f in fails:
        print("  x", f)
    sys.exit(1)
print("OK: %d checks passed" % checks)
