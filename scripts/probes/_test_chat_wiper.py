# -*- coding: utf-8 -*-
"""对话删除功能契约锁（v1.6.17）。

## 锁的三件事

1. **启动路径零引用**（用户硬约束：「不要启动时读取，一定不要让任何事情影响
   启动速度」）。这条最容易在后续重构里被破坏 —— 比如有人为了在维护页显示
   「共 N 个对话」而把 `ChatWiper.list()` 塞进 `MainActivity.onCreate`。
   所以这里逐个检查启动相关文件里**根本不出现** `ChatWiper` / `WipeChats`。

2. **删除顺序**：停引擎 → 删文件 → 清注册表 → 重启引擎。顺序错了会留下幽灵
   会话（引擎内存状态把注册表写回来）。断言的是 `stopForMaintenance` 出现在
   `ChatWiper.delete` 之前，`restart` 出现在之后。

3. **安全兜底**：删除是「移动进回收站」而不是 unlink，且注册表写回前必备份。

## 为什么用「不出现」而不是「不调用」

`import app.dsh.mobile.engine.ChatWiper` 本身不耗时，但它把两个东西绑在一起了 ——
一旦 import 存在，后续任何人加一行调用都不会再被 review 注意到。
断言「整个文件不出现符号」是最强的静态约束。
"""

import io
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAVA = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile")

WIPER = os.path.join(JAVA, "engine/ChatWiper.kt")
ACT = os.path.join(JAVA, "WipeChatsActivity.kt")
LAYOUT_MAINT = os.path.join(ROOT, "app/src/main/res/layout/activity_maintenance.xml")
LAYOUT_WIPE = os.path.join(ROOT, "app/src/main/res/layout/activity_wipe_chats.xml")
MANIFEST = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")
MAINT = os.path.join(JAVA, "MaintenanceActivity.kt")

checks = []


def check(name, cond):
    checks.append((name, bool(cond)))


def read(p):
    return io.open(p, encoding="utf-8").read()


def code_only(text):
    """剥掉注释，避免注释里的字面量骗过断言（本项目反复踩的坑）。"""
    out = []
    for line in text.splitlines():
        t = line.strip()
        if t.startswith("//") or t.startswith("*") or t.startswith("/*"):
            continue
        if "//" in line:
            line = line.split("//", 1)[0]
        out.append(line)
    return chr(10).join(out)


# ── 0. 文件存在 ─────────────────────────────────────────────────────
check("ChatWiper.kt exists", os.path.isfile(WIPER))
check("WipeChatsActivity.kt exists", os.path.isfile(ACT))
check("layout activity_wipe_chats.xml exists", os.path.isfile(LAYOUT_WIPE))

wiper = read(WIPER)
act = read(ACT)

# ── 1. 启动路径零引用（硬约束） ──────────────────────────────────────
#
# 这些文件在冷启动链路上：DshApp.onCreate → EngineSupervisor.start →
# MainActivity.onCreate。任何一个引用 ChatWiper 都可能拖慢启动。
STARTUP_FILES = [
    "DshApp.kt",
    "MainActivity.kt",
    "engine/EngineSupervisor.kt",
    "engine/EngineConfig.kt",
    "engine/BootTrace.kt",
]
for rel in STARTUP_FILES:
    p = os.path.join(JAVA, rel)
    if not os.path.isfile(p):
        continue
    src = code_only(read(p))
    check("startup %s does not reference ChatWiper" % rel, "ChatWiper" not in src)
    check("startup %s does not reference WipeChats" % rel, "WipeChats" not in src)

# ChatWiper 自身不能有 init 块 / object 初始化工作 / 静态扫描
wc = code_only(wiper)
check("ChatWiper has no init block", "init {" not in wc)
check(
    "ChatWiper does no eager work in object body",
    not re.search(r"^object ChatWiper \{[\s\S]{0,400}?=\s*(list|scan|File)\(", wc, re.M),
)
check("ChatWiper.list is not a val/field", "val allItems" not in wc)

