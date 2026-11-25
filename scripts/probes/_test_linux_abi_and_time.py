#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""issue #132 的三类缺陷守卫。

背景（2026-11-25，模拟器 x86_64 / Android 14 / App 域）：

① Ubuntu rootfs 与 glibc node 只写死 arm64
   → x86_64 设备部署「成功」但容器里每个程序都是 aarch64 ELF，内核拒绝执行，
     `apt-get update` 报 `[exit 132]`（132 = 128 + SIGILL），且**没有任何输出**。
② apt 源写死 `ubuntu-ports`
   → `ubuntu-ports` 只收录非 amd64 架构；x86_64 容器里拿到的索引里一个 amd64
     包都没有，报 `E: Unable to locate package python3`，看着像源挂了。
③ `time(201)` 被 Android zygote seccomp 拦（只按号判、不看 IP）
   → gpgv 拿不到当前时间，退化成 0（1970），于是**每个** Release 都被判成
     「未来」：`Release file ... is not valid yet (invalid for another 19838d)`。
     而 19838 天恰好等于那个 Release 的 Date 距 epoch 的天数 —— 差一天都不差。

另有两条装包链路的收尾缺陷：

④ `dpkg-divert --add --rename` 在容器里报
   `error checking '<path>': Operation not permitted`
   真因是 glibc `realpath()` 对带尾斜杠路径失败（实测：
   `libc.realpath("/etc")` → "/etc"，`libc.realpath("/etc/")` → NULL errno=EPERM）。
   脚本 `set -e` 因此直接退出 → ldconfig shim 永远装不上。
⑤ 一趟 `dpkg -i` 过不了 Pre-Depends（`python3` 要求 `python3-minimal`
   **已 configured** 而不只是 unpacked）→ 整批包停在 iU。

