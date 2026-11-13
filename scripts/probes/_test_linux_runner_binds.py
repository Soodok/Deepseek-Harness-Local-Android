#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""执行器容器与引擎容器的 bind 视图一致性（v1.6.18）。

## 为什么需要这个探针

同一个 App 里有两条独立的「起容器」路径：

  引擎启动：LinuxEngineLauncher.buildEngineArgv() → buildBinds() 十几条挂载
  执行器：  LinuxContainerRunner.shell()        → EngineConfig.linuxContainerArgv()

后者原来只返回三个参数（runner / rootfs / interp），**一条 bind 都没有**。
于是「按宿主绝对路径传参」在两条路径上一条通一条不通，而调用方并不知道
自己走在哪条上：

  LinuxActivity.startFrameServer() 传 /data/data/<pkg>/files/dshframe.bin
  → 引擎路径能写（有 bind）
  → runner.shell() 路径容器里根本没有 /data/data → 写失败

实测症状（模拟器 dsh34）：
  captureCompositor() 返回 WLSHOT_NOSTART
  /tmp/wlshot.log: FAIL open /data/data/app.dsh.mobile/files/dshframe.bin:
                   No such file or directory
而产线注释还写着「该目录已由 buildBinds 映射进容器」—— 那句话对 shell()
这条路从来不成立。这是「注释当真」的典型：结构上都在，语义上没接上。

## 本探针守什么

A. linuxContainerArgv 必须带 /data/data 的 -m
B. 参数顺序必须合法：runner [-m g:h]... rootfs interp（-m 必须在 rootfs 前）
C. 没有 /data/data 目录时要安静退化（不能抛异常，也不能挂个不存在的路径）
D. 不能整表复制 buildBinds（两张表迟早分叉）

## 反向验证

把 `listOf("-m", "/data/data:/data/data")` 改成 `emptyList()`，A 段必须变红。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CFG = os.path.join(
    ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EngineConfig.kt"
)

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
    out = []
    i = 0
    n = len(src)
    in_str = in_raw = False
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
    with open(CFG, encoding="utf-8") as f:
        src = strip_comments(f.read())

    print("[A] linuxContainerArgv 必须给执行器挂 /data/data")
    m = re.search(r"fun linuxContainerArgv\((.{0,1200}?)\n    \}", src, re.S)
    ok(m is not None, "A0 linuxContainerArgv 可定位")
    body = m.group(1) if m else ""

    ok('"/data/data:/data/data"' in body, "A1 带 /data/data 同名挂载")
    ok(re.search(r'listOf\(\s*"-m"\s*,\s*"/data/data:/data/data"\s*\)', body) is not None,
       "A2 -m 与挂载串成对（缺 -m 会被当成 rootfs 路径）")

    print("[B] 参数顺序：runner [-m g:h]... rootfs interp")
    # runner 必须先出现
    ok(re.search(r"return\s+listOf\(runner\.absolutePath\)\s*\+", body) is not None,
       "B1 runner 在最前")
    ok(re.search(r"\+\s*listOf\(rootfs\.absolutePath,\s*interp\)", body) is not None,
       "B2 rootfs 与 interp 在 bind 之后（顺序不能反）")

    print("[C] 目录不存在时安静退化")
    ok(re.search(r"priv\.isDirectory", body) is not None,
       "C1 用 isDirectory 判断，不硬挂不存在的路径")
    ok(re.search(r"else\s+emptyList\(\)", body) is not None,
       "C2 退化路径返回空列表（不抛异常）")

    print("[D] 不与 buildBinds 整表复制")
    ok("buildBinds()" not in body,
       "D1 执行器不调用 buildBinds（两张表会分叉）")
    # 挂载条数应保持精简（整表复制会把 /ws /dsh 等用户工作区一起挂进来）
    #
    # v1.7.8：/eng 从「不该出现」变成「必须出现」—— 门脚本（scr / dev / loop…）
    # 装在 engineRoot/bin，执行器这条路上容器里原来既没有 /eng 也没有 /eng/bin
    # 在 PATH 上，于是 `dev linux exec "scr dump"` 一律 127，而引擎自己跑同一
    # 命令却正常。用户工作区（/ws、/dsh）仍不该由执行器负责。
    ok('"/eng:"' in body or '"/eng"' in body,
       "D2 挂 /eng（门脚本在 engineRoot/bin，不挂则 dev linux exec 一律 127）")
    for key in ['"/ws"', '"/dsh"']:
        ok(key not in body, "D3 不含 %s（那是引擎表的职责）" % key)

    print("")
    if failed:
        print("FAILED (%d/%d)" % (failed, checks))
        return 1
    print("All passed (%d checks)" % checks)
    return 0


if __name__ == "__main__":
    sys.exit(main())
