#!/usr/bin/env python3
"""issue #31 导入功能回归测试：安全性（zip-slip / 白名单）与合并语义。

DataExport.import 是**会写用户数据**的路径，安全断言必须自动化。这里用 Python
复刻 Kotlin 的判定逻辑做语义验证 + 静态断言源码实现，两层都查：

  1. 源码层：确认 resolveInside / 白名单 / 上限 / 合并逻辑都在；
  2. 语义层：用同规则的 Python 实现跑一组恶意与正常条目，断言结论一致。

运行: python scripts/probes/_test_data_import.py
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "DataExport.kt")

FAILS = []


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (("  -> " + detail) if not ok and detail else ""))
    if not ok:
        FAILS.append(name)


src = io.open(SRC, encoding="utf-8").read()

print("=== 1. 实现存在性 ===")
check("有 import 方法", "fun import(" in src)
check("有 ImportSummary", "data class ImportSummary" in src)
check("有 ImportException", "class ImportException" in src)
check("有 resolveInside（zip-slip 防护）", "private fun resolveInside(" in src)
check("有注册表合并", "private fun mergeRegistry(" in src)
check("入口数上限", "MAX_ENTRIES" in src)
check("单条目上限", "MAX_ENTRY_BYTES" in src)
check("总量上限", "MAX_TOTAL_BYTES" in src)

print("\n=== 2. 安全判定 ===")
check("拒绝 .. 穿越", 'it == ".."' in src)
check("拒绝 Windows 盘符", 'Regex("^[A-Za-z]:")' in src)
check("拒绝 NUL 字节", "\\u0000" in src)
check("canonicalPath 前缀校验", "startsWith(home.canonicalPath" in src)
check("路径白名单（sessions/workspaces）",
      '"sessions/"' in src and '"workspaces/"' in src)
check("非白名单条目被跳过（profiles/engine 不写入）", "allowedRoots.none" in src)

print("\n=== 3. 合并语义（不覆盖现有数据） ===")
check("目标已存在则跳过并计数", "if (dest.exists()) { overwritten++; continue }" in src)
check("注册表按并集合并（追加新 id）", "if (it !in list) list.add(it)" in src)
check("注册表遇到内容相同不覆盖局部条目", "if (!local.has(k)) local.put(k, inc.get(k))" in src)
check("先全量校验后写入（先校验注释 + 两段循环）",
      "全量校验" in src and "写入（合并式" in src)

print("\n=== 4. 语义层：复刻 resolveInside 跑恶意条目 ===")


def resolve_inside(home: str, name: str):
    """复刻 Kotlin resolveInside 的判定（POSIX 语义）。"""
    if name.startswith("/") or name.startswith("\\"):
        return None
    n = name.lstrip("/")
    if not n or "\x00" in n:
        return None
    if re.match(r"^[A-Za-z]:", n):
        return None
    if any(p == ".." for p in n.split("/")):
        return None
    dest = os.path.normpath(os.path.join(home, n))
    home_c = os.path.normpath(home)
    if not dest.startswith(home_c + os.sep):
        return None
    return dest


HOME = "/data/user/0/app/files/dsh-home"
CASES = [
    # (条目名, 期望是否接受)
    ("sessions/abc/session.jsonl", True),
    ("workspaces/proj/a.txt", True),
    ("storages/workspace.json", True),
    ("../escape.txt", False),
    ("sessions/../../etc/passwd", False),
    ("/etc/passwd", False),
    ("C:/Windows/system32/x.dll", False),
    ("sessions/a\x00b", False),
    ("sessions//../../x", False),
    ("sessions/ok/../../../x", False),
]
for name, expect in CASES:
    got = resolve_inside(HOME, name) is not None
    check(f"resolveInside({name!r}) -> {expect}", got == expect,
          f"实际 {got}")

print("\n=== 5. 白名单过滤语义 ===")
ALLOWED = [os.path.normpath(os.path.join(HOME, d)) for d in ("sessions/", "workspaces/")]
REG = os.path.normpath(os.path.join(HOME, "storages/workspace.json"))


def allowed(path):
    if path is None:
        return False
    if path == REG:
        return True
    return any(path.startswith(a + os.sep) for a in ALLOWED)


for name, expect in [
    ("sessions/x.jsonl", True),
    ("workspaces/w/f.txt", True),
    ("storages/workspace.json", True),
    ("profiles/web/package.json", False),   # 凭据！必须拒绝
    ("engine/bin/node", False),             # 运行时！必须拒绝
    ("dsh-home/../../x", False),
]:
    p = resolve_inside(HOME, name)
    check(f"白名单 {name!r} -> {expect}", allowed(p) == expect)

print()
if FAILS:
    print(f"FAILED ({len(FAILS)}): " + ", ".join(FAILS))
    sys.exit(1)
print("All data-import checks passed.")