# ── 2. 删除顺序 ─────────────────────────────────────────────────────
i_stop = act.find("stopForMaintenance")
i_del = act.find("ChatWiper.delete")
i_restart = act.find("restart()")
check("activity stops engine before deleting", 0 < i_stop < i_del)
check("activity restarts engine after deleting", i_del > 0 and i_restart > i_del)
check(
    "activity re-allows start in finally-like path",
    "allowStartAfterMaintenance" in act,
)
check(
    "activity scan runs off the UI thread",
    "Thread {" in act and "name = \"wipe-chats-scan\"" in act,
)
check(
    "activity delete runs off the UI thread",
    'name = "wipe-chats-run"' in act,
)

# ── 3. 安全兜底 ─────────────────────────────────────────────────────
check("wiper moves to trash instead of unlink", "renameTo(dest)" in wc)
check("wiper has trash dir prefix", '".trash-' in wc)
check("wiper backs up registry before write", "before-wipe" in wc)
check("wiper writes registry atomically", "wipe-tmp" in wc and "renameTo(file)" in wc)
check("wiper prunes archived ids", "archivedSessionIds" in wc)
check("wiper prunes pinned ids", "pinnedSessionIds" in wc)
check("wiper keeps workspaces", 'ws.put("sessionIds", next)' in wc)
check("wiper purges expired trash", "purgeTrash" in wc and "TRASH_KEEP_DAYS" in wc)
# 回收站建在 sessions/ 下、布局与正常会话相同 —— list() 不排除它就会让刚删掉的
# 对话重新出现在列表里（离线复刻脚本抓到的真 bug）。
check("wiper defines a single trash prefix", "TRASH_PREFIX" in wc)
check(
    "list skips trash dirs",
    "if (top.name.startsWith(TRASH_PREFIX)) return@forEach" in wc,
)
check(
    "trash prefix used in every traversal",
    wc.count("TRASH_PREFIX") >= 5,
)
check(
    "wiper reads projection cache, not zstd",
    "session_projcache" in wc and "ZstdTail" not in wc,
)

# ── 4. UI 接线 ──────────────────────────────────────────────────────
maint_layout = read(LAYOUT_MAINT)
maint = read(MAINT)
check("maintenance layout has rowWipeChats", 'android:id="@+id/rowWipeChats"' in maint_layout)
check("maintenance activity wires rowWipeChats", "rowWipeChats" in maint)
check("maintenance launches WipeChatsActivity", "WipeChatsActivity::class.java" in maint)
wipe_layout = read(LAYOUT_WIPE)
check("wipe layout has list container", 'android:id="@+id/listChats"' in wipe_layout)
check("wipe layout has clear-all button", 'android:id="@+id/btnClearAll"' in wipe_layout)
check("wipe layout has select-all", 'android:id="@+id/btnSelectAll"' in wipe_layout)
check("activity has both delete entry points", "confirmDeleteSelected" in act and "confirmClearAll" in act)
check("clear-all confirms before wiping", "wipe_chats_confirm_clear" in act)
check("single delete confirms before wiping", "wipe_chats_confirm_delete" in act)

# 确认文案里的换行必须是 &#10; 而不是 &amp;#10;
# （后者在设备上会原样显示成 "&#10;&#10;"，模拟器截图已证实）
for loc in ["values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"]:
    p_loc = os.path.join(ROOT, "app/src/main/res/%s/strings.xml" % loc)
    if not os.path.isfile(p_loc):
        continue
    txt = read(p_loc)
    check("%s has no double-escaped newline" % loc, "&amp;#10;" not in txt)

man = read(MANIFEST)
check("manifest declares WipeChatsActivity", "WipeChatsActivity" in man)
check(
    "WipeChatsActivity is not exported",
    re.search(r'WipeChatsActivity"[\s\S]{0,120}?android:exported="false"', man) is not None,
)

# MaintenanceActivity 只跳转，不做扫描
maint_code = code_only(maint)
check("maintenance activity does not call ChatWiper", "ChatWiper" not in maint_code)

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
print("All chat-wiper checks passed.")
