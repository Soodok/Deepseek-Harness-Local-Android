#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""冷启前置产物的完整判据（v1.6.17）。

## 为什么需要这个探针

`LinuxEngineLauncher.readiness()` 是冷启唯一入口（`EngineSupervisor` 走
`EngineModeSelector.resolve()` → `launcher.readiness()`），它原本只检查
一件事：rootfs 里有没有 node。其余依赖全靠「部署阶段应该已经装好」这个
假设 —— 而这个假设踩了三次同一个坑：

1. `installCompatShims`（rg + /system/bin/sh shim）挂错位置；
2. `writeDistroConfig`（hosts/resolv.conf）挂错位置；
3. `BOOTSTRAP_PACKAGES`（bash/curl/ca-certificates）根本没在冷启路径上。

第 3 次的危害最隐蔽：门脚本（32 个）全部 `exec /bin/bash`，容器里没 bash
就全废，而 readiness() 只查 node → 判定 Ready → spawn → 引擎起来后每个
bash 工具、每个 gate 都失败，但 UI 上完全正常。

更糟的是 bootstrap 标记文件**成功与失败都落**：
  - 成功：`bash,curl,ca-certificates`
  - 失败：`attempted:bash,curl,ca-certificates`
而 `ensureBootstrapPackages` 只判 `mark.isFile` 就早退 —— 于是「尝试过但
失败」永久变成「已就绪」，用户点「修复」是唯一出路（而 force 会 purge
整个 rootfs，代价 8.5 MB 重解）。

## 本探针守什么

A. readiness() 的判据必须覆盖 bash 与 curl，不能只有 node
B. 缺文件时报的是可操作文案（linux_err_need_bash），不是笼统失败
C. 标记语义必须区分 attempted / 成功
D. 探针文件清单与 BOOTSTRAP_PACKAGES 必须对齐（加包不更新清单 = 假绿）

## 反向验证

把 readiness() 里 `gateDeps` 那段的 `missing.isNotEmpty()` 改成 `false`
（等于恢复旧行为），A 段必须变红。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
LAUNCHER = os.path.join(ENGINE, "LinuxEngineLauncher.kt")
MANAGER = os.path.join(ENGINE, "LinuxEnvironmentManager.kt")
RES = os.path.join(ROOT, "app", "src", "main", "res")

checks = 0
failed = 0


def ok(cond, msg):
    global checks, failed
    checks += 1
    if cond:
        print("  ok   %s" % msg)
    else:
        failed += 1
        print("  FAIL %s" % msg)


