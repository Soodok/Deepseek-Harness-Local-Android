#!/usr/bin/env python3
"""门脚本自检探针（v1.5.0-beta.10）。

覆盖的不是「桥能不能用」（那要真机），而是**静态结构正确性** —— 这类错误
在真机上表现为「命令跑了但没有任何输出」，极难看出来，实测踩过两次：

1. `dev` 统一入口的分派必须清空 `_DSH_REEXEC` 再 exec 子脚本。
   不清空的话子脚本会跳过自举、在 toybox sh 里 source `_dsh_http.sh`
   （需要 bash 的 `/dev/tcp`）→ 静默失败，一个字都不输出。

2. `dev <能力>` 无参数时**不能**写 `exec "$DIR/$x" --help || exec "$DIR/$x"`。
   `device-status` / `device-ping` 这类脚本不认 `--help` 但会静默成功退出 0，
   于是 `||` 的兜底永远不触发 → 同样一个字都不输出。

3. 每个门脚本都必须有自举块（`_DSH_REEXEC` 检查），否则被 toybox sh 直接
   执行时 `/dev/tcp` 不可用。

4. `dev` 的 LIST 表里登记的每一个脚本都必须真实存在，反之亦然（防止漏登记
   导致「能力存在但 AI 不知道」）。

5. 门脚本调用的 `/dev/*` 端点必须在 `AgentBridge.kt` 的路由表里存在。
"""

from __future__ import annotations

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GATES = os.path.join(ROOT, "app", "src", "main", "assets", "gates")
BRIDGE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "AgentBridge.kt")

checks = 0
failed = 0


def ok(msg: str) -> None:
    global checks
    checks += 1
    print(f"  ok   {msg}")


def bad(msg: str) -> None:
    global checks, failed
    checks += 1
    failed += 1
    print(f"  FAIL {msg}")


def read(path: str) -> str:
    return io.open(path, encoding="utf-8", errors="replace").read()


def main() -> int:
    print("A. dev 统一入口的自举与分派")
    dev_path = os.path.join(GATES, "dev")
    if not os.path.isfile(dev_path):
        bad("gates/dev 不存在")
        return 1
    dev = read(dev_path)

    if "_DSH_REEXEC= exec" in dev:
        ok("dev 分派前清空了 _DSH_REEXEC")
    else:
        bad("dev 分派前没有清空 _DSH_REEXEC —— 子脚本会跳过自举、静默无输出")

    code_lines = [ln for ln in dev.splitlines() if not ln.lstrip().startswith('#')]
    if any('exec "$DIR/$script" --help' in l for l in code_lines):
        bad('dev 仍有 `exec "$DIR/$script" --help ||` 写法 —— 不认 --help 的脚本会静默成功')
    else:
        ok("dev 无 `--help` 兜底陷阱")

    print("\nB. 每个门脚本都有自举块")
    skip = {"_dsh_http.sh"}
    # fast 是纯本地快速路径（node 侧执行），不发 HTTP 请求，故不需要自举
    no_bootstrap = {"fast"}
    for name in sorted(os.listdir(GATES)):
        path = os.path.join(GATES, name)
        if name in skip or name in no_bootstrap or not os.path.isfile(path):
            continue
        body = read(path)
        if '_DSH_REEXEC' in body and '@DSH_BASH@' in body:
            ok(f"{name} 有自举块")
        else:
            bad(f"{name} 缺自举块（toybox sh 下 /dev/tcp 不可用）")

    print("\nC. dev 的 LIST 表与目录清单一致")
    m = re.search(r'^LIST="$(.*?)^"$', dev, re.S | re.M)
    if not m:
        bad("dev 里找不到 LIST 表")
    else:
        listed = set()
        for line in m.group(1).split("\n"):
            line = line.strip()
            if not line or "|" not in line:
                continue
            script = line.split("|")[1]
            listed.add(script)
        on_disk = {
            n for n in os.listdir(GATES)
            if os.path.isfile(os.path.join(GATES, n)) and not n.startswith("_")
        }
        dep = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EngineConfig.kt")
        deployed = set(re.findall(r'"([a-z][a-z0-9-]*)"', read(dep)))
        # LIST 里登记的脚本必须真实存在，且必须进部署清单
        miss_file = sorted(s for s in listed if not os.path.isfile(os.path.join(GATES, s)))
        miss_dep = sorted(s for s in listed if s not in deployed)
        if miss_file:
            bad(f"LIST 登记但文件不存在：{miss_file}")
        else:
            ok(f"LIST 的 {len(listed)} 个脚本都有对应文件")
        if miss_dep:
            bad(f"LIST 登记但不在 EngineConfig 部署清单：{miss_dep}")
        else:
            ok("LIST 的脚本都在部署清单里")
        # 反向：有文件但没进 LIST 的（_dsh_http.sh 与底层工具除外）
        uncovered = sorted(s for s in on_disk if s not in listed
                           and s not in {"psx", "killx", "loop", "fast", "parallel", "dev"})
        if uncovered:
            bad(f"有门脚本但没进 dev LIST（AI 发现不了）：{uncovered}")
        else:
            ok("所有门脚本都能从 dev 发现")

    print("\nD. 门脚本调用的端点必须存在")
    bridge = read(BRIDGE)
    rpaths = {p for _, p in re.findall(r'"(GET|POST) (/[^"]+)"', bridge)}
    missing = []
    for name in sorted(os.listdir(GATES)):
        path = os.path.join(GATES, name)
        if not os.path.isfile(path):
            continue
        body = read(path)
        # 只认字面量路径（含 $ 的是动态拼接，跳过）
        for mm in re.finditer(r'dsh_http\s+(?:GET|POST)\s+"?(/[A-Za-z0-9_/-]+)', body):
            p = mm.group(1)
            if p not in rpaths:
                missing.append((name, p))
    if missing:
        bad(f"调用了不存在的端点：{missing}")
    else:
        ok(f"所有字面量端点都在路由表里（共 {len(rpaths)} 条路由）")

    print(f"\n{'=' * 46}")
    print(f"checks={checks} failed={failed}")
    print("GATE SCRIPT SELF-CHECK: " + ("OK" if failed == 0 else "FAILED"))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
