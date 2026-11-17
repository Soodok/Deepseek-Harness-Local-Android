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

## 两种修法，第二种才真的通用

**第一次修（已够用但有前提）**：经典 `[w]` 技巧。

    $ sh -c 'echo BEFORE; pkill -f "[w]lshot .* --loop"; echo AFTER'
    BEFORE
    AFTER

它之所以有效，是因为同一段命令里**只出现**模式文本 `[w]lshot .* --loop`，
而正则 `[w]lshot` 匹配的是 `wlshot`（不含方括号），这段字面量在 cmdline 里
并不存在。

**但方括号技巧会失效**（2026-11-14 真机实测，rc=143 自杀）：只要同一段
命令里还出现**去掉方括号后的真实路径**，正则就会命中它。例如：

    pkill -f [x]11frsrv 2>/dev/null
    nohup /x11frsrv 1 2 3 &

这里的 cmdline 同时含 `[x]11frsrv`（模式文本）与 `/x11frsrv`（真的启动命令），
正则 `[x]11frsrv` 在后者里能匹配到 `x11frsrv` → sh 把自己杀掉，后面的
Xvfb / xterm / 帧服务全都不执行，界面无任何报错。

**现在的做法**：行首锚定 `^/x11frsrv`。`pkill -f` 是 ERE，`^` 匹配整条
cmdline 的开头；而这条 shell 的 cmdline 以运行器路径开头，永不误伤。
停旧进程另外用 pidfile 兜一层（`for f in $stateDir/dsh-*.pid`）。

## 本探针守什么（语义判据，不是语法教条）

A. 对每一处 `pkill -f <模式>`，把模式当成 ERE，在**它所在的 shell 脚本文本**
   上搜一次 —— 有命中就说明「这条命令会匹配到自己」，无论写法是裸写、
   `[x]` 技巧还是别的花样。这是对真实行为的直接模拟，不依赖某种写法。
B. 该清理的进程都得被清理到（防「修掉自杀也顺手删了清理」）。
C. 裸写（不带引号/引号直接写进程名）额外单独守一遍。

## 反向验证

- 把任一处 `^/x11frsrv` 改回 `[x]11frsrv` → A 段红（块内有 `/x11frsrv` 字面量）。
- 把任一处改成裸写 `framesrv` → A 段与 C 段同时红。
- 把某条清理整条删掉 → B 段红。
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
    """去掉 Kotlin 的 // 与 /* */，但保留字符串与 raw string 内容。"""
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


def shell_blocks(src):
    """返回所有 Kotlin raw string（三引号）块的内容区间 [(start, end), ...]。

    pkill 只在 raw string 里执行（脚本通过 sh -c 跑），因此「它所在的块」
    就是那条 shell 的 cmdline 近似 —— 块文本会原样成为 sh -c 的参数。
    """
    blocks = []
    i = 0
    while True:
        s = src.find('"""', i)
        if s < 0:
            break
        e = src.find('"""', s + 3)
        if e < 0:
            break
        blocks.append((s + 3, e))
        i = e + 3
    return blocks


def block_of(blocks, pos, src):
    for (s, e) in blocks:
        if s <= pos <= e:
            return src[s:e]
    return None


def parse_pattern(rest):
    """从 `pkill -f ` 之后取出模式（支持 'x'、"x"、裸 token）。"""
    q = re.match(r"'([^']*)'", rest)
    if q:
        return q.group(1)
    d = re.match(r'"([^"]*)"', rest)
    if d:
        return d.group(1)
    tok = re.match(r"([^\s;\"']+)", rest)
    return tok.group(1) if tok else None


def main():
    with open(ACT, encoding="utf-8") as f:
        src = strip_comments(f.read())

    blocks = shell_blocks(src)

    print("[A] 每一处 pkill -f 的模式都不能匹配到自身所在的 shell 命令")
    hits = []          # [(pattern, matched_text_span, block_index)]
    for m in re.finditer(r"pkill\s+-f\s+", src):
        pat = parse_pattern(src[m.end():])
        if not pat:
            continue
        blk = block_of(blocks, m.start(), src)
        if blk is None:
            # 不在任何 raw string 里：退回整源近似（保守）
            blk = src
        try:
            mm = re.search(pat, blk)
        except re.error as exc:      # 模式本身不是合法 ERE 也是缺陷
            hits.append((pat, "正则非法: %s" % exc, -1))
            continue
        if mm:
            hits.append((pat, mm.group(0), -1))

    ok(len(hits) >= 0, "A0 抓取 pkill -f 模式（%d 处，其中自匹配 %d 处）"
       % (len(re.findall(r"pkill\s+-f\s+", src)), len(hits)))
    ok(not hits,
       "A1 无自匹配（模式不得出现在同一条 shell 命令里）；问题项: %s"
       % (["%s -> 命中 %r" % (p, t) for (p, t, _) in hits] or "无"))

    print("[B] 该清理的进程都还在被清理（防「修掉自杀顺手删了清理」）")
    all_pats = [p for p in (parse_pattern(src[m.end():])
                            for m in re.finditer(r"pkill\s+-f\s+", src)) if p]
    for name in ("inputd", "framesrv", "x11frsrv", "wlshot", "Xvfb"):
        ok(any(name in p for p in all_pats), "B %s 有对应的 pkill 清理" % name)

    # B2 逐块守：块内起的事件，若在别处也被清理，那本块必须先清一次。
    # 这条抓的是「把自杀那行删掉」这种最省事的假修 —— 删掉后两轮实例会抢同一
    # 个共享文件 / 同一 display，表现是 seq 乱跳或 Xvfb 起不来，都不是报错。
    # 只对「本文件里确实会被清理」的进程要求，避免把 xterm/sway 这类历史遗留的
    # 未清理项算成新缺陷（它们在本轮之前就没有清理，属独立问题）。
    stray = []
    texts = [src[a:b] for (a, b) in blocks]
    for bi, blk in enumerate(texts):
        started = set()
        for m in re.finditer(r"nohup\s+(?:env\s+\S+\s+)?([^\s;&>]+)", blk):
            started.add(os.path.basename(m.group(1)))
        if not started:
            continue
        killed_here = [p for p in (parse_pattern(blk[m.end():])
                                   for m in re.finditer(r"pkill\s+-f\s+", blk)) if p]
        for nm in sorted(started):
            if not any(nm in p for p in all_pats):
                continue                     # 全程无清理 → 历史遗留，不计
            if not any(nm in p for p in killed_here):
                stray.append("block%d 起了 %s 但本块没清" % (bi, nm))
    ok(not stray, "B2 每个块起的进程都先清了一次；问题项: %s" % (stray or "无"))

    print("[C] 不许有裸写（会被 A 段抓到，这里单独给出更可读的原因）")
    for bare in ("pkill -f framesrv", 'pkill -f "framesrv',
                 "pkill -f 'framesrv", 'pkill -f "wlshot', "pkill -f 'wlshot"):
        ok(bare not in src, "C 无裸写 %s" % bare)

    print("")
    if failed:
        print("FAILED (%d/%d)" % (failed, checks))
        return 1
    print("All passed (%d checks)" % checks)
    return 0


if __name__ == "__main__":
    sys.exit(main())
