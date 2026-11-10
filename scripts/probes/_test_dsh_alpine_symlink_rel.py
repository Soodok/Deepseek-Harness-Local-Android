#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守「容器里能装带符号链接的 deb」这条能力 —— Ubuntu apt 模式的地基。

## 为什么需要这个探针（2026-11-07 模拟器实测事故）

扩展中心在 Linux 模式下走 `apt-get -d install` → `dpkg -i` 两步法装包。首次
在模拟器上实测 Ubuntu 装 `sqlite3` 时失败：

    dpkg: error processing archive …/sqlite3_…_amd64.deb (--install):
     error creating symbolic link './usr/share/doc/sqlite3/changelog.Debian.gz':
     No such file or directory

strace 抓到真相：

    5103  symlink("../libsqlite3-0/changelog.Debian.gz",
                 "/usr/share/doc/sqlite3/changelog.Debian.gz.dpkg-new") = 88
    5103  --- SIGSYS {si_syscall=__NR_symlink} ---
    5103  symlink(...) = -1 ENOENT

**根因**：`SYS_symlink` 原先与 `SYS_rename`/`SYS_link` 合并成一支，共享守卫

    if (!op || op[0] != '/' || !np || np[0] != '/')  →  原样转发

这对 rename/link 是对的（两个参数都是真实路径），但 symlink 的**第一个参数是
链接内容**（写进 inode 的字符串），既可能是相对也可能是绝对，**一律不该翻译**。
dpkg 传的 target 是 `../libsqlite3-0/…`（相对）→ 守卫判定「相对路径」→ 把
**绝对路径的 linkpath 也原样转发** → 落到宿主根 → ENOENT。

后果不是「少一个功能」：**任何含符号链接的 deb 都装不上**，而 Ubuntu 的绝大多数
包都有符号链接（`.so` 版本链接、`/usr/bin` 快捷方式、doc 目录链接）。这直接等于
「Ubuntu 模式下扩展中心不可用」。

修好第一层后还暴露出第二层（同一个 strace 会话里看到）：

    5103  symlinkat("..", 1024, "./usr/share/doc/sqlite3/changelog.Debian.gz.dpkg-new") = 0  ← 修好了
    5103  lchown("/usr/share/doc/sqlite3/changelog.Debian.gz.dpkg-new", 0, 0) = -1 ENOENT

`lchown` 是 x86_64 独有的老式 syscall，不在 BPF 树里 → 完全没被 TRAP → 直接
落到宿主根。而它又**必须**用 lchown（改链接本身，不能跟随目标）。

## 本探针断言什么

1. **静态**：`SYS_symlink` 是**独立一支**，不与 rename/link 合并；且它的守卫
   只看 linkpath（r[1]），不看 target（r[0]）。
2. **静态**：`lchown` / `utimes` / `mknod` 同时出现在 BPF 生成器的 TRAP 清单
   （`LEGACY_X86_64`）**和** 源码的老式 syscall switch 里 —— 只在一边会出现
   「拦到了但没做分派」，落到 default 用截断指针，报 Bad address（实测过）。
3. **产物**：两个 ABI 的 `libdsh_alpine.so` 都存在且是 ET_EXEC（重编过）。
4. **反向自检**：把源码里 symlink 分支的守卫改回合并形态时，本探针必须变红。

跑法：python scripts/probes/_test_dsh_alpine_symlink_rel.py
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app/src/main/cpp/dsh_alpine.c")
GEN = os.path.join(ROOT, "scripts/gen_bpf_tree.py")
SO_ARM = os.path.join(ROOT, "app/src/main/jniLibs/arm64-v8a/libdsh_alpine.so")
SO_X86 = os.path.join(ROOT, "app/src/main/jniLibs/x86_64/libdsh_alpine.so")

checks = 0
failed = 0


def ok(cond, msg, detail=""):
    global checks, failed
    checks += 1
    if cond:
        print("  [OK]   " + msg)
    else:
        failed += 1
        print("  [FAIL] " + msg + ((" - " + detail) if detail else ""))


