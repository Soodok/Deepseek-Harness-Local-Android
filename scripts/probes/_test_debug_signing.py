#!/usr/bin/env python3
"""固定 debug 签名回归（v1.5.0）。

## 背景
AGP 默认 debug 签名用 `~/.android/debug.keystore`，**首次构建时自动生成**：
每个开发者、每个 CI runner（干净 HOME）各有一把不同的 key。后果不是"不够安全"，
而是**debug 包之间无法覆盖安装** —— 每个版本对系统来说都是签名冲突，只能卸载重装，
edge/preview 渠道的自动更新因此完全不成立。

修法是把一把共享的 debug key 放进仓库、让 Gradle 的 debug 签名指向它。

## 本测试保证
1. 密钥材料存在且 alias/口令与 Gradle 配置一致（口径漂移会静默产生不可覆盖的包）；
2. `app/build.gradle.kts` 里 **同名覆盖** 了 `debug` signingConfig，且没有回落到
   `~/.android/debug.keystore`；
3. 密钥缺失时构建 **必须失败**（否则又会悄悄产出不可覆盖的包）；
4. `.gitignore` 允许该文件入库，但**仍然忽略** `keystore/`（release 私钥不能入库）。

运行：python scripts/probes/_test_debug_signing.py
"""
from __future__ import annotations

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GRADLE = os.path.join(ROOT, "app/build.gradle.kts")
GITIGNORE = os.path.join(ROOT, ".gitignore")
SIGNING_DIR = os.path.join(ROOT, "debug-signing")

failures: list[str] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    if cond:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}{(' — ' + detail) if detail else ''}")
        failures.append(name)


def read(p: str) -> str:
    with open(p, encoding="utf-8") as f:
        return f.read()


def main() -> int:
    print("== 1. 密钥材料 ==")
    ks = os.path.join(SIGNING_DIR, "dsh-debug.keystore")
    props = os.path.join(SIGNING_DIR, "dsh-debug.properties")
    fpr = os.path.join(SIGNING_DIR, "dsh-debug.fingerprint.txt")
    check("keystore 存在", os.path.isfile(ks), ks)
    check("properties 存在", os.path.isfile(props), props)
    check("指纹文件存在", os.path.isfile(fpr), fpr)
    if os.path.isfile(props):
        p = read(props)
        check("alias = dsh-debug", "keyAlias=dsh-debug" in p)
        check("storePassword 为公开值 dsh-debug", "storePassword=dsh-debug" in p)
    if os.path.isfile(fpr):
        val = read(fpr).strip()
        check("指纹是 SHA-256（32 字节 HEX 冒号分隔）", len(val.split(":")) == 32, val)

    print("== 2. Gradle 配置 ==")
    g_raw = read(GRADLE)
    # 注释里出现 "~/.android/debug.keystore" 是在解释"为什么不回落到它"，不算违规；
    # 去掉 // 行注释与块注释后再判断真实代码。
    g = "\n".join(line.split("//", 1)[0] for line in g_raw.splitlines())
    check("debug signingConfig 被同名覆盖", 'getByName("debug")' in g)
    check("指向仓库内 keystore", "debug-signing/dsh-debug.keystore" in g)
    check("未回落到 AGP 默认 debug keystore", ".android/debug.keystore" not in g)
    check("密钥缺失时构建失败（不静默回落）", "缺少固定 debug 签名密钥" in g and "GradleException" in g)

    print("== 3. gitignore：入库 debug key，但不放行 release 私钥 ==")
    gi = read(GITIGNORE)
    check("仍忽略 keystore/ 目录", "keystore/" in gi)
    check("放行 debug-signing/", "!debug-signing/" in gi)
    check("放行 debug keystore 文件", "!debug-signing/dsh-debug.keystore" in gi)
    # 危险动作：为了放 debug key 而解禁整个 keystore/ 目录
    check(
        "没有解禁整个 keystore/（会连带 release 私钥）",
        not any(line.strip() == "!keystore/" for line in gi.splitlines()),
    )

    print()
    if failures:
        print(f"FAILED ({len(failures)}): {failures}")
        return 1
    print("All debug-signing checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
