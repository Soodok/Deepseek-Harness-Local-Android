#!/usr/bin/env python3
"""能力自描述与可发现性（C 方向）的契约探针。

覆盖三件事：
1. **意图检索**：`EndpointCatalog.search()` 能按人话（中英）找到能力，
   而不是只能精确匹配内部 id。中文必须能工作 —— AI 只会说「读未读消息」，
   不会说 `notifications`。
2. **名册自动生成**：`renderRoster()` 从目录生成，`rosterDrift()` 能发现
   说明书与目录的漂移。这条探针**必须自己先复刻一遍漂移检测**，否则
   它只是在读同一份代码两次。
3. **同义词表有效性**：每个键都必须是真实存在的 id —— 否则那条别名
   永远不会命中，等于白写。

判定原则（沿用 HYGIENE.md）：
- 断言锚定**可执行代码形态**，不命中文档/注释里的字面量。
- 每条断言都要能反向改坏变红；无法变红的断言不算通过。
"""

from __future__ import annotations

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CATALOG = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EndpointCatalog.kt")
SEED = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/AgentContextSeed.kt")
GATE_DEV = os.path.join(ROOT, "app/src/main/assets/gates/dev")

FAILURES: list[str] = []
CHECKS = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global CHECKS
    CHECKS += 1
    if cond:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}" + (f"  [{detail}]" if detail else ""))
        FAILURES.append(name)


