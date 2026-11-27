#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守「属主自愈判据自己抛异常、被外层 runCatching 吞成 false」（真机事故 2026-11-27）。

## 现场

真机 PLZ110 上 `dsh-home/storages/workspace.json` 被 root 跑过之后变成
`root:root 600`，App（u0_a501 / uid 10501）读它直接 `Permission denied`。
启动日志里 `home-owner-check 0 ms` 一路绿灯，没有任何告警 —— 看起来是
「检查通过」，实际是**判据自己先抛了异常**：

```kotlin
// 旧代码
private fun hasUnreadableEntries(ctx: Context, roots: List<File>): Boolean {
    ...
    probes += File(roots[1], ".runtime-version")   // ← roots.size == 1 时越界
}

// 唯一调用点
if (hasUnreadableEntries(ctx, listOf(home))) { ... }   // 只传 1 个元素

// 外层
return runCatching { ... }.getOrDefault(false)          // 异常被吞 → 恒 false
```

所以**整段属主自愈**（自读探测 / 特权 `find -not -user` / pnpm 现场探针 /
`hasForeignOwnedFiles`）永远不会执行。手工 `chown u0_a501:u0_a501` 之后重启
即恢复正常 —— 这条正是反证。

## 为什么这类缺陷最难发现

判据抛异常被 `runCatching{}.getOrDefault(false)` 吃成 false，行为与「检查通过」
**完全一样**：日志没有、计时是 0ms、状态机前进。用户看到的是下游症状
（插件装不上 / 工作区空白 / 会话列表空），而维护页说「没有发现异常」。

## 本探针守什么

A 段：`hasUnreadableEntries` 内部**不许**出现任何可能越界的下标访问
      （`roots[1]` / `roots.last()` / `[roots.size - 1]` 之外的花样）。
B 段：engine 路径必须从 `home.parentFile` 推导（`dshHome` 恒在 `filesDir` 下，
      engine 是它的兄弟）。
C 段：`storages/` 与其下 `*.json` 必须被**逐文件**探（目录可列 ≠ 文件可读；
      注册表读不出来正是这次事故的直接受害者）。
D 段：判据里 `runCatching` 的兜底值不许把「读不动」当成「没问题」的语义
      （只允许对单个探针 runCatching，不许把整段逻辑包成一层）。

