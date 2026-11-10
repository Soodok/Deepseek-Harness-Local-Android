#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""cleaner 的对端凭据校验（SO_PEERCRED）。

为什么需要这个探针：抽象命名空间的 AF_UNIX socket **没有任何权限保护**。
实测（本机）：uid=0 绑定 @dsh-cleaner-... 后，uid 10811 / 10810 都能
connect 成功（rc=0）。而 cleaner_exec 拿到对端的 path/argv/env 后是用
**cleaner 自己的权限** fork+execve —— cleaner 跑在 App uid 下，于是同机
任意低权限进程都能借它提权执行任意程序。

这条链原本靠「socket 名里带 uid 段、按 uid 精确匹配」挡住；但 fork+exec
场景必须放宽成前缀匹配（pid 变了猜不中，只能扫 /proc/net/unix），
名字就不再是护栏。所以必须在 accept 之后补 SO_PEERCRED 校验。
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
# 剥掉注释再做存在性判断，避免「注释里写了」被当成实现
nc = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
nc = re.sub(r"//[^\n]*", "", nc)


# ---- A. 校验逻辑本体 ----
ok("A1 调用 getsockopt 取对端凭据", re.search(r"SYS_getsockopt", nc))
# 注释已剥，「SO_PEERCRED」字样不在了；判 getsockopt 调用的选项参数是 17
# 剥注释后形如 raw6(SYS_getsockopt, cfd, 1, 17, ...)，选项号 17 紧跟第三个参数
_gso = ""
if "SYS_getsockopt" in nc:
    _gso = nc[nc.find("SYS_getsockopt"):nc.find("SYS_getsockopt") + 120]
ok("A2 选项号是 SO_PEERCRED(17)", "17" in _gso)
ok("A3 比对对端 uid 与本进程 uid", re.search(r"pc\.uid\s*!=\s*myuid", nc))
ok("A4 getsockopt 失败也拒绝（失败不能等于放行）",
   re.search(r"gr\s*!=\s*0\s*\|\|", nc))

# ---- B. 拒绝路径必须是「关掉并继续」 ----
body = re.search(r"if\s*\(\s*gr\s*!=\s*0[^{]*\{(.*?)\n\s{8}\}", nc, flags=re.S)
ok("B1 拒绝分支存在", body)
if body:
    b = body.group(1)
    ok("B2 拒绝分支关掉连接", "SYS_close" in b)
    ok("B3 拒绝分支 continue 回到 accept（不退出 cleaner）", "continue" in b)
    ok("B4 拒绝分支不写回执（没向 cfd 写数据）",
       not re.search(r"SYS_write\s*\(\s*cfd", b))

# ---- C. 校验必须在 read 请求体之前 ----
m_accept = re.search(r"SYS_accept4", nc)
m_check = re.search(r"SYS_getsockopt", nc)
m_read = re.search(r"SYS_read\s*,\s*cfd", nc)
ok("C1 accept / 校验 / read 三者顺序正确",
   bool(m_accept and m_check and m_read
        and m_accept.start() < m_check.start() < m_read.start()))

# ---- D. 前缀扫描仍保留（fork+exec 场景的必需品） ----
ok("D1 保留前缀扫描（fork+exec 靠它）", "find_cleaner_by_prefix" in nc)
ok("D2 保留探活适配（现找现连兜底）", "cleaner_probe_and_adopt" in nc)

# ---- E. 两架构产物 ----
for abi, em in (("arm64-v8a", 183), ("x86_64", 62)):
    p = os.path.join(ROOT, "app", "src", "main", "jniLibs", abi, "libdsh_alpine.so")
    ok("E %s 产物存在" % abi, os.path.isfile(p), p)
    if os.path.isfile(p):
        with open(p, "rb") as f:
            hdr = f.read(20)
        ok("E %s 是 ELF64" % abi, hdr[:4] == b"\x7fELF" and hdr[4] == 2)
        ok("E %s e_machine=%d" % (abi, em),
           int.from_bytes(hdr[18:20], "little") == em)

# ---- F. 反向自检：把校验改成恒假后，判据必须失效 ----
sab = re.sub(r"if\s*\(\s*gr\s*!=\s*0[^{]*\{", "if (0) {", nc, count=1)
ok("F1 反向：改成 if (0) 后 A4 的判据失效",
   re.search(r"gr\s*!=\s*0\s*\|\|", sab) is None)

print()
for name, good, detail in checks:
    print("  [%s] %s%s" % ("OK  " if good else "FAIL", name,
                           (" — " + detail) if detail else ""))
print()
print("checks=%d failed=%d" % (len(checks), len(failed)))
print("RESULT: %s" % ("OK" if not failed else "FAILED"))
sys.exit(0 if not failed else 1)
