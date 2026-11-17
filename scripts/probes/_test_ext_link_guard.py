#!/usr/bin/env python3
"""扩展软链落点守卫探针（issue #115 / #116）。

守的是 v1.7.12 引入、一直没被发现的**方向性**缺陷：

  守卫要判「链接目标是否还在扩展目录内」。POSIX 里相对 target 的基准是
  **链接所在目录**，但实现把基准写成了「扩展根」。于是 deb 里最常见的
  上级相对软链全部被判越界并静默跳过：

      zig     bin/zig         -> ../lib/zig/zig
      golang  bin/go          -> ../lib/go/bin/go
      dex2jar bin/d2j-dex2jar -> ../opt/dex2jar/d2j-dex2jar.sh

  这些包的负载其实完好（zig 171MB 二进制能直接跑出版本号），只有 bin/ 下
  那条入口链没建出来 → 主程序存在性校验报「none of the executables declared
  by Zig (zig) were installed」，用户看到的是「下载失败，请重试」，而重试
  永远修不好。

判据只认接线事实：
  A. 落点判定必须按链接父目录解析相对 target（且不许退回以根为基准）
  B. createLinks 必须传「剥掉 usr/ 前缀后的 linkRel」，否则父目录多一层
  C. 安装流程必须在主程序存在性校验**之前**补别名链，否则 binutils 这类
     「声明名 ≠ 上游名」的扩展会被判装失败
  D. 启动期必须有存量补链入口（判定修好只救将来的安装）
  E. 落点判定仍必须拒绝真越界（不能为了修 A 把守卫整个废掉）
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
MGR_PATH = os.path.join(ENGINE, "ExtensionManager.kt")
POLICY_PATH = os.path.join(ENGINE, "ExtensionLinkPolicy.kt")
REPAIR_PATH = os.path.join(ENGINE, "ExtensionLinkRepair.kt")
ALIAS_PATH = os.path.join(ENGINE, "ExtensionBinAliases.kt")

fails = []


def check(name, ok, hint=""):
    if not ok:
        fails.append("[%s] %s" % (name, hint))


def read(p):
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        return f.read()


mgr = read(MGR_PATH)
policy = read(POLICY_PATH)
repair = read(REPAIR_PATH)
alias = read(ALIAS_PATH)

check("files-present", mgr is not None, "ExtensionManager.kt missing")
check("policy-file", policy is not None, "ExtensionLinkPolicy.kt missing (落点判定应独立可单测)")
check("repair-file", repair is not None, "ExtensionLinkRepair.kt missing (存量补链入口)")
check("alias-file", alias is not None, "ExtensionBinAliases.kt missing (声明名≠上游名的别名表)")

if policy:
    # A. 相对 target 的基准必须是链接父目录
    check(
        "policy-resolves-from-link-parent",
        "parentFile" in policy and "linkRel" in policy,
        "落点判定必须用 linkRel 的父目录解析相对 target；以扩展根为基准会把 "
        "bin/x -> ../lib/x/x 判成越界（issue #115 的根因）",
    )
    check(
        "policy-not-root-basis",
        "root.toPath().resolve(t)" not in policy.replace(" ", ""),
        "出现了 root.resolve(target) 形态 —— 这正是被修掉的错误基准",
    )
    # E. 仍要拒绝真越界
    check(
        "policy-still-guards-escape",
        "startsWith(rootPath)" in policy,
        "落点判定必须仍比较是否落在扩展根内（守卫不能被整个废掉）",
    )
    check(
        "policy-normalises",
        "normalize()" in policy,
        "必须 normalize，否则 .. 与重复分隔符能骗过前缀比较",
    )

if mgr:
    # B. 调用方必须剥 usr/ 前缀
    check(
        "caller-strips-usr-prefix",
        re.search(r"linkRel\s*=\s*job\.linkRel\.removePrefix\(\"usr/\"\)", mgr) is not None,
        "createLinks 必须把 job.linkRel 的 usr/ 前缀剥掉再判落点；否则父目录变成 "
        "<ext>/usr/bin，同一条链仍被判越界",
    )
    # C. 别名补链必须早于主程序存在性校验
    alias_pos = mgr.find("ExtensionBinAliases.apply")
    verify_pos = mgr.find("val missingBins = ext.bins.filterNot")
    check(
        "aliases-before-bin-verification",
        alias_pos > 0 and verify_pos > 0 and alias_pos < verify_pos,
        "别名补链必须在 missingBins 校验之前，否则 binutils 会被判「装失败」并删掉版本标记",
    )
    # D. 启动期存量补链
    check(
        "startup-heals-existing",
        "ExtensionLinkRepair.repairMissingBins" in mgr or "ExtensionLinkRepair.pendingRepairs" in mgr,
        "启动期必须补回存量扩展缺失的入口链（修判定只救将来的安装）",
    )
    check(
        "startup-replays-manifest",
        "replayManifest" in mgr,
        "安装期清单必须在启动期重放，否则「当时被守卫拦掉」的链接永远补不回来",
    )

if repair:
    check(
        "repair-checks-target-exists",
        "isFile" in repair,
        "补链前必须确认目标真的在盘上，否则产出悬空链（exists() 仍为 false，等于没修）",
    )
    check(
        "repair-uses-policy",
        "ExtensionLinkPolicy" in repair,
        "补链也必须过落点守卫（清单文件按不可信输入处理）",
    )

if alias:
    check(
        "alias-declares-binutils",
        "binutils" in alias and "ld.bfd" in alias and "gar" in alias,
        "binutils 的声明名（ar/nm/objdump/strip/ld）与上游名（gar/gnm/gobjdump/gstrip/ld.bfd）"
        "必须显式映射，否则 issue #116 的「Main executables missing (5)」永远修不好",
    )
    check(
        "alias-skips-missing-target",
        "target.isFile" in alias,
        "上游产物不存在时不许建别名（会变成悬空链）",
    )

print("-" * 68)
if fails:
    print("FAILED %d check(s):" % len(fails))
    for f in fails:
        print("  " + f)
    print("RESULT: FAILED")
    sys.exit(1)
print("checks passed")
print("RESULT: PASSED")