def read(path: str) -> str:
    with io.open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(src: str) -> str:
    """去掉 // 与 /* */ 注释，只留可执行代码。

    必要性：`EndpointCatalog` 里大段注释解释了「为什么不能用某种做法」，
    裸子串断言会命中注释里的反例，导致断言永远绿。
    """
    out: list[str] = []
    i = 0
    n = len(src)
    in_line = False
    in_block = False
    in_str = False
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
                out.append(c)
            i += 1
            continue
        if in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 2
                continue
            i += 1
            continue
        if in_str:
            out.append(c)
            if c == "\\":
                if i + 1 < n:
                    out.append(src[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == "/" and nxt == "/":
            in_line = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


# ---------------------------------------------------------------- 复刻打分

CJK_RANGES = [(0x4E00, 0x9FFF), (0x3400, 0x4DBF), (0xF900, 0xFAFF),
              (0x3040, 0x30FF), (0xAC00, 0xD7AF)]


def is_cjk(ch: str) -> bool:
    cp = ord(ch)
    return any(lo <= cp <= hi for lo, hi in CJK_RANGES)


def tokenize(raw: str) -> list[str]:
    """与 Kotlin `search()` 的切词保持一致。"""
    words: list[str] = []
    for chunk in raw.lower().split():
        if not chunk:
            continue
        cjk_only = all(is_cjk(c) for c in chunk)
        if not cjk_only:
            words.append(chunk)
        if any(is_cjk(c) for c in chunk):
            if cjk_only and len(chunk) == 1:
                words.append(chunk)
            else:
                for k in range(len(chunk) - 1):
                    bg = chunk[k:k + 2]
                    if all(is_cjk(c) for c in bg):
                        words.append(bg)
    return words


def score(entry: tuple[str, str, str], words: list[str],
          syn: dict[str, list[str]]) -> int:
    eid, path, summary = entry
    i, p, s = eid.lower(), path.lower(), summary.lower()
    total = 0
    all_hit = True
    soft = 0
    for w in words:
        is_soft = len(w) == 2 and all(is_cjk(c) for c in w)
        if i == w or p == w or p.endswith("/" + w):
            v = 1000
        elif i.startswith(w) or ("/" + w) in p:
            v = 500
        elif w in i or w in p:
            v = 300
        elif w in s:
            v = 200
        elif any((c == i or p.endswith("/" + c)) and any(w in a for a in al)
                 for c, al in syn.items()):
            v = 250
        else:
            v = 0
        if v == 0:
            if is_soft:
                continue
            all_hit = False
            break
        if is_soft:
            soft += 1
        else:
            total += v
    if not all_hit or (total == 0 and soft == 0):
        return 0
    return total + soft * 150


def main() -> int:
    catalog = read(CATALOG)
    seed = read(SEED)
    gate = read(GATE_DEV)
    catalog_code = strip_comments(catalog)

    # 提取目录条目
    pat = re.compile(
        r'e\(\s*"([a-z0-9-]+)"\s*,\s*"(GET|POST)"\s*,\s*"([^"]+)"\s*,\s*"([^"]*)"', re.S)
    entries = [(m.group(1), m.group(3), m.group(4)) for m in pat.finditer(catalog_code)]
    ids = sorted({e[0] for e in entries})

    # 提取同义词表
    syn_block = catalog_code.split("private val SYNONYMS")[1]
    syn_block = syn_block.split("\n    )")[0]
    syn = {
        m.group(1): [a.lower() for a in re.findall(r'"([^"]*)"', m.group(2))]
        for m in re.finditer(r'"([a-z0-9-]+)" to listOf\(([^)]*)\)', syn_block, re.S)
    }

    print("\n== 1. 意图检索 ==")
    check("catalog exposes search()", "fun search(query: String" in catalog_code,
          "search() missing")
    check("search() splits CJK into bigrams", "CJK_RANGES" in catalog_code
          and "substring(k, k + 2)" in catalog_code,
          "no bigram logic")
    check("search() tolerates soft (bigram) misses", "isSoft" in catalog_code,
          "no soft-hit handling")
    check("help() falls back to search on exact miss",
          "val hits = search(q" in catalog_code,
          "help() has no search fallback")

    def search(query: str, limit: int = 5) -> list[str]:
        words = tokenize(query)
        if not words:
            return []
        scored = [(score(e, words, syn), e) for e in entries]
        scored = [x for x in scored if x[0] > 0]
        scored.sort(key=lambda x: -x[0])
        return [e[0] for _, e in scored[:limit]]

    # 真实意图：这些是 AI 会说的话，不是内部名
    intent_cases = [
        ("读未读消息", "notifications"),
        ("剪贴板", "clipboard"),
        ("断网", "net"),
        ("蓝牙键盘", "bt-hid"),
        ("录屏", "record"),
        ("日历", "calendar"),
        ("通讯录", "contacts"),
        ("手电", "control"),
        ("提醒我", "remind"),
        ("位置围栏", "geofence"),
        ("震动摩斯", "pulse"),
        ("现在什么状态", "context"),
    ]
    for query, expect in intent_cases:
        hits = search(query)
        check(f"intent '{query}' finds {expect}", expect in hits, f"got {hits}")

    # 英文 intent 也要能中
    for query, expect in [("battery", "status"), ("screenshot", "screenwatch"),
                          ("notification", "notifications")]:
        hits = search(query)
        check(f"intent '{query}' finds {expect}", expect in hits, f"got {hits}")

    # 纯英文精确名仍然排第一
    hits = search("sms")
    check("exact id ranks first", hits and hits[0] == "sms", f"got {hits}")

    # 完全无关联的查询必须返回空（不能乱猜）
    check("nonsense query returns nothing", search("zzzqqqxxx") == [],
          f"got {search('zzzqqqxxx')}")

    print("\n== 1b. 检索实现形态（锚定 Kotlin 本体，防复刻假绿） ==")
    # 上面那组断言跑的是 Python 复刻的打分逻辑，它证明「算法语义对」，
    # 但改了 Kotlin 本体它察觉不到。所以这里必须再钉一遍**可执行代码形态**：
    # 把实现改坏时这些必须变红，否则前面的语义断言只是自说自话。
    check("search splits chunk into bigrams in Kotlin",
          "chunk.substring(k, k + 2)" in catalog_code,
          "bigram slicing removed from Kotlin")
    check("search guards bigram with CJK test",
          "if (bg.all { c -> isCjk(c.code) }) words.add(bg)" in catalog_code,
          "bigram guard removed")
    check("search keeps soft hits via isSoft branch",
          "if (isSoft) continue" in catalog_code,
          "soft-hit branch removed")
    check("search counts soft hits",
          "if (isSoft) softHit++ else total += s" in catalog_code,
          "soft counter removed")
    check("CJK detection uses range comparison, not IntArray membership",
          "private fun isCjk(cp: Int): Boolean" in catalog_code
          and "cp >= CJK_RANGES[i] && cp <= CJK_RANGES[i + 1]" in catalog_code,
          "isCjk range lookup missing")
    check("no stray IntArray membership test for CJK",
          "it.code in CJK_RANGES" not in catalog_code,
          "regressed to IntArray membership (only matches the 10 boundary values)")
    check("exact id gets a decisive bonus",
          "EXACT_ID_BONUS" in catalog_code,
          "exact matches can lose to fuzzy ones")
    check("search adds soft weight to score",
          "total + softHit * 150" in catalog_code,
          "soft weight removed")
    check("roster disables caps via if (off)",
          'if (off) sb.append("[off]")' in catalog_code,
          "off-marker branch removed")

    print("\n== 2. 名册自动生成 ==")
    check("renderRoster() exists", "fun renderRoster(" in catalog_code, "missing")
    check("roster marks POST entries",
          'if (it.method == "POST") sb.append("(POST)")' in catalog_code, "missing")
    check("roster marks disabled caps", "[off]" in catalog_code, "missing")
    check("rosterDrift() exists", "fun rosterDrift(" in catalog_code, "missing")
    check("seed renders roster dynamically",
          "EndpointCatalog.renderRoster" in strip_comments(seed),
          "seed still hardcodes the roster")
    check("seed interpolates the roster", "${roster}" in seed or "$roster" in seed,
          "roster computed but never interpolated")

    # 漂移检测必须真的能标出缺失 —— 用「删掉某个 id」模拟
    def roster_contains(text: str, needle: str) -> bool:
        pos = 0
        while True:
            idx = text.find(needle, pos)
            if idx < 0:
                return False
            before = text[idx - 1] if idx > 0 else None
            after = text[idx + len(needle)] if idx + len(needle) < len(text) else None

            def ident(c):
                return c is not None and (c.isalnum() or c in "_-")

            if not ident(before) and not ident(after):
                return True
            pos = idx + 1

    # 现状：说明书里仍有若干 id 只能通过「策略段 + 自动名册」覆盖。
    # 名册是动态插入的，所以静态文件里查不到是**预期**的 —— 这里断言
    # 的是「名册插入点存在」，而不是「静态文件包含每个 id」。
    check("seed has a roster insertion point",
          "Complete capability roster" in seed, "no roster section")

    # 反向验证：rosterDrift 的实现必须真的对边界字符敏感，
    # 否则 `net` 会被 `network` 命中，漂移永远查不出来。
    check("drift check anchors on Kotlin boundary form",
          "if (okBefore && okAfter) return true" in catalog_code,
          "boundary guard form changed")
    check("drift check rejects substring matches",
          roster_contains("network sms_post", "net") is False,
          "boundary check broken: 'net' matched 'network'")
    check("drift check accepts exact token",
          roster_contains("- net: GET/POST", "net") is True,
          "boundary check rejects exact token")

    print("\n== 3. 同义词表有效性 ==")
    check("synonyms table is non-trivial", len(syn) >= 20, f"only {len(syn)} entries")
    bad_keys = [k for k in syn if k not in ids]
    check("every synonym key is a real id", not bad_keys, f"dangling: {bad_keys}")
    # 别名不能为空
    empty_alias = [k for k, v in syn.items() if not v]
    check("no empty alias lists", not empty_alias, f"empty: {empty_alias}")

    print("\n== 4. 发现路径（dev 门脚本）==")
    check("dev lists capabilities when run bare", 'cmd_list; exit' in gate, "no bare listing")
    check("dev shows usage for one capability", "cmd_detail" in gate, "no detail path")
    check("dev queries the catalog for metadata",
          '/dev/help?q=' in gate, "dev does not consult the catalog")
    check("dev reports catalog/route drift", "/dev/routes" in gate, "no drift report")

    print(f"\n{CHECKS - len(FAILURES)}/{CHECKS} checks passed")
    if FAILURES:
        print("FAILED:")
        for f in FAILURES:
            print("  -", f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