def read(p):
    with open(p, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def strip_comments(src):
    """去掉 C/C++ 注释，避免注释里的示例代码被当成真实实现。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def main():
    print("=" * 66)
    print("dsh_alpine: relative-symlink / lchown handling for apt installs")
    print("=" * 66)

    for p in (SRC, GEN):
        if not os.path.isfile(p):
            print("missing: %s" % p)
            return 1

    raw = read(SRC)
    code = strip_comments(raw)

    print("\n[A] SYS_symlink 必须是独立分支（不与 rename/link 合并）")
    # 反例：合并形态 `case SYS_rename: case SYS_link: case SYS_symlink: {`
    merged = re.search(
        r"case\s+SYS_rename\s*:\s*case\s+SYS_link\s*:\s*case\s+SYS_symlink\s*:", code)
    ok(merged is None,
       "SYS_symlink is NOT in the merged rename/link case",
       "found merged case at char %d" % (merged.start() if merged else -1))

    ok(re.search(r"case\s+SYS_rename\s*:\s*case\s+SYS_link\s*:\s*\{", code) is not None,
       "rename/link still share one branch (they are both real paths)")

    ok(re.search(r"case\s+SYS_symlink\s*:\s*\{", code) is not None,
       "SYS_symlink has its own case block")

    print("\n[B] symlink 分支的守卫只能看 linkpath（r[1]），不能看 target（r[0]）")
    m = re.search(r"case\s+SYS_symlink\s*:\s*\{(.*?)\n    \}", code, re.S)
    ok(m is not None, "symlink branch body extracted")
    if m:
        body = m.group(1)
        # 取守卫那一行：if (!np || np[0] != '/')
        guard = re.search(r"if\s*\(([^)]*np[^)]*)\)", body)
        ok(guard is not None, "symlink guard on linkpath exists",
           "no `if (… np …)` found in body")
        if guard:
            g = guard.group(1)
            ok("tp" not in g,
               "guard does NOT consult target (tp) — target is link CONTENT, not a path",
               "guard reads: %s" % g.strip())
            ok("np" in g, "guard consults linkpath (np)")
        # target 必须以原样传下去（不 make_rel / 不 bind_map）
        ok(re.search(r"raw6\(SYS_symlinkat,\s*\(long\)tp", body) is not None,
           "target passed through verbatim to symlinkat")
        ok("make_rel(nb, np)" in body or "make_rel(nb,np)" in body,
           "linkpath still goes through make_rel (translated into rootfs)")
        # 反例：如果 target 被 make_rel 处理过，说明又把内容当路径了
        ok(re.search(r"make_rel\([^,]+,\s*tp\)", body) is None,
           "target is NOT make_rel'd (would corrupt the link content)")

    print("\n[C] lchown / utimes / mknod 必须同时在 BPF 树与 switch 分派里")
    gen = read(GEN)
    legacy = re.search(r"LEGACY_X86_64\s*=\s*\[(.*?)\]", gen, re.S)
    ok(legacy is not None, "LEGACY_X86_64 list found in generator")
    legacy_body = legacy.group(1) if legacy else ""
    for name in ("lchown", "utimes", "mknod"):
        ok('"%s"' % name in legacy_body,
           "generator traps %s on x86_64" % name,
           "not in LEGACY_X86_64")
        # 源码里必须也有 case（否则 TRAP 后落 default，用截断指针 → EFAULT/Bad address）
        ok(re.search(r"case\s+SYS_%s\s*:" % name, code) is not None,
           "source dispatches SYS_%s (else TRAP falls into truncated-pointer default)" % name,
           "no case SYS_%s in code" % name)

    # lchown 必须带 AT_SYMLINK_NOFOLLOW，否则会改到链接目标上
    ok("AT_SYMLINK_NOFOLLOW" in raw,
       "lchown uses AT_SYMLINK_NOFOLLOW (must not follow the link target)")
    # 内层分派（缩进 8 空格）才是真正干活的；外层的 case 列表是
    # `case SYS_lchown: case SYS_utimes: case SYS_mknod: {`，两者不能混淆。
    m2 = re.search(r"\n\s{8}case\s+SYS_lchown\s*:(.*?)return;", code, re.S)
    ok(m2 is not None, "lchown inner dispatch body extracted")
    if m2:
        ok("0x100" in m2.group(1) and "fchownat" in m2.group(1),
           "lchown → fchownat(..., 0x100)",
           "body: %s" % m2.group(1).strip()[:120])

    print("\n[D] 两个 ABI 的预编译产物都在且是 ET_EXEC（改过源码必须重编）")
    for so, mach in ((SO_ARM, "AArch64"), (SO_X86, "X86-64")):
        if not os.path.isfile(so):
            ok(False, "artifact exists: %s" % os.path.basename(os.path.dirname(so)))
            continue
        ok(True, "artifact exists: %s" % os.path.relpath(so, ROOT))
        hdr = open(so, "rb").read(64)
        # e_type 在 offset 16（2 字节），ET_EXEC = 2
        e_type = int.from_bytes(hdr[16:18], "little")
        ok(e_type == 2, "%s e_type=ET_EXEC(2)" % mach, "got %d" % e_type)
        ok(hdr[4] == 2, "%s ELFCLASS64" % mach)
        # e_machine: AArch64=183(0xB7), x86-64=62(0x3E)，都在 offset 18
        em = int.from_bytes(hdr[18:20], "little")
        want = 183 if mach == "AArch64" else 62
        ok(em == want, "%s e_machine=%d" % (mach, em), "expected %d" % want)

    print("\n[E] 反向自检：探针能识别「把 symlink 改回合并形态」")
    # 构造一个合并形态的假源码，确认 [A] 的正则会命中
    fake = "case SYS_rename: case SYS_link: case SYS_symlink: { return; }"
    ok(re.search(r"case\s+SYS_rename\s*:\s*case\s+SYS_link\s*:\s*case\s+SYS_symlink\s*:",
                 fake) is not None,
       "merged-shape regex really matches a merged case (guard against dead regex)")

    return finish()


def finish():
    print("\n" + "-" * 66)
    print("checks=%d failed=%d" % (checks, failed))
    if failed:
        print("RESULT: FAILED")
        return 1
    print("RESULT: PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
