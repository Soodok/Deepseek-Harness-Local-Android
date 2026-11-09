#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""引擎启动环境的回归测试（v1.6.3）。

## 事故（模拟器实测，2026-11-09）

App 在 NORMAL 模式下起引擎，engine.log 里只有一行、没有崩溃栈：

    CANNOT LINK EXECUTABLE ".../engine/bin/node": library "libz.so.1" not found

三层误判之后才定到真根因：

1. 不是「runtime 没装好」—— `files/engine` 631MB 齐全，`lib/libz.so.1` 就在那里。
2. 不是「node 二进制坏了」—— 同一台机器上
   `env -i LD_LIBRARY_PATH=$E/lib $E/bin/node --version` → `v24.22.2`，
   app uid 与 root 都一样成功。
3. 真差别在 **execve 路径**：Android 给 App 进程标记 AT_SECURE=1，bionic 的
   链接器会**剥掉 LD_LIBRARY_PATH**。凡经 `/system/bin/sh` 中转（sh 不是 setuid
   程序 → AT_SECURE=0）就生效，直连 execve 就丢。

真机 arm64 一直没暴露，是因为那份 runtime 的 node 自带 RPATH；换成没有 RPATH 的
构建（x86_64）立刻现形。所以这不是「为模拟器特判」，而是「启动不该依赖 runtime
是否编了 RPATH」。

同一处还有第二个缺口：su 分支会重置环境，旧代码只补 export 了
HOME/PATH/DSH_HOME/... 但**漏了 LD_LIBRARY_PATH**。arm64 靠 RPATH 掩盖了它。

## 本测试验证

A. 静态契约：中转常量、非 root 分支的包裹逻辑、su 分支的 export、参数都被转义
B. 行为验证：把同一套引号规则用真实 POSIX sh 跑一遍，断言
   · LD_LIBRARY_PATH 在 exec 之后可见
   · argv 逐项原样往返（含空格、单引号）
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineProcess.kt")

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


raw = open(SRC, encoding="utf-8").read()

# 只保留可执行代码（行首 # 或 // 去掉）。禁/必需类断言必须基于它 ——
# 否则注释里的说明文字会把断言骗过去（本项目已踩过两次）。
code = "\n".join(
    ln for ln in raw.split("\n") if not ln.lstrip().startswith(("//", "*", "/*"))
)


def seg(text: str, start_marker: str, end_marker: str) -> str:
    i = text.index(start_marker)
    j = text.index(end_marker, i)
    return text[i:j]


print("=== A1. 中转解释器常量 ===")
check("SYSTEM_SH 常量存在", "private const val SYSTEM_SH" in code)
m = re.search(r'private const val SYSTEM_SH\s*=\s*"([^"]+)"', code)
check("SYSTEM_SH 是绝对路径", bool(m) and m.group(1).startswith("/"),
      m.group(1) if m else "未找到")
check("SYSTEM_SH 指向 /system/bin/sh", bool(m) and m.group(1) == "/system/bin/sh",
      m.group(1) if m else "")

print("\n=== A2. 非 root 分支：经 sh 中转 ===")
# spawn() 里 su 分支结束到 nativeForkPty 之间就是非 root 分支
native = seg(raw, "} else {", "val fd = Pty.nativeForkPty(")
check("存在 else 分支（不是直接 execve node）", len(native) > 200, f"{len(native)} 字符")
check("非 root 分支读 LD_LIBRARY_PATH", "LD_LIBRARY_PATH=" in native)
check("命中后用 SYSTEM_SH 作为 cmd", "cmd = SYSTEM_SH" in native)
check("用 -c 传脚本", '"-c"' in native)
check("脚本里 export 该变量", "export LD_LIBRARY_PATH=" in native)
check("脚本里 exec node（不是再 fork）", "exec " in native)
check("export 与 exec 在同一条脚本里", "; " in native)
check("只在该变量非空时才中转（空值退回直连）",
      "if (!libPathVar.isNullOrEmpty())" in native)

print("\n=== A3. node 参数必须逐个转义（防注入 / 路径含空格）===")
check("参数循环里调用 shellQuote",
      re.search(r"args\.forEach\s*\{[^}]*shellQuote\(it\)", native) is not None)
check("node 路径本身也转义",
      re.search(r'"exec "\)\.append\(shellQuote\(nodeBin\.absolutePath\)\)', native) is not None)
check("转义后的路径被包在 exec 脚本里", "exec " in native and "shellQuote(nodeBin.absolutePath)" in native)