跑法：python scripts/probes/_test_privilege_ownership_probe.py
"""

import io
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PRIV = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/Privilege.kt")

fails = []


def chk(cond, msg):
    if not cond:
        fails.append(msg)
        print("FAIL  " + msg)
    else:
        print("ok    " + msg)


def read(p):
    return io.open(p, encoding="utf-8").read()


def strip_comments(s):
    """剥 Kotlin 的 `/* */` 与 `//`；认字符串字面量（`"//"` 是代码不是注释）。"""
    out = []
    i, n = 0, len(s)
    in_str = False
    while i < n:
        c = s[i]
        if in_str:
            out.append(c)
            if c == chr(92) and i + 1 < n:
                out.append(s[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == '/' and i + 1 < n and s[i + 1] == '*':
            j = s.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '/' and i + 1 < n and s[i + 1] == '/':
            if out and out[-1] == ':':
                out.append(c)
                out.append(s[i + 1])
                i += 2
                continue
            j = s.find(chr(10), i)
            i = n if j < 0 else j
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def fn_body(src, name):
    nl = chr(10)
    m = re.search(r"(?:^|" + nl + r")[ ]{4}(?:private |internal |public )?fun "
                  + re.escape(name) + r"\b", src)
    if not m:
        return ""
    rest = src[m.start():]
    nxt = re.search(nl + r"[ ]{4}(?:private |internal |public )?(?:fun|val|var|object|data class) ",
                    rest[1:])
    return rest if not nxt else rest[:nxt.start() + 1]


src = read(PRIV)
nc = strip_comments(src)

print("[A] hasUnreadableEntries：判据内部不许有越界下标访问")

body = fn_body(nc, "hasUnreadableEntries")
outer_scan = body
chk(body != "", "A1  hasUnreadableEntries 存在且能切出函数体")
chk(re.search(r"roots\[1\]", body) is None,
    "A1b 函数体里没有 roots[1]（唯一调用点只传 listOf(home)，越界会被外层吞成 false）")
chk(re.search(r"roots\[\s*1\s*\]", body) is None, "A1c 换个写法（roots[ 1 ]）也不许")
chk("roots.last()" not in body,
    "A1d 不用 roots.last()（长度不定时同样会抛）")
# 明确：允许 roots[0]，但必须先判空
chk("roots.isEmpty()" in body or "roots.size" in body or "roots.isNotEmpty()" in body,
    "A2  函数开头判空了（roots[0] 之前必须有空集判据）")
chk(re.search(r"if \(roots\.isEmpty\(\)\) return", body) is not None,
    "A2b 空集直接返回（不是靠后面的 for 循环隐式跳过）")
chk(len(re.findall(r"roots\[0\]", body)) >= 1, "A2c roots[0] 只作 home 使用")

print()
print("[B] engine 路径必须从 home.parentFile 推导")

chk('java.io.File(home.parentFile, "engine")' in body,
    "B1  engine 由 home.parentFile 推导（dshHome 恒在 filesDir 下，engine 是兄弟）")
chk('".runtime-version"' in body, "B1b 仍然探 engine/.runtime-version（启动链路会读它）")
chk('java.io.File(home, "AGENTS.md")' in body, "B1c home/AGENTS.md 仍被探")

print()
print("[C] storages/ 与 *.json 必须逐文件探（这次事故的直接受害者）")

chk('"storages"' in body, "C1  探 storages 目录")
chk('endsWith(".json")' in body,
    "C1b 逐个探 *.json（目录能列出 ≠ 文件能读；root:600 的注册表就在这一层）")
chk("listFiles()" in body, "C1c 用 listFiles 枚举（不是硬编码 workspace.json 一个名字）")
# 注册表文件名必须来自这里或别处能覆盖到；判据是「别写死只有 workspace.json」
chk(body.count("workspace.json") == 0,
    "C1d 不写死单个文件名（storages 下可能有别的 json，也要能检出）")

print()
print("[D] runCatching 的语义：只许包单个探针，不许包整段")

# 单个探针的 try
chk(re.search(r"for \(p in probes\)", body) is not None, "D1  逐探针循环存在")
chk("p.listFiles() != null" in body,
    "D1b 目录判据是「能否列出」（无 x/r 位返回 null）")
chk("p.inputStream().use { it.read() }" in body,
    "D1c 文件判据是「能否真正读出内容」（不是 canRead()）")
# 循环内对单个探针 runCatching 是允许的；但不许在函数最外层再包一层把异常吞掉
# ★这条判据一开始写宽了：`storages` 枚举那圈的 runCatching 是**允许**的
#   （它只保护 `listFiles()` 这一步，且不决定「关键项是否被探」；即便抛，
#    前面已经加进去的 engine/.runtime-version、AGENTS.md 等仍在列表里）。
#   真正要禁的是：**在函数最外层把可能抛的 key access 包进 runCatching**。
#   所以判据只查：`roots[1]` 这类下标访问没有出现在任何 runCatching 块里。
chk(re.search(r"runCatching[^{]*\{[^}]*roots\[", outer_scan, re.S) is None,
    "D2  roots[...] 下标访问没有出现在 runCatching 块里（那会把越界吞成 false）")

# 调用点：不许把 hasUnreadableEntries 包在会被吞的 runCatching 里 —— 它已经在
# dshHomeNeedsOwnershipFix 的 runCatching 内层，函数自己必须不抛。
i_fn = nc.find("fun dshHomeNeedsOwnershipFix")
chk(i_fn >= 0, "D3  dshHomeNeedsOwnershipFix 存在")
if i_fn >= 0:
    fb = nc[i_fn:i_fn + 2000]
    chk("hasUnreadableEntries(ctx, listOf(home))" in fb,
        "D3b 调用点签名仍是 listOf(home)（改动只发生在函数体内，调用方无需变）")

print()
print("[E] 回归记录：这条缺陷曾在真机上被误判为「检查通过」")

chk("IndexOutOfBoundsException" in src,
    "E2  代码里写明了这个异常名（后人一眼能认出症状来源）")
chk("恒返回 false" in src or "getOrDefault(false)" in src,
    "E2b 注释里点明「被外层 getOrDefault(false) 吞掉」这条机制")

print()
if fails:
    print("FAILED (%d)" % len(fails))
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("All privilege-ownership-probe checks passed.")
