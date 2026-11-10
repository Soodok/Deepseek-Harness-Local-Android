#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""bootstrap 失败时必须留下可诊断的日志（v1.6.19）。

## 为什么需要这个探针

`ensureBootstrapPackages()` 失败时原本只打一行：

    Log.w(TAG, "bootstrap failed (will retry next boot): " + r.combined().take(400))

而 `Result.combined()` 会 `trim()` 两路输出。实测（模拟器 dsh34，App 内）：

    W LinuxEnv: bootstrap failed (will retry next boot):            ← 后面全空

**看起来像"没有报错"**，实际是容器的 stdout/stderr 都为空（进程被信号杀死）。
这条空白日志把排查引向「没网/镜像站挂了」方向，而真实原因（模拟器上 App 进程
Seccomp:2/filters=1 导致容器 rc=139）完全看不出来。为此白查了两轮。

## 本探针守什么

A. 失败日志必须带 rc（否则 0B/0B 与「成功但无输出」无法区分）
B. 失败日志必须带 timedOut（超时与秒退是完全不同的故障）
C. 失败日志必须带两路输出长度（空输出 = 进程没跑起来，不是"没报错"）
D. 日志格式必须能区分 apk 与 apt 两条分支（各自 rc 语义不同）

## 反向验证

把日志改回 `+ r.combined().take(400)`，A/B/C 必须一起变红。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
MGR = os.path.join(
    ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine",
    "LinuxEnvironmentManager.kt",
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
    with open(MGR, encoding="utf-8") as f:
        src = strip_comments(f.read())

    # 定位 bootstrap 的 apk 分支（含 ensureBootstrapPackages 到 APT 分支之间）
    m = re.search(r"fun ensureBootstrapPackages\((.{0,3500}?)LinuxRootfsCatalog\.PackageManager\.APT",
                  src, re.S)
    ok(m is not None, "A0 bootstrch apk 分支可定位")
    body = m.group(1) if m else ""

    print("[A] 失败日志必须可诊断")
    ok(re.search(r"rc=\$\{r\.exitCode\}", body) is not None,
       "A1 带 rc（${r.exitCode}）")
    ok(re.search(r"timedOut=\$\{r\.timedOut\}", body) is not None,
       "A2 带 timedOut")
    ok(re.search(r"stdout=\$\{r\.stdout\.length\}B", body) is not None,
       "A3 带 stdout 长度")
    ok(re.search(r"stderr=\$\{r\.stderr\.length\}B", body) is not None,
       "A4 带 stderr 长度")
    # 关键反向守卫：不能只有 combined()
    ok(re.search(r'Log\.w\(\s*TAG,\s*"bootstrap failed \(will retry next boot\): " \+\s*r\.combined\(\)',
                 body) is None,
       "A5 没有退化成「只打 combined()」的旧写法")

    print("[B] 不要多余的 apk update")
    # 假根因：以为空索引必失败而加了 update。实测 apk add 会自己 fetch。
    # 这里守「不要在 bootstrap 里多一次联网」。
    upd = re.search(r'args\s*=\s*listOf\("update"\)', body)
    ok(upd is None, "B1 bootstrap 不额外跑 apk update（apk add 自己会 fetch 索引）")

    print("[C] APT 分支保持原样")
    # 合并 feat/linux-workspace（2026-11-09）：APT 分支的实参从 BOOTSTRAP_PACKAGES
    # 改成 pkgs（= BOOTSTRAP_PACKAGES + option.flavor.extraPackages）—— 否则切到
    # DEV / GUI 形态时额外包永远不装。仍必须走两步法 installPackagesApt。
    ok(re.search(r"installPackagesApt\(option, pkgs\)", src) is not None,
       "C1 APT 仍走两步法 installPackagesApt")
    ok(re.search(r"val pkgs = BOOTSTRAP_PACKAGES \+ option\.flavor\.extraPackages", src)
       is not None,
       "C2 pkgs 含 flavor 额外包（否则 DEV/GUI 形态装了个空）")

    print("")
    if failed:
        print("FAILED (%d/%d)" % (failed, checks))
        return 1
    print("All passed (%d checks)" % checks)
    return 0


if __name__ == "__main__":
    sys.exit(main())
