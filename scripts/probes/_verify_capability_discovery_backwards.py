#!/usr/bin/env python3
"""`_test_capability_discovery.py` 的反向验证。

逐条把实现改坏，确认探针**真的变红**，然后复原。无法变红的断言是
假绿 —— 它保护不了任何东西。

每个变换都先断言「锚点命中次数」，命中 0 就报错退出（避免替换没生效
却报「通过」的历史事故）。
"""

from __future__ import annotations

import io
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CATALOG = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EndpointCatalog.kt")
SEED = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/AgentContextSeed.kt")
PROBE = os.path.join(ROOT, "scripts/probes/_test_capability_discovery.py")

results: list[tuple[str, bool, str]] = []


def run_probe() -> tuple[int, str]:
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    p = subprocess.run([sys.executable, PROBE], capture_output=True, text=True,
                       env=env, encoding="utf-8", errors="replace")
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def baseline() -> bool:
    rc, out = run_probe()
    ok = rc == 0
    print(f"baseline: rc={rc} {'OK' if ok else 'BROKEN'}")
    if not ok:
        print(out[-1500:])
    return ok


def mutate(label: str, path: str, old: str, new: str) -> None:
    """替换一处，跑探针，必须变红，然后复原。"""
    src = io.open(path, encoding="utf-8").read()
    n = src.count(old)
    if n != 1:
        results.append((label, False, f"ANCHOR COUNT {n} != 1 (变换未生效)"))
        print(f"  SKIP  {label}: anchor count {n}")
        return
    backup = src
    try:
        io.open(path, "w", encoding="utf-8", newline="\n").write(src.replace(old, new))
        rc, out = run_probe()
        went_red = rc != 0
        results.append((label, went_red, "" if went_red else "探针未变红"))
        tag = "RED " if went_red else "GREEN(!!!)"
        print(f"  {tag} {label}")
    finally:
        io.open(path, "w", encoding="utf-8", newline="\n").write(backup)


def main() -> int:
    if not baseline():
        print("baseline broken, abort")
        return 1

    print("\n== 反向验证 ==")

    # 1. 去掉 CJK 二元组切分 → 中文意图应当全部失效
    mutate("remove CJK bigram split", CATALOG,
           "if (bg.all { c -> isCjk(c.code) }) words.add(bg)",
           "if (false) words.add(bg)")

    # 1b. 把区间判断退回 IntArray 成员判断（真实踩过的 bug）
    mutate("CJK check regresses to IntArray membership", CATALOG,
           "if (cp >= CJK_RANGES[i] && cp <= CJK_RANGES[i + 1]) return true",
           "if (cp == CJK_RANGES[i]) return true")

    # 1c. 去掉精确 ID 加成 → 精确查询可能被模糊结果挤下去
    # 1c. 去掉精确 ID 加成 —— **本条故意不归 py 探针管**。
    # py 探针只能断言「常量名存在」（形态），把它置 0 形态不变、探针不会红。
    # 这个缺陷由 JVM 单测 `exact id ranks first` 覆盖（断言真实排序结果）。
    # 记在这里是为了不让「没变红」被误读成漏测。

    # 2. 去掉软命中容忍 → 中文整句应当全部失效
    mutate("remove soft-hit tolerance", CATALOG,
           "                    if (isSoft) continue\n",
           "                    if (false) continue\n")

    # 3. 拆掉 help() 的 search 兜底 → 精确未命中就 404
    mutate("remove help() search fallback", CATALOG,
           "                val hits = search(q, limit = SEARCH_FALLBACK_LIMIT)",
           "                val hits = emptyList<Entry>()")

    # 4. 名册不再标 POST
    mutate("roster stops marking POST", CATALOG,
           'if (it.method == "POST") sb.append("(POST)")',
           'if (false) sb.append("(POST)")')

    # 5. 名册不再标 [off]
    mutate("roster stops marking disabled caps", CATALOG,
           'if (off) sb.append("[off]")',
           'if (false) sb.append("[off]")')

    # 6. 说明书不再插值名册（回到硬编码时代）
    mutate("seed stops interpolating roster", SEED,
           "${roster}", "roster-goes-here")

    # 7. 边界检查失效：rosterContains 恒返回 true → 漂移永远查不出
    mutate("drift boundary check always true", CATALOG,
           "            if (okBefore && okAfter) return true\n",
           "            if (true) return true\n")

    print("\n== 汇总 ==")
    ok = sum(1 for _, r, _ in results if r)
    for label, red, reason in results:
        mark = "RED " if red else "FAIL"
        print(f"  {mark}  {label}" + (f"  <- {reason}" if reason else ""))
    print(f"\n{ok}/{len(results)} mutations turned the probe red")
    return 0 if ok == len(results) and results else 1


if __name__ == "__main__":
    sys.exit(main())
