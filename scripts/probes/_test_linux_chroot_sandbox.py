#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""chroot 沙箱策略（root 用户）的静态与形状断言。

## 为什么需要这一组断言

`Sandbox.CHROOT` 这条路径有四个「一旦写错就静默坏掉」的点，全部是实测踩出来的：

1. **su 形态必须适配** —— toybox 的 `su` 把参数当 argv 直接 exec，不经过 shell。
   第一版 `prefix + [script]` 在模拟器上立刻报
   `su: failed to exec for mp in /proc /dev; ...: No such file or directory`。
2. **必须插 `sh -c`** —— 但两种 su 形态插法不同（`-c` 形态要把命令收成一个串，
   `0` 形态要 append 三个元素）。
3. **PATH 必须显式给** —— chroot 后 `/etc/profile` 不读，宿主 PATH 被继承，
   容器里 `uname`/`cat`/`ls` 全部 not found，看起来像 busybox 坏了。
4. **挂载点必须显式建** —— chroot 不自动挂 `/proc` `/dev` `/sys`，缺了 Node
   与包管理器全废。

这一组断言锁死以上四点。每一条都做过反向验证（改坏 → 变红 → 复原 → 全绿）。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
SANDBOX = os.path.join(SRC, "LinuxSandbox.kt")
RUNNER = os.path.join(SRC, "LinuxContainerRunner.kt")

checks = 0
failed = 0


