# -*- coding: utf-8 -*-
"""AGENTS.md 种子（引擎 AI 的环境说明书）契约锁。

## 锁什么

这份提示词写在 `app/src/main/java/app/dsh/mobile/engine/AgentContextSeed.kt`，
由 App 盖到 `$DSH_HOME/AGENTS.md` 给引擎里的 AI 当环境事实读。它一旦写错，
AI 会按错的世界观行动（最典型：被告知没有 apt 却在 Linux 容器里；被告知
当前是 shizuku 模式但实际是 root）。

四条：

1. **不许硬编码运行态**：权限模式与已激活扩展必须来自 `Privilege.getMode()`
   与 `ExtensionManager.activeRoots()` 的插值，不能写字面量。历史上的 bug 正是
   `## Privilege mode: `shizuku`` 与 `- Active now: none yet.` 两处写死。
2. **Linux 容器模式必须被区分**：开了 Linux 模式时引擎跑在 musl/glibc 容器里，
   有 apt/apk；不能还告诉它「没有 apt」。
3. **入口不能教错**：`scr` 是独立命令，不是 `dev` 的子命令。
4. **版本号必须能推进**：`SEED_VERSION` 是升级覆盖的唯一开关。

## 为什么断言「不许出现字面量」而不是「有插值」

两者可以同时成立：模板里既写了 `${privModeTitle}`，也在别处留了一句硬编码的
旧描述。只有断言「某字面量不出现」才能真正防住回退。
"""

import io
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SRC = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/AgentContextSeed.kt")

checks = []


def check(name, cond):
    checks.append((name, bool(cond)))


src = io.open(SRC, encoding="utf-8").read()

# ── 1. 不许硬编码运行态 ─────────────────────────────────────────────
check("reads privilege mode from Privilege", "Privilege.getMode(ctx)" in src)
check(
    "privilege heading is interpolated",
    "## Privilege mode: `$privModeTitle`" in src,
)
check(
    "no hardcoded shizuku heading",
    "## Privilege mode: `shizuku`" not in src,
)
check("reads active extensions", "ExtensionManager.activeRoots(ctx)" in src)
check(
    "active-now line is interpolated",
    "- Active now: $active." in src,
)
check("no hardcoded empty active list", "- Active now: none yet." not in src)

# ── 2. Linux 容器模式必须被区分 ─────────────────────────────────────
check("checks linux mode", "EngineModeSelector.isLinuxEnabled(ctx)" in src)
check("has a linux-on branch", "Linux workspace mode is ON" in src)
check("has a linux-off branch", "Linux workspace mode is OFF" in src)
check(
    "linux-on branch mentions apt/apk availability",
    "apt-get" in src and "apk" in src,
)
check(
    "linux-on branch says libc is musl/glibc",
    "musl" in src,
)
check("linux section is actually interpolated", "${linuxMode}" in src)

# root 模式下不该让 AI 以为要 shz
check("root mode gets a shz note", 'privModeTitle == "root"' in src)
check("shz note is interpolated", "${shzNote}" in src)

# ── 3. 入口不能教错 ─────────────────────────────────────────────────
check(
    "warns that dev scr is wrong",
    "NOT `dev scr`" in src or "not `dev scr`" in src.lower(),
)
check(
    "documents scr as a standalone command",
    "`scr` is its own" in src,
)
# 14 项新能力必须出现
NEW_CAPS = [
    "dev net", "dev ble", "dev bt-hid", "dev hid", "dev pulse", "dev scene",
    "dev context", "dev nudge", "dev notify-digest", "dev screenwatch",
    "dev doze", "dev hand", "dev settings", "dev mcp",
]
# ⚠️ 必须用「词边界」而不是裸子串：把 `dev nudge` 改成 `dev nudge-OLD` 时，
# 裸 `in src` 仍然命中（子串还在），探针不会变红 —— 这是本仓库反复踩的
# 「断言锚定不够严」的老坑。要求后面紧跟空白/反引号/竖线/标点或行尾。
for cap in NEW_CAPS:
    pat = re.escape(cap) + r"(?=[\s`|.,)}\]]|$)"
    check("documents %s" % cap, re.search(pat, src) is not None)
check("documents wifi-radio", "dev wifi-radio" in src)

# scr key 的三个新动作
check("scr key documents lock", "lock|screenshot|power_dialog" in src)

# ── 4. 版本号与幂等机制 ─────────────────────────────────────────────
m = re.search(r"SEED_VERSION = (\d+)", src)
check("SEED_VERSION present", m is not None)
check("SEED_VERSION advanced to >= 51", m is not None and int(m.group(1)) >= 51)
check("version marker format used", 'MARKER_PREFIX$SEED_VERSION' in src)
check(
    "version gate actually consulted",
    "containsVersion(" in src,
)
check(
    "comment ties edits to the version bump",
    "递增" in src or "bump" in src.lower() or "changed" in src.lower(),
)

# ── 5. 用户段永不被覆盖（既有不变量，防回退） ───────────────────────
check("user section markers exist", "USER_SECTION_BEGIN" in src and "USER_SECTION_END" in src)
check(
    "upgrade keeps the user section",
    "extractUserSection(" in src and "userSection(" in src,
)

# ── 输出 ────────────────────────────────────────────────────────────
passed = sum(1 for _, ok in checks if ok)
failed = [n for n, ok in checks if not ok]
for n, ok in checks:
    print(("  PASS  " if ok else "  FAIL  ") + n)
print()
print("%d/%d checks passed" % (passed, len(checks)))
if failed:
    print("FAILED:", ", ".join(failed))
    sys.exit(1)
print("All agent-context seed checks passed.")