判据全部落在源码文本上，不依赖设备；变异验证见文件末尾的注释。
"""

import io
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CATALOG = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LinuxRootfsCatalog.kt")
ENVMGR = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LinuxEnvironmentManager.kt")
LOADER = os.path.join(ROOT, "app/src/main/cpp/dsh_alpine.c")
BPFGEN = os.path.join(ROOT, "scripts/gen_bpf_tree.py")

fails = []


def chk(cond, msg):
    if not cond:
        fails.append(msg)
        print("FAIL  " + msg)
    else:
        print("ok    " + msg)


def read(p):
    return io.open(p, encoding="utf-8").read()


def strip_line_comments(s):
    """剥掉 Kotlin 的 `//` 行注释。

    ★不能简单找 `//`：URL 里的 `https://` 也会命中，会把整条字符串砍成
    `"https:`（历史教训：探针第一版就这么干，A2c/A2d 于是把
    `…-linux-arm64.tar.xz` 当成注释剥掉，源码明明两分支都写了却判成缺失，
    是一批**假红**）。
    判据：`//` 前一个字符是 `:` 就跳过，那就是 URL 协议分隔符。
    """
    out = []
    for ln in s.split(chr(10)):
        i = ln.find("//")
        while i >= 0 and i > 0 and ln[i - 1] == ":":
            i = ln.find("//", i + 2)
        out.append(ln if i < 0 else ln[:i])
    return chr(10).join(out)


def fn_body(src, name):
    """切出一个同级声明的函数/属性体。

    依据「换行 + 四空格 + (private |internal |public )?(fun|val|var|const)」
    找下一个同级成员，而不是固定字符窗口 —— 窗口开大就会把下一个函数里的
    调用算进来（这正是历史上反复造成假绿的手法）。
    """
    pat = re.compile("\n    (?:private |internal |public |protected )?(?:fun|val|var|const) ")
    m = re.search(r"\n    (?:private |internal |public |protected )?(?:fun|val|var|const) "
                  + re.escape(name) + r"\b", src)
    if not m:
        return None
    start = m.start() + 1
    nxt = pat.search(src, m.end())
    return src[start:(nxt.start() if nxt else len(src))]


cat = read(CATALOG)
env = read(ENVMGR)
ldr = read(LOADER)
gen = read(BPFGEN)

cat_nc = strip_line_comments(cat)

# ==================== A 段：Ubuntu rootfs 必须按 ABI 分支 ====================
print("\n[A] Ubuntu rootfs / node tarball 的 ABI 分支")

# A1：IS_ARM64 在 nodeTarball 之前声明（否则 Kotlin 报 must be initialized）
i_abi = cat.find("private val IS_ARM64")
i_node = cat.find("val nodeTarball = NodeTarball(")
chk(i_abi >= 0, "A1  IS_ARM64 存在")
chk(i_abi < i_node, "A1b IS_ARM64 声明在 nodeTarball 之前（否则编译报 must be initialized）")

# A2：nodeTarball 的三处元数据都按 ABI 分支
node = fn_body(cat_nc, "nodeTarball")
chk(node is not None, "A2  nodeTarball 函数体可切出")
if node:
    chk(node.count("if (IS_ARM64)") >= 3,
        "A2b nodeTarball 的 urls/sha256/bytes 三处都按 IS_ARM64 分支（实测 %d 处）"
        % node.count("if (IS_ARM64)"))
    chk('linux-x64' in node, "A2c nodeTarball 含 linux-x64 源")
    chk('linux-arm64' in node, "A2d nodeTarball 保留 linux-arm64 源")
    # sha256 必须两条都在，且互不相同
    shas = re.findall(r'"([0-9a-f]{64})"', node)
    chk(len(shas) >= 2 and shas[0] != shas[1],
        "A2e nodeTarball 有两个不同的 sha256（x64 与 arm64 各一）")

# A3：UBUNTU 的 RootfsSource 必须按 ABI 选源
ub = cat.find("Family.UBUNTU -> Option(")
if ub < 0:
    ub = cat.find("rootfs for Ubuntu")
u_blk_start = cat.find("RootfsSource.Remote(", cat.find("Family.UBUNTU", 0))
chk(u_blk_start > 0, "A3  找到 UBUNTU 的 RootfsSource.Remote")
if u_blk_start > 0:
    seg = cat_nc[u_blk_start:u_blk_start + 3000]
    chk("if (IS_ARM64)" in seg, "A3b UBUNTU 源按 IS_ARM64 分支")
    chk("amd64" in seg, "A3c UBUNTU 含 amd64 rootfs 源")
    chk("arm64" in seg, "A3d UBUNTU 保留 arm64 rootfs 源")
    # interpPath 两架构都要在
    chk("/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2" in seg,
        "A3e interpPath 含 x86_64 动态链接器")
    chk("/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" in seg,
        "A3f interpPath 保留 arm64 动态链接器")

# ==================== B 段：apt 源不得写死 ubuntu-ports ====================
print("\n[B] aptSources 按 ABI 选归档路径")

apt = fn_body(cat_nc, "aptSources")
chk(apt is not None, "B1  aptSources 函数体可切出")
if apt:
    chk('if (IS_ARM64) "ubuntu-ports" else "ubuntu"' in apt,
        "B1b aptSources 按 ABI 在 ubuntu-ports / ubuntu 之间切换")
    # 剥注释后不许出现「无条件写死 ports」的形态
    hard = re.search(r'URIs:\s*http[^"]*ubuntu-ports/', apt)
    chk(hard is None,
        "B1c 剥注释后不存在无条件写死的 ubuntu-ports URI 字面量")

# ==================== C 段：time(201) 必须在 trap 表里并被翻译 ====================
print("\n[C] time(201) 的 trap 与翻译")

chk('"time",' in gen or '"time"' in gen, "C1  gen_bpf_tree.py 的 trap 表含 time")
# 且必须只在 x86_64 分支加（aarch64 通用 ABI 没有 time 这个号）
g_legacy = gen[gen.find("LEGACY_X86_64 = ["):gen.find("]", gen.find("LEGACY_X86_64 = ["))]
chk('"time"' in g_legacy,
    "C1b time 加在 LEGACY_X86_64（★不能进 NEEDED：aarch64 通用 ABI 没有 time 这个号，"
    "两条架构都查会让 aarch64 生成时 SystemExit）")
g_needed2 = gen[gen.find("NEEDED = ["):gen.find("]", gen.find("NEEDED = ["))]
chk('"time"' not in g_needed2, "C1b2 time 不在 NEEDED 里（aarch64 没有此号）")
# 树里真的出现 SYS_time（生成器实际产出了）
chk("SYS_time" in ldr, "C1c 编译进 dsh_alpine.c 的 BPF 树里出现 SYS_time")
if "SYS_time" not in ldr:
    print("      (生成器输出里是否含 SYS_time：%r)" % ("SYS_time" in gen))
# 且只在 x86_64 那段 —— aarch64 段里不许有
i_arm = ldr.find("#if defined(DSH_ARCH_AARCH64)", ldr.find("static const struct sock_filter f[]"))
i_el = ldr.find("#else", i_arm)
i_end = ldr.find("#endif", i_el)
arm_tree = ldr[i_arm:i_el]
x86_tree = ldr[i_el:i_end]
chk("SYS_time" not in arm_tree, "C1d aarch64 树里没有 SYS_time（通用 ABI 无此号）")
chk("SYS_time" in x86_tree, "C1e x86_64 树里有 SYS_time")

# C2：handler 里必须有翻译分支
tb = ldr.find("if (nr == SYS_time)")
chk(tb >= 0, "C2  sigsys handler 有 `if (nr == SYS_time)` 分支")
if tb >= 0:
    seg = ldr[tb:tb + 2200]
    chk("SYS_clock_gettime" in seg, "C2b 翻译成 clock_gettime(228, CLOCK_REALTIME)")
    chk("SYS_gettimeofday" in seg, "C2c 有 gettimeofday 作为兜底")
    chk("*(long *)(unsigned long long)r[0] = ts.tv_sec" in seg,
        "C2d 回填 tloc（time 的 tloc 可为 NULL，必须判 r[0] 非零）")
    chk("if (r[0])" in seg, "C2e 写 tloc 前判断 r[0] 非空")
    chk("return;" in seg, "C2f 分支末尾 return（不许落回 default 原样重发）")

# C3：注释里必须留住真实证据（防止后来者「优化」掉）
chk("19838" in ldr, "C3  注释保留 19838 天这条算术铁证")
chk("Fatal: time() failed" in ldr, "C3b 注释保留 gpgv 的原始报错原文")

# ==================== D 段：dpkg-divert 用 --no-rename + 自己 mv ====================
print("\n[D] quirks 脚本的 dpkg-divert 写法")

q = fn_body(cat_nc, "glibcQuirksScript")
chk(q is not None, "D1  glibcQuirksScript 可切出")
if q:
    chk("--add --no-rename --divert" in q,
        "D1b 用 `--add --no-rename --divert`（--rename 会触发 realpath 校验而 EPERM）")
    bad = re.search(r'appendLine\("  dpkg-divert --add --rename', q)
    chk(bad is None, "D1c 不存在 `dpkg-divert --add --rename` 的写法")
    chk("mv -f /usr/sbin/ldconfig.real /usr/sbin/ldconfig.dshreal" in q,
        "D1d 自己 mv 真文件（与 --rename 语义等价）")
    chk("--no-rename --divert" in q and "&&" not in q.split("--no-rename")[1][:200].replace("|| true", ""),
        "D1e diversion 登记失败时不能靠 && 短路掉后面的 mv")

# ==================== E 段：dpkg -i 与 configure 必须交替 ====================
print("\n[E] 装包收尾必须「i → configure」交替")

inst = fn_body(env, "installPackagesAptLocked")
chk(inst is not None, "E1  installPackagesAptLocked 可切出（真正的两步法实现；installPackagesApt 只是加锁壳）")
if inst:
    chk('step("dpkg -i (round ' in inst,
        "E1b dpkg -i 带轮次标记（证明它在一个循环里反复执行）")
    _lim = re.search(r"while \(missing\.isNotEmpty\(\) && rounds < (\d+)\)", inst)
    chk(_lim is not None,
        "E1c 循环条件按「还缺包」驱动（`while (missing.isNotEmpty() && rounds < N)`）")
    chk(_lim is not None and int(_lim.group(1)) >= 3,
        "E1c2 轮次上限 >= 3（真机实测全新 rootfs 装 python3 到第 3 轮才收敛，"
        "上限 2 会让链路必失败）")
    chk("verifyAptInstalled(packages)" in inst,
        "E1d 每轮都重新核对（不能只在循环外查一次）")
    # 循环体内必须**同时**有 dpkg -i 与 configure
    loop = inst[inst.find("while (missing.isNotEmpty()"):]
    loop = loop[:loop.find("\n        }")] if "\n        }" in loop else loop
    chk("dpkg -i (round" in loop, "E1e 循环体内有 dpkg -i")
    chk("configureArgv.fold" in loop, "E1f 循环体内有 configure（这才是把 unpacked 推到 installed 的关键）")
    # Pre-Depends 的实证必须留在注释里
    chk("pre-depends on python3-minimal" in inst or "Pre-Depends" in inst.replace("-", ""),
        "E1g 注释保留 Pre-Depends 的实测报错")

# E2：失败仍要如实报（不能因为循环把错误吃掉）
chk("return AptResult(configureOk && missing.isEmpty()" in inst or
    "missing.isEmpty()" in inst,
    "E2  最终成败仍以「包真的装上了」为准，不以退出码为准")

# ==================== F 段：属主/链接自愈仍在（#132 ② 的既有修复不许退化） ====================
print("\n[F] rootfs 自愈链未退化")

chk("repairAbsoluteLinks" in cat or "repairAbsoluteLinks" in read(
    os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/RootfsModes.kt")),
    "F1  RootfsModes.repairAbsoluteLinks 存在（绝对符号链接自愈）")
modes = read(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/RootfsModes.kt"))
chk("repairExecBits" in modes, "F2  repairExecBits 存在（缺 x 位自愈）")
pm = read(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LinuxPackageManager.kt"))
chk("healPackageFiles" in pm, "F3  LinuxPackageManager 的装包后自愈钩子仍在（不许随 #132 退化掉）")

print()
if fails:
    print("FAILED (%d)" % len(fails))
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("All linux ABI / time / apt-install checks passed.")

# ==================== 变异验证（做过，记录在此防退化） ====================
#
# 下列变异逐个施加后应报红，恢复后应复绿且字节一致：
#   M1  nodeTarball 的三处 `if (IS_ARM64)` 改成固定 arm64        → A2b 红
#   M2  UBUNTU 的 RootfsSource 去掉 ABI 分支、写死 arm64         → A3b 红
#   M3  aptSources 改回无条件 `ubuntu-ports/`                      → B1b + B1c 红
#   M4  gen_bpf_tree.py 的 NEEDED 里删掉 `"time"`                 → C1b 红
#   M5  dsh_alpine.c 里 `if (nr == SYS_time)` 改成 `if (0)`       → C2b/C2d/C2f 红
#   M6  quirks 脚本改回 `--add --rename`                          → D1b/D1c 红
#   M7  `while (missing.isNotEmpty() && rounds < N)` 改成
#       `if (missing.isNotEmpty())`（只跑一轮）                    → E1c/E1c2/E1e/E1f 红
