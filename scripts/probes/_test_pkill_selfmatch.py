#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""pkill -f 的模式绝不能匹配到运行它的 shell 自身。

## 缺陷现象（已实测复现）

App 的 `captureCompositor()` 起帧服务前要清上一轮遗留进程，原来写：

    pkill -f 'wlshot .* --loop' 2>/dev/null
    nohup env ... /wlsrc/wlshot $shared 5000 --loop 8 >/tmp/wlshot.log 2>&1 &
    ...等首帧...
    echo WLSHOT_STARTED

`pkill -f` 匹配的是**整条 cmdline**，而这条 shell 自己的 cmdline 里就含
`wlshot .* --loop` 这串字面量 —— 于是 pkill 把自己杀掉了。实测：

    $ sh -c 'echo BEFORE; pkill -f "wlshot .* --loop"; echo AFTER'
    BEFORE
    Terminated                    ← AFTER 永远不打印

后果：后面 nohup 起帧服务、等首帧、echo WLSHOT_STARTED **全都不执行**。
用户点「抓合成画面」看起来什么也没发生，而且没有任何报错 —— 又一个静默失败。

改用经典 `[w]` 技巧后正常：

    $ sh -c 'echo BEFORE; pkill -f "[w]lshot .* --loop"; echo AFTER'
    BEFORE
    AFTER

## 本探针守什么

A. LinuxActivity 里所有 pkill -f 的模式，首字符必须写成 `[x]` 形式
B. 不许有其他形式的自匹配（裸进程名 + -f、或 pattern 与命令同时出现）
C. 三处调用点都要守到（startFrameServer / captureCompositor / stopFrameServer）

## 反向验证

把任意一处 `[f]ramesrv` 改回 `framesrv`，A 段立刻变红。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ACT = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                   "LinuxActivity.kt")

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
    i, n = 0, len(src)
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
        if src.startswith("//", i):
            j = src.find(chr(10), i)
            i = n if j < 0 else j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def strip_shell_comments(src):
    """Kotlin raw string 里的 `#` 行是 shell 注释，但 strip_comments 不认。

    探针要在「真实执行的 shell 命令」上判断，注释里的示例文本（如
    `pkill -f "wlshot .* --loop"` 这种反面教材）不能算违规。
    """
    out = []
    for ln in src.split("\n"):
        if ln.lstrip().startswith("#"):
            continue
        out.append(ln)
    return "\n".join(out)


def main():
    with open(ACT, encoding="utf-8") as f:
        src = strip_shell_comments(strip_comments(f.read()))

    print("[A] pkill -f 模式必须防自匹配")
    # 抓出所有 pkill -f 后面的模式：带引号或不带引号都要抓到。
    # 不带引号的形态是 `pkill -f [f]ramesrv 2>/dev/null`（在字符串拼接里），
    # 我第一版只认引号包起来的，于是漏掉了这一处 → B 段假红。
    flat = []
    for m in re.finditer(r"pkill\s+-f\s+", src):
        rest = src[m.end():]
        q = re.match(r"'([^']*)'", rest)
        d = re.match(r'"([^"]*)"', rest)
        if q:
            flat.append(q.group(1))
        elif d:
            flat.append(d.group(1))
        else:
            tok = re.match(r"([^\s;\"]+)", rest)
            if tok:
                flat.append(tok.group(1))

    ok(len(flat) >= 3, "A0 找到 ≥3 处 pkill -f（实际 %d）" % len(flat))

    bad = []
    for p in flat:
        # 合法形式：[x]yz 或 [x]yz .* --abc
        if not re.match(r"^\[[A-Za-z]\]", p):
            bad.append(p)
    ok(not bad, "A1 全部写成 [x] 形式（防自杀）；不合规: %s" % (bad or "无"))

    print("[B] 已知模式逐个守住")
    for name in ("[f]ramesrv", "[w]lshot"):
        hit = any(p.startswith(name) for p in flat)
        ok(hit, "B %s 存在" % name)

    print("[C] 不许有裸写")
    for bare in ("pkill -f framesrv", 'pkill -f "wlshot', "pkill -f 'wlshot"):
        ok(bare not in src, "C 无裸写 %s" % bare)

    print("")
    if failed:
        print("FAILED (%d/%d)" % (failed, checks))
        return 1
    print("All passed (%d checks)" % checks)
    return 0


if __name__ == "__main__":
    sys.exit(main())
