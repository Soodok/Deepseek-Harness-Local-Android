#!/usr/bin/env python3
"""插件原生模块预检逻辑的回归测试（v1.4.15）。

## 背景
用户反馈：插件中心装插件会报 `ERR_PNPM_IGNORED_BUILDS`，或者装完直接起不来。

根因两层：
1. pnpm 12 默认拒绝执行依赖的 build 脚本（安全策略）→ 带原生模块的插件
   「装成功但二进制没编译」，`build/` 目录都不存在；
2. Android 上这类模块多数根本编译不出来（缺 clang 头文件搜索路径、
   node_prefix 写死成 Termux 路径）。

所以策略改为**装前检测 + 用户确认**。本测试固化「哪些包应该被判风险」的判据。

## 实测依据（2026-10-23，registry.npmmirror.com/latest）
    RISK  better-sqlite3  dep:node-addon-api
    RISK  sharp           platformVariants(24)
    RISK  node-pty        scripts.install | dep:node-addon-api
    RISK  canvas          scripts.install | binary | dep:node-addon-api | dep:prebuild-install
    PURE  esbuild / lodash / chalk / axios / @deepseek-ai/dsh-workspace
"""
from __future__ import annotations

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/NativePluginCheck.kt")

ok = 0
fail = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global ok, fail
    if cond:
        ok += 1
        print(f"  \u2713 {name}")
    else:
        fail += 1
        print(f"  \u2717 {name}" + (f"  -- {detail}" if detail else ""))


src = open(SRC, encoding="utf-8").read()


def simulate(manifest: dict) -> list[str]:
    """复刻 analyse() 的判据（保持在 Python 侧独立实现，做交叉验证）。"""
    reasons = []
    osv = manifest.get("os")
    if isinstance(osv, list) and osv and not any(x in ("android", "linux") for x in osv):
        reasons.append("os")

    scripts = manifest.get("scripts") or {}
    for hook in ("preinstall", "install", "postinstall"):
        c = str(scripts.get(hook, "")).lower()
        if re.search(r"node-gyp|prebuild-install|cmake-js|node-gyp-build|gyp rebuild", c):
            reasons.append(f"scripts.{hook}")

    if manifest.get("gypfile") is True:
        reasons.append("gypfile")
    if manifest.get("binary"):
        reasons.append("binary")

    files = manifest.get("files") or []
    if any("binding.gyp" in str(f) for f in files):
        reasons.append("binding.gyp")
    if any(str(f).startswith("prebuilds") for f in files):
        reasons.append("prebuilds")

    NATIVE = {"node-gyp", "node-pre-gyp", "@mapbox/node-pre-gyp", "prebuild-install",
              "node-addon-api", "nan", "cmake-js", "node-gyp-build"}
    for d in (manifest.get("dependencies") or {}):
        if d in NATIVE:
            reasons.append(f"dep:{d}")

    od = list((manifest.get("optionalDependencies") or {}).keys())
    PLAT = ("-linux", "-darwin", "-win32", "-freebsd", "-android", "-musl")
    pf = [n for n in od if any(t in n.lower() for t in PLAT)]
    if len(pf) >= 2 and not any("android" in n.lower() for n in pf):
        reasons.append(f"platformVariants({len(pf)})")

    # v1.4.17：fork 引擎插件检测（与 Kotlin 的 FORK_ENGINE_COMPONENTS 对齐）
    peers = manifest.get("peerDependencies") or {}
    for dep in peers:
        scope = dep.split("/")[0] if dep.startswith("@") else ""
        bare = dep.split("/")[-1].split("@")[0]
        if scope != "@deepseek-ai" and bare in (
            "cordis", "dsh-llm", "dsh-scope", "dsh-invariants",
            "dsh-system-prompt", "dsh-schemastery", "schemastery",
        ):
            reasons.append(f"engine fork dependency: {dep}")

    return list(dict.fromkeys(reasons))


print("=== 1. 已知原生包必须被判风险 ===")
cases_risk = {
    "better-sqlite3": {"dependencies": {"node-addon-api": "^8"}, "gypfile": False},
    "sharp": {"optionalDependencies": {
        "@img/sharp-linux-x64": "1", "@img/sharp-win32-x64": "1",
        "@img/sharp-darwin-arm64": "1", "@img/sharp-linuxmusl-arm64": "1"}},
    "node-pty": {"scripts": {"install": "node scripts/install.js"},
                 "dependencies": {"node-addon-api": "^7"}},
    "canvas": {"scripts": {"install": "node-pre-gyp install --fallback-to-build"},
               "binary": {"module_name": "canvas"}},
    "sqlite3-classic": {"gypfile": True, "scripts": {"install": "node-gyp rebuild"}},
    "prebuild-only": {"scripts": {"install": "prebuild-install || node-gyp rebuild"}},
}
for name, m in cases_risk.items():
    r = simulate(m)
    check(f"{name} 判为风险", bool(r), "判成纯 JS 了")
    print(f"      reasons: {r}")

