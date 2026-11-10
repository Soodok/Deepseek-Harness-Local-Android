#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""_test_engine_spawn_env.py 的反向验证（证明那些断言真会变红）。

做法：逐处把实现改坏 → 重跑主探针 → 断言它**必须失败** → 立刻还原。
一轮下来全红才算断言有效；任何一处「改坏了还绿」都说明该断言是摆设。

纪律：
· 每次替换先断言命中数量非 0，命中 0 就报错退出（否则测的是空气）；
· 每轮结束都还原并复跑一次全绿，防止上一轮的改动污染下一轮。
"""
from __future__ import annotations

import io
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineProcess.kt")
PROBE = os.path.join(ROOT, "scripts/probes/_test_engine_spawn_env.py")
BACKUP = SRC + ".backwards.bak"

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


def run_probe() -> tuple[bool, str]:
    p = subprocess.run([sys.executable, PROBE], capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=180,
                       env={**os.environ, "PYTHONIOENCODING": "utf-8"})
    return p.returncode == 0, (p.stdout or "") + (p.stderr or "")


# (标题, 原文, 替换为, 期望变红的断言关键字)
MUTATIONS = [
    (
        "退回直接 execve node（去掉 sh 中转）",
        "cmd = SYSTEM_SH",
        "cmd = nodeBin.absolutePath",
        "SYSTEM_SH 作为 cmd",
    ),
    (
        "中转脚本里不 export LD_LIBRARY_PATH",
        '"export LD_LIBRARY_PATH=" + shellQuote(libPathVar) + "; " + inner,',
        "inner.toString(),",
        "export 该变量",
    ),
    (
        "su 分支漏掉 LD_LIBRARY_PATH（回到旧行为）",
        'if (!libPathVar.isNullOrEmpty()) append(" && export LD_LIBRARY_PATH=").append(shellQuote(libPathVar))',
        "",
        "su 分支 export 它",
    ),
    (
        "node 参数不转义（裸拼进脚本）",
        "args.forEach { inner.append(' ').append(shellQuote(it)) }\n                    cmd = SYSTEM_SH",
        "args.forEach { inner.append(' ').append(it) }\n                    cmd = SYSTEM_SH",
        "参数循环里调用 shellQuote",
    ),
    (
        "SYSTEM_SH 改成相对路径（execve 不搜 PATH）",
        'private const val SYSTEM_SH = "/system/bin/sh"',
        'private const val SYSTEM_SH = "sh"',
        "SYSTEM_SH 是绝对路径",
    ),
    (
        "注释丢掉 AT_SECURE 依据",
        "AT_SECURE",
        "某个安全标记",
        "注释点名 AT_SECURE",
    ),
    (
        "注释丢掉 RPATH 说明",
        "RPATH",
        "搜索路径",
        "注释点名 RPATH",
    ),
]

print("=== 0. 基线必须先绿 ===")
green, out = run_probe()
check("改坏之前主探针全绿", green, out[-300:])

if not green:
    print("\n基线就是红的 —— 反向验证无意义，先修主探针。")
    sys.exit(1)

shutil.copy2(SRC, BACKUP)
print(f"已备份到 {os.path.basename(BACKUP)}")

try:
    for i, (title, old, new, expect_kw) in enumerate(MUTATIONS, 1):
        print(f"\n=== {i}. 改坏：{title} ===")
        text = io.open(BACKUP, encoding="utf-8").read()
        hits = text.count(old)
        if hits == 0:
            check(f"[{title}] 替换命中", False, "原文没找到 —— 探针与实现已脱节，必须先更新反向脚本")
            continue
        # 必须替换**全部**出现：同一依据可能在两处注释里各写了一遍（su 分支 / 非 root
        # 分支 / 常量文档），只改第一处时其余几处仍能让断言保持绿 —— 第一次跑就这么假绿了。
        io.open(SRC, "w", encoding="utf-8", newline="\n").write(text.replace(old, new))

        green2, out2 = run_probe()
        check(f"[{title}] 主探针变红", not green2, "改坏了居然还是绿 —— 该断言是摆设")
        # 必须匹配**失败行**（✗ + 关键字）。只匹配关键字是无效断言：
        # 探针全绿时也会把 "✓ 注释点名 AT_SECURE" 打进输出，那种写法永远成立。
        red_lines = [ln.strip() for ln in out2.splitlines() if ln.strip().startswith("✗")]
        matched = [ln for ln in red_lines if expect_kw in ln]
        check(f"[{title}] 红在预期的那条断言上", bool(matched),
              f"失败行里没有含 {expect_kw!r} 的：{red_lines[:6]}")

        # 立刻还原，避免污染下一轮
        shutil.copy2(BACKUP, SRC)
        green3, out3 = run_probe()
        check(f"[{title}] 还原后复绿", green3, out3[-200:])
finally:
    shutil.copy2(BACKUP, SRC)
    os.remove(BACKUP)

print("\n=== 收尾 ===")
green4, out4 = run_probe()
check("终局全绿", green4, out4[-200:])

print("\n" + "-" * 44)
print(f"反向验证 {ok} / {fail} 失败；共 {len(MUTATIONS)} 处破坏")
sys.exit(1 if fail else 0)
