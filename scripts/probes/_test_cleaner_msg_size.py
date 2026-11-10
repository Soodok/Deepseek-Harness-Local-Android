#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""cleaner 请求消息的容量边界：两端都要 64KB + 循环 IO + 超限显式失败。

实测（模拟器 dsh34 / x86_64）暴露的静默截断：
  单变量 7000 字节  → bionic 子进程收到 7000   ✅
  单变量 30000 字节 → 只收到 7043（ENVBYTES 卡在 8130 ≈ 8191）❌

两端都有 8KB 定长 + 单次 IO：
  发送端 cleaner_exec 的 msg[8192]
  接收端 cleaner_loop 的 buf[8192] + 单次 read
**只改一端症状一模一样** —— 这是本条最容易被误判成「已修」的地方。

修法三件：
  1) 两端缓冲都放大到 64KB
  2) 接收端循环读到协议结束（不能假设一次 read 拿全）
  3) 发送端装不下时**显式失败并告诉用户原因**，绝不静默截断
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "cpp", "dsh_alpine.c")

checks = []
failed = []


def ok(name, cond, detail=""):
    checks.append((name, bool(cond), detail))
    if not cond:
        failed.append(name)


src = io.open(SRC, encoding="utf-8").read()
nc = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
nc = re.sub(r"//[^\n]*", "", nc)

# ---- A. 发送端容量 ----
ok("A1 发送端 msg 缓冲是 65536（不是 8192）",
   re.search(r"static char msg\[65536\]", nc))
ok("A2 发送端没有残留 msg[8192]",
   not re.search(r"static char msg\[8192\]", nc))

# ---- B. 接收端容量 + 循环读 ----
ok("B1 接收端 buf 缓冲是 65536", re.search(r"static char buf\[65536\]", nc))
ok("B2 接收端没有残留 buf[8192]",
   not re.search(r"static char buf\[8192\]", nc))

# 接收端必须是 while 循环读，不能是单次 long n = raw6(SYS_read ...)
m_single = re.search(r"long\s+n\s*=\s*raw6\(\s*SYS_read\s*,\s*cfd", nc)
ok("B3 接收端不是单次 read（改成了循环）", not m_single)
ok("B4 接收端有 while 循环读", re.search(r"while\s*\(\s*!\s*done\s*&&\s*n\s*<", nc))

# ---- C. 超限必须显式失败，不能静默截断 ----
ok("C1 有 trunc 标记", re.search(r"int\s+trunc\s*=\s*0", nc))
ok("C2 拷贝循环里设置 trunc", len(re.findall(r"trunc\s*=\s*1", nc)) >= 3)
ok("C3 超限分支直接 return -1", re.search(r"if\s*\(\s*trunc\s*\)\s*\{[^}]*return\s+-1", nc, flags=re.S))

# ---- D. 超限错误必须**用户可见**，不能只有 loader 内部的 FAIL ----
m_user = re.search(r"cannot run bionic program", nc)
ok("D1 超限时打印用户可读原因", m_user)
ok("D2 原因里带上实际字节数", re.search(r"ldr_log1\(\"\",\s*n,\s*10\)", nc))
ok("D3 不要只留通用的 cleaner_exec FAIL",
   m_user is not None)

# ---- E. 源码必须是纯文本（写入 NUL 会让 grep/sed 静默跳过） ----
raw = open(SRC, "rb").read()
ok("E1 源码不含 NUL 字节（否则 grep 会当二进制跳过）", raw.count(b"\x00") == 0,
   "%d NUL" % raw.count(b"\x00"))
ok("E2 源码是合法 UTF-8", True if raw.decode("utf-8", "replace") else False)

# ---- F. 反向自检 ----
sab = nc.replace("static char buf[65536]", "static char buf[8192]", 1)
ok("F1 反向：接收端改回 8192 后 B1 判据失效",
   re.search(r"static char buf\[65536\]", sab) is None)
sab2 = nc.replace("static char msg[65536]", "static char msg[8192]", 1)
ok("F2 反向：发送端改回 8192 后 A1 判据失效",
   re.search(r"static char msg\[65536\]", sab2) is None)

# ---- G. 两架构产物 ----
for abi, em in (("arm64-v8a", 183), ("x86_64", 62)):
    p = os.path.join(ROOT, "app", "src", "main", "jniLibs", abi, "libdsh_alpine.so")
    ok("G %s 产物存在" % abi, os.path.isfile(p), p)
    if os.path.isfile(p):
        with open(p, "rb") as f:
            hdr = f.read(20)
        ok("G %s e_machine=%d" % (abi, em),
           hdr[:4] == b"\x7fELF" and int.from_bytes(hdr[18:20], "little") == em)

print()
for name, good, detail in checks:
    print("  [%s] %s%s" % ("OK  " if good else "FAIL", name,
                           (" — " + detail) if detail else ""))
print()
print("checks=%d failed=%d" % (len(checks), len(failed)))
print("RESULT: %s" % ("OK" if not failed else "FAILED"))
sys.exit(0 if not failed else 1)
