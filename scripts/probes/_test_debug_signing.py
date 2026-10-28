#!/usr/bin/env python3
"""共享 debug 签名策略回归（v1.5.0）。

## 背景
AGP 默认 debug 签名用 `~/.android/debug.keystore`，**首次构建时自动生成**：
每个开发者、每个 CI runner（干净 HOME）各有一把不同的 key。后果不是"不够安全"，
而是**debug 包之间无法覆盖安装** —— 每个版本对系统来说都是签名冲突，只能卸载重装，
edge/preview 渠道的自动更新因此完全不成立。

修法要的是「同一把 key」，不是「公开这把 key」。所以材料只放两处：
CI repository secrets（`DEBUG_KEYSTORE_BASE64` 等）与本地 `debug-signing/`（gitignore）。

## 本测试保证
1. 私钥材料**没有被 git 跟踪**（这条一旦破，等于把 debug 私钥公开）；
2. `.gitignore` 只放行公开的指纹文件，且**没有**为了放 key 而解禁目录
   （`!debug-signing/` 会连带放行口令文件，`!keystore/` 更会放行 release 私钥）；
3. Gradle 支持 secret 注入 + 本地回退 + 无材料时退回 AGP 默认（不阻断开发）；
4. **只有 release** 在显式要求时缺私钥会失败（`-PrequireReleaseSigning=true`）；
5. CI 有解码步骤，且指纹门禁区分"没配 secret（fork）"与"配了 secret 但没配指纹"。

运行：python scripts/probes/_test_debug_signing.py
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GRADLE = os.path.join(ROOT, "app/build.gradle.kts")
GITIGNORE = os.path.join(ROOT, ".gitignore")
WORKFLOW = os.path.join(ROOT, ".github/workflows/android-build.yml")
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


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, check=False
    ).stdout


def strip_comments(src: str) -> str:
    return "\n".join(line.split("//", 1)[0] for line in src.splitlines())


def main() -> int:
    print("== 1. 私钥绝不入库 ==")
    tracked = [p for p in git("ls-files", "debug-signing/").splitlines() if p.strip()]
    secret_like = [
        p for p in tracked if p.endswith((".keystore", ".jks", ".properties", ".base64"))
    ]
    check("debug-signing/ 下没有私钥类文件被跟踪", not secret_like, str(secret_like))
    check(
        "指纹文件仍被跟踪（公开值，CI 靠它断言）",
        "debug-signing/dsh-debug.fingerprint.txt" in tracked,
        str(tracked),
    )
    # 全仓库扫一遍：任何被跟踪的 *.keystore / *.jks 都是事故
    all_tracked = git("ls-files").splitlines()
    leaked = [p for p in all_tracked if p.endswith((".keystore", ".jks"))]
    check("仓库任何位置都没有被跟踪的 keystore/jks", not leaked, str(leaked[:3]))

    print("== 2. gitignore：只放行公开指纹 ==")
    gi = read(GITIGNORE)
    lines = [ln.strip() for ln in gi.splitlines()]
    check("忽略 debug-signing/ 内容", "debug-signing/*" in lines)
    check(
        "放行指纹文件",
        "!debug-signing/dsh-debug.fingerprint.txt" in lines,
    )
    check("仍忽略 keystore/ 目录", "keystore/" in lines)
    # 危险动作：为了放 key 而解禁目录
    check("没有解禁 debug-signing/ 整个目录（会放行口令文件）", "!debug-signing/" not in lines)
    check("没有解禁 keystore/（会连带 release 私钥）", "!keystore/" not in lines)
    if os.path.isdir(SIGNING_DIR):
        for name in ("dsh-debug.keystore", "dsh-debug.properties"):
            p = os.path.join(SIGNING_DIR, name)
            if os.path.isfile(p):
                out = subprocess.run(
                    ["git", "check-ignore", "-q", os.path.relpath(p, ROOT).replace(os.sep, "/")],
                    cwd=ROOT,
                    check=False,
                )
                check(f"{name} 被 gitignore 命中", out.returncode == 0)

    print("== 3. Gradle：注入 → 本地 → 默认，三级回退 ==")
    g = strip_comments(read(GRADLE))
    check("支持 DSH_DEBUG_STORE_FILE 注入", "DSH_DEBUG_STORE_FILE" in g)
    check("支持 -PdshDebugStoreFile 覆盖", "dshDebugStoreFile" in g)
    check("本地回退 debug-signing/dsh-debug.keystore", "debug-signing/dsh-debug.keystore" in g)
    check("显式指定却不存在时失败（不静默换 key）", "DSH_DEBUG_STORE_FILE 指向的 keystore 不存在" in g)
    check("拿不到材料时不覆盖 signingConfig（退回默认）", "debugSigningReady && debugKeystoreFile != null" in g)
    check("未硬编码 ~/.android/debug.keystore 路径", ".android/debug.keystore" not in g)
    check("debug 不再无条件抛异常", "缺少固定 debug 签名密钥" not in g)
    # 口令不能再是公开常量：keystore 泄漏 + 口令公开 = 私钥泄漏
    # 只看口令（alias 留默认值是正常的）
    check(
        "口令没有公开默认值",
        re.search(r'debugStorePassword\s*=\s*debugSetting\([^)]*\)\s*\?:', g) is None
        and 'storePassword = "dsh-debug"' not in g,
    )
    check("缺口令时给出明确指引", "但缺少口令" in g)
    sh = read(os.path.join(ROOT, "scripts/make-debug-keystore.sh"))
    check("生成脚本用随机口令", "gen_pass()" in sh and 'STORE_PASS="dsh-debug"' not in sh)

    print("== 4. 只有 release 强制要求私钥 ==")
    check("存在 requireReleaseSigning 开关", "requireReleaseSigning" in g)
    check(
        "缺私钥时 packageRelease 失败",
        'tasks.matching { it.name.startsWith("packageRelease") }' in g and "拒绝产出未签名的正式包" in g,
    )
    wf = read(WORKFLOW)
    check("CI 的 assembleRelease 传了该开关", "-PrequireReleaseSigning=true" in wf)

    print("== 5. CI 注入与门禁 ==")
    check("CI 从 secret 注入 base64", "DEBUG_KEYSTORE_BASE64: ${{ secrets.DEBUG_KEYSTORE_BASE64 }}" in wf)
    check("CI 有解码步骤", "解码 debug keystore" in wf)
    check("解码目标在 RUNNER_TEMP（不进工作树）", "$RUNNER_TEMP/dsh-debug" in wf)
    check("门禁区分是否配置了 secret", "HAS_DEBUG_SECRET" in wf)
    check("门禁读取 DEBUG_CERT_SHA256 变量", "vars.DEBUG_CERT_SHA256" in wf)
    check("配了 secret 却没配指纹时报错", "没有 DEBUG_CERT_SHA256 变量" in wf)

    print()
    if failures:
        print(f"FAILED ({len(failures)}): {failures}")
        return 1
    print("All debug-signing checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