print("\n=== A4. su 分支也补上了 LD_LIBRARY_PATH ===")
su = seg(raw, "if (suPath != null) {", "} else {")
check("su 分支读 LD_LIBRARY_PATH", "libPathVar" in su)
check("su 分支 export 它", "export LD_LIBRARY_PATH=" in su)
# 与 HOME/PATH 同一处 buildString，说明是「同一个重置问题」一起补的
check("与 HOME/PATH 的 export 在同一段", su.count("export ") >= 4, f"export 次数 {su.count('export ')}")

print("\n=== A5. 依据写进注释（防将来被当垃圾注释删掉）===")
check("注释点名 AT_SECURE", "AT_SECURE" in raw)
check("注释点名 RPATH（说明为何真机没暴露）", "RPATH" in raw)
check("注释记录真实报错文案", 'library "libz.so.1" not found' in raw)
check("注释说明 exec 不新增外壳", "PR_SET_PDEATHSIG" in raw)

print("\n=== A6. 不能有「直接 execve node」的旧路径残留 ===")
# native 分支里若把 nodeBin 直接赋给 cmd 就是退回旧行为
check("非 root 分支没有 cmd = nodeBin.absolutePath",
      "cmd = nodeBin.absolutePath" not in native)
check("非 root 分支必然设置 cmd（要么 SYSTEM_SH）", "cmd = SYSTEM_SH" in native)


# ── B. 行为验证：同一套引号规则交给真实 POSIX sh 跑 ─────────────────
print("\n=== B. 行为验证：真实 sh 里 export → exec 的往返 ===")


def kotlin_shell_quote(s: str) -> str:
    """复刻 EngineProcess.shellQuote()。

    Kotlin 原文：
        if (!s.contains(Regex("[\\s\"'\\\\]"))) return "'$s'"
        return "'" + s.replace("'", "'\\''") + "'"
    """
    if not re.search(r"[\s\"'\\]", s):
        return "'" + s + "'"
    return "'" + s.replace("'", "'\\''") + "'"


# 先确认复刻的算法与 Kotlin 源码里写的形态一致（否则行为验证测的是别的东西）
check("shellQuote 的判据字符类一致",
      re.search(r'Regex\("\[\\\\s\\"\'\\\\\\\\\]"\)', raw) is not None
      or 'Regex("[\\\\s\\"\'\\\\\\\\]")' in raw,
      "Kotlin 里没找到预期的字符类")
check("shellQuote 用 '\\'' 转义单引号", "'\\\\''" in raw)

LIB = "/tmp/dsh lib/lib:$/tmp/other"
FAKE = "/tmp/dsh spawn/fake node"
ARGS = ["--expose-internals", "/tmp/a b/entry.js", "web", "--patch", "/tmp/p atch.yml",
        "--no-open", "it's"]

script = (
    "export LD_LIBRARY_PATH=" + kotlin_shell_quote(LIB) + "; "
    + "exec " + kotlin_shell_quote(FAKE)
    + "".join(" " + kotlin_shell_quote(a) for a in ARGS)
)

# 用一个假程序验证：它把自己的环境与 argv 原样打印出来
probe_dir = os.path.join(ROOT, "_engspawn_tmp")
os.makedirs(probe_dir, exist_ok=True)
fake_py = os.path.join(probe_dir, "fake node")
with open(fake_py, "w", encoding="utf-8", newline="\n") as f:
    f.write('#!/bin/sh\necho "LD=$LD_LIBRARY_PATH"\nfor a in "$@"; do echo "ARG=$a"; done\n')
os.chmod(fake_py, 0o755)
script_real = script.replace(kotlin_shell_quote(FAKE), kotlin_shell_quote(fake_py))

try:
    sh = "/bin/sh" if os.path.exists("/bin/sh") else "sh"
    p = subprocess.run([sh, "-c", script_real], capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=30)
    out = (p.stdout or "") + (p.stderr or "")
    check("脚本无语法错误", p.returncode == 0, f"rc={p.returncode} {out[:200]}")
    check("exec 之后 LD_LIBRARY_PATH 可见", f"LD={LIB}" in out,
          "变量没穿透到被 exec 的程序")
    got = [ln[len("ARG="):] for ln in out.split("\n") if ln.startswith("ARG=")]
    check("argv 逐项原样往返", got == ARGS, f"got={got}")
    check("含空格的参数未被拆分", "/tmp/a b/entry.js" in got)
    check("含单引号的参数未被截断", "it's" in got)
finally:
    for name in os.listdir(probe_dir):
        os.remove(os.path.join(probe_dir, name))
    os.rmdir(probe_dir)

print("\n" + "-" * 44)
print(f"通过 {ok} / 失败 {fail}")
sys.exit(1 if fail else 0)