print("\n=== 2. 纯 JS 包必须放行 ===")
cases_pure = {
    "lodash": {},
    "chalk": {},
    "axios": {"dependencies": {"follow-redirects": "^1"}},
    "dsh-workspace": {"dependencies": {"@deepseek-ai/cordis": "1"}},
    "echo-only-install": {"scripts": {"install": "node -e \"console.log(1)\""}},
}
for name, m in cases_pure.items():
    r = simulate(m)
    check(f"{name} 判为纯 JS", not r, f"误判: {r}")

print("\n=== 3. 平台专用包：有 android 变体就不算风险 ===")
r = simulate({"optionalDependencies": {
    "@pkg/linux-x64": "1", "@pkg/android-arm64": "1"}})
check("含 android 变体时不判风险", not r, str(r))

r = simulate({"os": ["linux", "darwin"]})
check("os 含 linux 时放行（bionic 属于 linux 语义）", not r, str(r))

r = simulate({"os": ["win32", "darwin"]})
check("os 既无 android 也无 linux 时判风险", bool(r), str(r))

print("\n=== 3b. fork 引擎插件检测（v1.4.17，用户反馈破甲插件完全不可用）===")
# 真实生态案例（registry 实测 2026-10-24）：
#   @knyazevai/dsh-system-prompt    peerDeps: @knyazevai/dsh-llm, @knyazevai/cordis, @knyazevai/dsh-scope
#   @monotykamary/dsh-system-prompt peerDeps: @monotykamary/dsh-llm, @monotykamary/cordis 等
# 「整套引擎的 fork 重发布」，在官方引擎上三层全断：
#   无 patch 声明（装=没装）/ name 守卫跳过换芯 / fork peerDeps 不自动装且无法共存。
fork_cases = {
    "knyazevai": {"peerDependencies": {
        "@knyazevai/dsh-llm": "0.2.6", "@deepseek-ai/cordis": "~4.0.5",
        "@knyazevai/dsh-scope": "0.2.6"},
        "dependencies": {"@deepseek-ai/schemastery": "~3.18.5"}},
    "monotykamary": {"peerDependencies": {
        "@monotykamary/dsh-invariants": "^0.1.9", "@monotykamary/dsh-llm": "^0.1.9",
        "@monotykamary/dsh-scope": "^0.1.9", "@monotykamary/cordis": "^4.0.1"},
        "dependencies": {"@monotykamary/schemastery": "^3.18.1"}},
}
for name, m in fork_cases.items():
    r = simulate(m)
    check(f"{name} 判为 fork 风险", any("fork" in x for x in r), str(r))

r = simulate({"peerDependencies": {
    "@deepseek-ai/cordis": "~4.0.5", "@deepseek-ai/dsh-llm": "^0.1.0"}})
check("官方 @deepseek-ai 作用域不判 fork", not any("fork" in x for x in r), str(r))

print("\n=== 4. 源码结构约束 ===")
check("定义了 registryName 解析", "fun registryName" in src)
check("非 registry 源返回 null", "startsWith(\"git\")" in src and "://" in src)
check("网络失败不拦用户（返回 Unknown）", "Result.Unknown(\"registry unreachable\")" in src)
check("超时设置存在", "connectTimeout" in src and "readTimeout" in src)
check("注释写明了 sharp 的判据来源", "optionalDependencies" in src and "platform" in src.lower())
check("fork 检测表存在（FORK_ENGINE_COMPONENTS）", "FORK_ENGINE_COMPONENTS" in src)
check("fork 判据排除官方作用域", 'scope != "@deepseek-ai"' in src)

print("\n=== 5. 启动隔离（bundled 快速通道）===")
pr = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/PluginRecovery.kt")
p = open(pr, encoding="utf-8").read()
check("有 isolateRecentBundleOnBootFailure", "fun isolateRecentBundleOnBootFailure" in p)
check("有快速隔离阈值常量", "FAST_ISOLATE_STREAK" in p)
check("同一批安装不猜测", "not guessing" in p)
check("隔离前备份清单", "backupManifest" in p)
sup = open(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt"),
           encoding="utf-8").read()
check("监督器接入了快速隔离", "isolateRecentBundleOnBootFailure" in sup)
check("隔离后写 engine.log", '"[plugin] isolated' in sup)

print("\n=== 6. UI 接入 ===")
ui = open(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/PluginStoreActivity.kt"),
          encoding="utf-8").read()
check("安装前调用预检", "NativePluginCheck.check" in ui)
check("风险时弹确认框", "confirmInstall" in ui)
check("确认后才真正安装", "doInstall" in ui)

print("\n" + "-" * 44)
print(f"通过 {ok} / 失败 {fail}")
sys.exit(1 if fail else 0)
