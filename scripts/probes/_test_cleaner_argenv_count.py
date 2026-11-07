#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""cleaner 请求里 argv / env 的**条数**上限：必须显式失败，且数组与阈值单源。

实测（模拟器 dsh34 / x86_64）暴露的静默丢弃：
  容器内 200 个环境变量 → bionic 子进程只拿到 **127** 个，
  LASTENV 停在 V116，V117~V200 与 FINALVAR 全部消失，程序侧完全看不出问题。

更糟的是我做反向验证时踩到的栈踩踏：只把数组 `ev[256]` 改回 `ev[128]`
而没改判断阈值 `ec >= 255`，指针写到下标 255 —— **越过数组末尾把栈上的
av[] 一起踩坏**，出现 `ARGV=90` 且环境变量被当成参数的怪象。
生产代码里这两个常量一旦不同步就是栈溢出，所以必须由同一个宏派生。

修法：
  1) `#define ARGV_MAX 63` / `#define ENV_MAX 255`
  2) 数组写成 `[ARGV_MAX + 1]` / `[ENV_MAX + 1]`，阈值用同一个宏
  3) 超限时回一个非 0 退出码 + 打印用户可读原因，不执行半截请求
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

# ---- A. 宏定义 ----
m_argv = re.search(r"#define\s+ARGV_MAX\s+(\d+)", nc)
m_env = re.search(r"#define\s+ENV_MAX\s+(\d+)", nc)
ok("A1 ARGV_MAX 宏存在", m_argv)
ok("A2 ENV_MAX 宏存在", m_env)
ok("A3 ENV_MAX >= 255（容器里常见 200+ 个变量）",
   m_env and int(m_env.group(1)) >= 255, m_env.group(1) if m_env else "")

# ---- B. 数组大小与阈值必须单源（同宏派生） ----
ok("B1 argv 数组用 [ARGV_MAX + 1] 派生", re.search(r"char\s*\*av\[ARGV_MAX\s*\+\s*1\]", nc))
ok("B2 env 数组用 [ENV_MAX + 1] 派生", re.search(r"char\s*\*ev\[ENV_MAX\s*\+\s*1\]", nc))
ok("B3 argv 阈值用 ARGV_MAX", re.search(r"if\s*\(\s*ac\s*>=\s*ARGV_MAX\s*\)", nc))
ok("B4 env 阈值用 ENV_MAX", re.search(r"if\s*\(\s*ec\s*>=\s*ENV_MAX\s*\)", nc))

# 绝对不能出现「数组是宏、阈值是裸数字」这种不同步形态
ok("B5 没有裸数字阈值残留（argv>=63 / env>=127 / env>=255）",
   not re.search(r"if\s*\(\s*ac\s*>=\s*63\s*\)", nc)
   and not re.search(r"if\s*\(\s*ec\s*>=\s*(127|255)\s*\)", nc))
ok("B6 没有裸数字数组声明残留",
   not re.search(r"char\s*\*av\[64\]", nc) and not re.search(r"char\s*\*ev\[(128|256)\]", nc))

# ---- C. 超限必须显式失败 ----
ok("C1 有 too_many 标记", re.search(r"int\s+too_many\s*=\s*0", nc))
ok("C2 too_many 至少被置两次（argv 与 env 两处）",
   len(re.findall(r"too_many\s*=\s*1", nc)) >= 2)
ok("C3 超限分支存在", re.search(r"if\s*\(\s*too_many\s*\)\s*\{", nc))

# ---- D. 超限原因必须用户可见 ----
ok("D1 打印用户可读原因", re.search(r"bionic exec refused", nc))
ok("D2 原因里带上实际条数（ac / ec）",
   re.search(r'ldr_log1\("[^"]*",\s*ac,', nc) and re.search(r'ldr_log1\("[^"]*",\s*ec,', nc))
ok("D3 回非 0 退出码而不是静默继续",
   re.search(r"unsigned char\s+bad\s*=\s*\d+", nc))

# ---- E. 源码纯文本性（NUL 会让 grep 静默跳过，见 #65） ----
raw = open(SRC, "rb").read()
ok("E1 源码不含 NUL 字节", raw.count(b"\x00") == 0, "%d NUL" % raw.count(b"\x00"))

# ---- F. 反向自检：宏与数组必须联动 ----
sab = nc.replace("#define ENV_MAX  255", "#define ENV_MAX  127", 1)
ok("F1 反向：把 ENV_MAX 改成 127 后 A3 判据失效",
   re.search(r"#define\s+ENV_MAX\s+255", sab) is None)
sab2 = re.sub(r"char\s*\*ev\[ENV_MAX\s*\+\s*1\]", "char *ev[128]", nc, count=1)
ok("F2 反向：把数组写成裸 128 后 B2 判据失效",
   re.search(r"char\s*\*ev\[ENV_MAX\s*\+\s*1\]", sab2) is None)

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
