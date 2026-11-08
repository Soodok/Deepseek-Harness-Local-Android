#!/usr/bin/env python3
"""`_test_agent_preset_fallback.py` 的反向验证。

## 这里踩过的坑（值得记住）

第一版语义探针把补丁后的 `resolve()` **手抄**在 mjs 里，反向验证时
「把补丁改成 `if (false)`（完全不降级）」竟然**没变红** —— 因为探针在测
自己的复刻，跟补丁无关。这是典型的「复刻证明复刻」。

后来改成：py 探针用 `importlib` 载入补丁脚本、取 `ANCHOR + OLD_DIAG` 当作
「上游原文」、**真跑一遍 `patch_text()`**、把输出交给 mjs 执行。从此补丁
一改，探针立刻跟着变 —— 那两条 mutate 才真的变红。

## 每个变换先断言锚点命中数为 1
命中 0 就报「变换未生效」而不是「通过」，避免历史上「替换没生效却报绿」。
"""

from __future__ import annotations

import io
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PATCH = os.path.join(ROOT, "scripts/patch-agent-preset-fallback.py")
BUILD = os.path.join(ROOT, "scripts/collect-termux-runtime.sh")
PROBE = os.path.join(ROOT, "scripts/probes/_test_agent_preset_fallback.py")

results: list[tuple[str, bool, str]] = []


def run_probe() -> int:
    p = subprocess.run(
        [sys.executable, PROBE],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=dict(os.environ, PYTHONIOENCODING="utf-8"),
    )
    return p.returncode


def mutate(label: str, path: str, old: str, new: str) -> None:
    src = io.open(path, encoding="utf-8").read()
    n = src.count(old)
    if n != 1:
        results.append((label, False, f"ANCHOR COUNT {n} != 1（变换未生效）"))
        print(f"  SKIP  {label}: anchor count {n}")
        return
    backup = src
    try:
        io.open(path, "w", encoding="utf-8", newline="\n").write(src.replace(old, new))
        red = run_probe() != 0
        results.append((label, red, "" if red else "探针未变红"))
        print(f"  {'RED ' if red else 'GREEN(!!!'} {label}")
    finally:
        io.open(path, "w", encoding="utf-8", newline="\n").write(backup)


def main() -> int:
    rc = run_probe()
    print(f"baseline rc={rc}")
    if rc != 0:
        print("baseline broken, abort")
        return 1

    print("\n== 反向验证 ==")

    # 1. 补丁不再降级：未知预设重新抛错（等于退回上游行为）
    mutate(
        "patch drops the default fallback",
        PATCH,
        "if (record === void 0) {",
        "if (false) {",
    )

    # 2. 降级但不报告来源（静默吞掉，用户查不出发生了什么）
    mutate(
        "patch stops reporting fellBackFrom",
        PATCH,
        "const fellBack = fellBackFrom === null ? {} : { fellBackFrom };",
        "const fellBack = {};",
    )

    # 3. 默认预设也缺失时也降级（配置真损坏却被掩盖）
    mutate(
        "patch swallows a missing default too",
        PATCH,
        "if (fallback === void 0) throw new RemoteError(",
        "if (false) throw new RemoteError(",
    )

    # 4. 返回值仍用 wanted 而不是实际生效的 record.config.id
    #    （降级后 id 会报成那个已删除的名字，前端仍会错位）
    mutate(
        "resolve reports the requested id instead of the effective one",
        PATCH,
        "id: record.config.id,",
        "id: wanted,",
    )

    # 5. 构建链断开：补丁永不执行
    mutate(
        "build script stops calling the patch",
        BUILD,
        'python3 "$SCRIPTS_DIR/patch-agent-preset-fallback.py" "$ROOT" || {',
        "true || {",
    )

    print("\n== 汇总 ==")
    ok = sum(1 for _, r, _ in results if r)
    for label, red, reason in results:
        mark = "RED " if red else "FAIL"
        print(f"  {mark}  {label}" + (f"  <- {reason}" if reason else ""))
    print(f"\n{ok}/{len(results)} mutations turned the probe red")
    return 0 if results and ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
