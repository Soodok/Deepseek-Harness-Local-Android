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


def bootstrap_fn_body(src):
    """取 ensureBootstrapPackages 的完整函数体（到同缩进的下一个 fun 为止）。

    这里刻意不用字符窗口：函数头附近的注释和分支体会持续增长，写死
    `.{0,3500}?` 迟早会把真正的分支甩出窗口（2026-11-13 就发生过一次 ——
    66f28ddb 在函数头加了「首装前修符号链接」块，窗口随即停在那个 if 上）。
    """
    m = re.search(r"\n    fun ensureBootstrapPackages\s*\(", src)
    if m is None:
        return None
    tail = src[m.end():]
    nxt = re.search(r"\n    fun\s+", tail)
    return tail[:nxt.start()] if nxt else tail


def apk_branch_of(fn):
    """在函数体里定位 when(option.pkgManager) 的 APK 分支正文。

    先找 `val ok = when (option.pkgManager)` 这个分发点（用最后一个匹配 ——
    函数开头可能还有别的同名 when），再从它的 `PackageManager.APK ->` 切到
    同层 `PackageManager.APT ->`。两边都是结构锚点，中间加多少代码都不影响。
    """
    if not fn:
        return None
    anchors = [m.start() for m in re.finditer(r"val ok = when \(option\.pkgManager\)", fn)]
    if not anchors:
        return None
    start = anchors[-1]
    apk = fn.find("LinuxRootfsCatalog.PackageManager.APK ->", start)
    if apk < 0:
        return None
    apt = fn.find("LinuxRootfsCatalog.PackageManager.APT ->", apk)
    if apt < 0:
        return None
    # 从分支箭头往前挪到 `->` 之后，把 {` 与缩进一并带上
    return fn[apk:apt]


def failure_log_of(branch):
    """抽出 apk 分支里那条 bootstrap 失败日志的完整语句正文。

    取 `Log.w(` 起始、括号配平为止，这样断言只作用于这一条日志，
    不会被分支里别处的 rc= 污染。
    """
    if not branch:
        return None
    m = re.search(r"Log\.w\(\s*TAG,\s*\"bootstrap failed \(will retry next boot\)", branch)
    if m is None:
        return None
    i = branch.find("(", m.start() + 3)   # Log.w( 的左括号
    if i < 0:
        return None
    depth = 0
    for k in range(i, len(branch)):
        c = branch[k]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return branch[m.start():k + 1]
    return None


def main():
    with open(MGR, encoding="utf-8") as f:
        src = strip_comments(f.read())

    # 定位 bootstrap 的 apk 分支：结构定位（when 分发 -> APK 分支 -> APT 分支）
    fn = bootstrap_fn_body(src)
    ok(fn is not None, "A0a ensureBootstrapPackages 可定位")
    body = apk_branch_of(fn) or ""
    ok(bool(body) and len(body) > 400,
       "A0b bootstrap apk 分支可定位（when 分发里的 APK -> 到 APT -> 之间，实测 %dB）" % len(body))

    print("[A] 失败日志必须可诊断")
    # 四个字段必须落在**同一条** "bootstrap failed (will retry next boot)" 日志里。
    # 早先这条断言是对整个 apk 分支搜 rc= / timedOut=，结果分支末尾
    # "apk install incomplete: rc=..." 那句也能喂饱 A1 —— 变异测试时
    # 主日志已经被掏空、A1 却仍绿。收窄到日志语句本身才不会假绿。
    log = failure_log_of(body)
    ok(log is not None, "A0c 找到 bootstrap 失败日志语句")
    log = log or ""
    ok(re.search(r"rc=\$\{r\.exitCode\}", log) is not None,
       "A1 带 rc（${r.exitCode}）")
    ok(re.search(r"timedOut=\$\{r\.timedOut\}", log) is not None,
       "A2 带 timedOut")
    ok(re.search(r"stdout=\$\{r\.stdout\.length\}B", log) is not None,
       "A3 带 stdout 长度")
    ok(re.search(r"stderr=\$\{r\.stderr\.length\}B", log) is not None,
       "A4 带 stderr 长度")
    # 关键反向守卫：不能只有 combined()（trim 会把 0B/0B 变成空串）
    ok(re.search(r'"(?:bootstrap failed[^"]*)?"\s*\+\s*r\.combined\(\)', log) is None,
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