def strip_comments(src):
    """去掉 // 与 /* */ 注释，保留字符串与原始字符串。

    不剥注释会误判：源码注释里大量出现 /bin/bash、/sbin/apk 这类例子，
    也有被废弃的旧写法说明。
    """
    out = []
    i = 0
    n = len(src)
    in_str = False
    in_raw = False
    bs = chr(92)
    while i < n:
        c = src[i]
        if in_raw:
            if src.startswith('"""', i):
                in_raw = False
                out.append('"""')
                i += 3
            else:
                out.append(c)
                i += 1
            continue
        if in_str:
            if c == bs:
                out.append(src[i:i + 2])
                i += 2
                continue
            if c == '"':
                in_str = False
            out.append(c)
            i += 1
            continue
        if src.startswith('"""', i):
            in_raw = True
            out.append('"""')
            i += 3
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if src.startswith('//', i):
            j = src.find(chr(10), i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def main():
    with open(LAUNCHER, encoding="utf-8") as f:
        launcher = strip_comments(f.read())
    with open(MANAGER, encoding="utf-8") as f:
        manager = strip_comments(f.read())

    print("[A] readiness() 的前置产物判据")
    ok(re.search(r'readiness\(\)', launcher) is not None, "A0 readiness 函数存在")
    # bash 必须在判据里，且是 rootfs 相对路径
    ok(re.search(r'"bin/bash"\s*to\s*R\.string\.', launcher) is not None,
       "A1 bash 纳入 readiness 判据（带可操作文案）")
    ok(re.search(r'"usr/bin/curl"\s*to\s*R\.string\.', launcher) is not None,
       "A2 curl 纳入 readiness 判据")
    # 必须有「缺了就返回 NeedsPackages」的分支，不能只算不用
    # A3/A4：分支必须真正生效 —— 只查「有 if 有 return」会假通过：
    # 实测把条件改成 `if (false && missing.isNotEmpty())` 时，
    # 纯结构断言仍然全绿。这里额外要求条件里没有恒假常量。
    ok(re.search(r"if\s*\(missing\.isNotEmpty\(\)\)\s*\{", launcher) is not None,
       "A3 条件是 missing.isNotEmpty()（未被短路常量包住）")
    ok(re.search(r"false\s*&&\s*missing\.isNotEmpty|missing\.isNotEmpty\(\)\s*&&\s*false",
                 launcher) is None,
       "A3b 条件里没有恒假短路（防假通过）")
    ok(re.search(r"missing\.isNotEmpty\(\)\)\s*\{(.{0,300}?)NeedsPackages", launcher, re.S) is not None,
       "A4 分支确实返回 NeedsPackages")
    # 判据必须是静态文件检查，不能起容器进程
    ok("command -v node" not in launcher,
       "A5 不用 command -v 探测（避免每次冷启多一次 fork）")

    print("[B] 文案可操作性")
    for loc in ["values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"]:
        p = os.path.join(RES, loc, "strings.xml")
        with open(p, encoding="utf-8") as f:
            xml = f.read()
        ok('name="linux_err_need_bash"' in xml, "B %s 有 linux_err_need_bash" % loc)

    print("[C] bootstrap 标记语义")
    ok(re.search(r'"attempted:"', manager) is not None,
       "C0 失败路径写 attempted: 前缀")
    ok(re.search(r'attemptedOnly', manager) is not None,
       "C1 区分 attempted 与成功标记")
    ok(re.search(r'attemptedOnly\s*\)\s*return|if\s*\(!attemptedOnly\)\s*return', manager) is not None,
       "C2 成功标记仍无条件早退（不代用户恢复被删文件）")
    ok(re.search(r"stillMissing", manager) is not None,
       "C3 attempted 时用 stillMissing 决定是否重试")
    # 关键反向守卫：不能写成「attempted 也直接 return」
    ok(re.search(r'attemptedOnly\)\s*\{?\s*\n?\s*val\s+stillMissing', manager) is not None or
       re.search(r"if\s*\(!attemptedOnly\)\s*return", manager) is not None,
       "C4 attempted 不直接早退")

    print("[D] 包清单与探测文件对齐")
    m = re.search(r"val BOOTSTRAP_PACKAGES\s*=\s*listOf\(([^)]*)\)", manager)
    ok(m is not None, "D0 BOOTSTRAP_PACKAGES 可解析")
    pkgs = []
    if m:
        pkgs = [x.strip().strip('"') for x in m.group(1).split(",") if x.strip()]
    ok("bash" in pkgs, "D1 包清单含 bash")
    ok("curl" in pkgs, "D2 包清单含 curl")
    ok("ca-certificates" in pkgs, "D3 包清单含 ca-certificates")
    p = re.search(r"BOOTSTRAP_PROBE_FILES\s*=\s*listOf\(([^)]*)\)", manager)
    ok(p is not None, "D4 BOOTSTRAP_PROBE_FILES 可解析")
    if p:
        files = re.findall(r'"([^"]+)"', p.group(1))
        ok(any(x.endswith("bin/bash") for x in files), "D5 探测清单含 bash")
        ok(any(x.endswith("bin/curl") for x in files), "D6 探测清单含 curl")
        ok(any("ca-certificates.crt" in x for x in files), "D7 探测清单含证书合并文件")
        # 数量对齐：清单里的每个包都该有对应探测（用文件数 >= 包数近似守）
        ok(len(files) == len(pkgs),
           "D8 探测文件数(%d) 与包数(%d) 对齐" % (len(files), len(pkgs)))

    print("")
    if failed:
        print("FAILED (%d/%d)" % (failed, checks))
        return 1
    print("All passed (%d checks)" % checks)
    return 0


if __name__ == "__main__":
    sys.exit(main())