def ok(cond, label):
    global checks, failed
    checks += 1
    if cond:
        print("  ok   %s" % label)
    else:
        failed += 1
        print("  FAIL %s" % label)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def strip_comments(src):
    """去掉行注释与块注释，避免注释里的关键字把断言骗绿。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def main():
    for p in (SANDBOX, RUNNER):
        if not os.path.isfile(p):
            print("FATAL: missing %s" % p)
            return 2

    sb = read(SANDBOX)
    sb_code = strip_comments(sb)
    runner = read(RUNNER)
    runner_code = strip_comments(runner)

    print("\n[A] 两种策略都存在且是 enum")
    ok(re.search(r"enum\s+class\s+Sandbox", sb_code) is not None, "A1 Sandbox 是 enum class")
    ok(re.search(r"\bSECCOMP\b", sb_code) is not None, "A2 有 SECCOMP")
    ok(re.search(r"\bCHROOT\b", sb_code) is not None, "A3 有 CHROOT")

    print("\n[B] 开关默认关闭 + 绝不静默假装 root")
    # 默认值必须是 false：chroot 要 root，且会改挂载命名空间
    ok(
        re.search(r'getBoolean\(\s*KEY_CHROOT\s*,\s*false\s*\)', sb_code) is not None,
        "B1 KEY_CHROOT 默认 false",
    )
    # 关掉开关时直接 SECCOMP，不做 su 探测（否则无 root 用户每次启动都吃探测成本）
    ok(
        re.search(
            r"if\s*\(\s*!\s*isChrootPreferred\(ctx\)\s*\)\s*return\s+SECCOMP", sb_code
        )
        is not None,
        "B2 未开开关时短路返回 SECCOMP（不探测 su）",
    )
    # su 不可用时必须落回 SECCOMP 并且有日志
    ok(
        re.search(r"if\s*\(\s*!\s*ok\s*\)", sb_code) is not None,
        "B3 有 su 不可用的分支",
    )
    ok(
        re.search(
            r'Log\.w\(\s*TAG\s*,\s*"chroot preferred but su unavailable', sb_code
        )
        is not None,
        "B4 su 不可用时打 warn（不静默）",
    )

    print("\n[C] ★su 形态适配（toybox 不经过 shell）")
    fn = re.search(r"private fun wrapSu\(script: String\).*?\n    \}", sb_code, re.S)
    ok(fn is not None, "C1 wrapSu 存在")
    body = fn.group(0) if fn else ""
    # 必须区分 -c 形态与非 -c 形态
    ok('prefix.last() == "-c"' in body, "C2 按 prefix 末元素区分两种 su 形态")
    # -c 形态：把整条命令收成一个字符串（不能裸 append 三个元素）
    ok(
        re.search(r'prefix\s*\+\s*listOf\(\s*"sh -c "\s*\+', body) is not None,
        "C3 -c 形态把命令收成一个串",
    )
    # toybox 形态：append sh/-c/script 三个元素
    ok(
        re.search(r'prefix\s*\+\s*listOf\(\s*"sh"\s*,\s*"-c"\s*,\s*script\s*\)', body)
        is not None,
        "C4 toybox 形态 append sh -c script",
    )
    # 绝不能出现裸 script（那是第一版的 bug）
    ok(
        not re.search(r"prefix\s*\+\s*listOf\(\s*script\s*\)", body),
        "C5 不再出现裸 prefix+[script]（第一版 bug 回归守卫）",
    )

    print("\n[D] PATH 必须显式给（否则 chroot 后 uname/cat 全 not found）")
    ok(
        re.search(r'CONTAINER_PATH\s*=\s*"/usr/local/sbin', sb_code) is not None,
        "D1 CONTAINER_PATH 以 /usr/local/sbin 开头",
    )
    # 绝不能包含宿主路径
    ok(
        "/system/bin" not in re.search(
            r'CONTAINER_PATH\s*=\s*"([^"]*)"', sb_code
        ).group(1),
        "D2 CONTAINER_PATH 不含宿主 /system/bin",
    )
    ok(
        re.search(r"env -i\s*\"\)", sb_code) is not None
        or "env -i " in sb_code,
        "D3 用 env -i 清空宿主环境",
    )
    ok("PATH=$CONTAINER_PATH" in sb_code, "D4 PATH 被显式注入")

    print("\n[E] 挂载点必须显式建（chroot 不自动挂）")
    ok(
        re.search(r'REQUIRED_MOUNT_POINTS\s*=\s*listOf\([^)]*"/proc"', sb_code)
        is not None,
        "E1 挂载点含 /proc",
    )
    for mp in ("/dev", "/sys", "/tmp", "/dev/pts"):
        ok('"%s"' % mp in sb_code, "E2 挂载点含 %s" % mp)
    ok("mountpoint -q" in sb_code, "E3 用 mountpoint -q 幂等判（避免二次 EBUSY）")
    ok("mount --bind" in sb_code, "E4 用 mount --bind")

    print("\n[F] 执行器接线（LinuxContainerRunner）")
    ok(
        re.search(r"when\s*\(\s*Sandbox\.resolve\(ctx\)\s*\)", runner_code) is not None,
        "F1 执行器按 Sandbox.resolve 分支",
    )
    ok(
        "ChrootArgv.build(" in runner_code,
        "F2 CHROOT 分支调 ChrootArgv.build",
    )
    # 运行时失败必须落回 seccomp 且有日志（不许假装 root）
    ok(
        re.search(
            r"if\s*\(\s*built\s*==\s*null\s*\)", runner_code
        )
        is not None,
        "F3 chroot argv 为 null 时有兜底分支",
    )
    ok(
        "falling back to seccomp" in runner_code,
        "F4 兜底时打日志说明退回 seccomp",
    )
    # 兜底分支里必须真的构造 seccomp argv
    ok(
        re.search(
            r"if\s*\(\s*built\s*==\s*null\s*\)\s*\{[^}]*linuxContainerArgv", runner_code, re.S
        )
        is not None,
        "F5 兜底分支真的构造 seccomp argv（不是空转）",
    )

    print("\n[G] 契约差异（chroot 不传 interp）")
    # ChrootArgv.build 的签名不能含 interp 参数
    sig = re.search(r"fun build\(rootfs: File, command: List<String>\)", sb_code)
    ok(sig is not None, "G1 ChrootArgv.build 签名不含 interp（内核按 PT_INTERP 装载）")
    # seccomp 版必须仍然传 interp
    ok(
        re.search(r"fun linuxContainerArgv\([^)]*interp: String", read(
            os.path.join(SRC, "EngineConfig.kt")
        )) is not None,
        "G2 seccomp 版 linuxContainerArgv 仍显式传 interp",
    )

    print("\n[H] 文档化实测数据（防止后人对数字失忆）")
    ok("0.551" in sb, "H1 记录 chroot stat 0.551µs")
    ok("1.997" in sb, "H2 记录 seccomp stat 1.997µs")
    ok("3.6" in sb, "H3 记录 3.6 倍差距")

    print("\n%d checks, %d failed" % (checks, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
