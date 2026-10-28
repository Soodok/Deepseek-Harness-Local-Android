#!/usr/bin/env python3
"""选包矩阵回归（v1.4.37）—— 自动更新"拿哪个包"的判定表。

## 为什么需要一条专门的探针
旧 `UpdateChecker` 用 `asset.name.contains("arm64")` 取**第一个**匹配，两个事故：
 - x86_64 设备拿到 arm64 包（装不上）；
 - tag Release 里 `-debug-noruntime.apk` 排在前面 → 正式用户被引到 debug 包
   （签名不同，覆盖安装必失败）。

新实现在 `update/UpdateAssetPicker.kt` 里按结构化三元组 `(variant, abi, package)` 选，
并且 **F2** 要求先判断"运行时有没有变更"：

    full 用户 + 运行时无变更 → 优先 noruntime（省 ~250MB，且不重解压）
    full 用户 + 运行时有变更 → 让用户二选一（AskUser）
    noruntime 用户           → 继续拿 noruntime（缺货才退 full）

本探针用 Python 复刻这张判定表，并断言 Kotlin 侧仍是同一套结构（关键分支标记还在）。
漂移时两边会同时失败，逼人明确决定"改行为"还是"改预期"。

运行：python scripts/probes/_test_update_asset_picker.py
"""
from __future__ import annotations

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PICKER = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update/UpdateAssetPicker.kt")

FULL, NORT = "full", "noruntime"
AUTO, KEEP = "auto", "keep"

failures: list[str] = []


def check(name: str, got, want) -> None:
    if got == want:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name} — got {got!r}, want {want!r}")
        failures.append(name)


# ── 复刻算法（与 UpdateAssetPicker 一一对应；改这里等于改规格） ─────────────


def runtime_changed(full_asset, env):
    if full_asset["builtAt"] > 0 and env["localBuiltAt"] > 0:
        return full_asset["builtAt"] > env["localBuiltAt"]
    if not full_asset.get("runtimeVersion"):
        return False
    local = env.get("localVersion")
    if not local:
        return True
    # 本地是占位值 "unknown"（v1.4.37 及更早写戳 bug）：无法证明变过 → 按未变更处理
    if local == "unknown":
        return False
    return full_asset["runtimeVersion"] != local


def pick(assets, env, strategy):
    same = [a for a in assets if a["variant"] == env["variant"] and a["abi"] == env["abi"]]
    if not same:
        return "NoAsset"
    full = next((a for a in same if a["package"] == FULL), None)
    nrt = next((a for a in same if a["package"] == NORT), None)

    if strategy == KEEP:
        if env["mode"] == NORT:
            if nrt:
                return ("Single", NORT, "KeptRuntime", False)
            if full:
                return ("Single", FULL, "AddedRuntime", runtime_changed(full, env))
            return "NoAsset"
        if full:
            return ("Single", FULL, "KeptRuntime", runtime_changed(full, env))
        if nrt:
            return ("Single", NORT, "FullToNoRuntime", False)
        return "NoAsset"

    if env["mode"] == NORT:
        if nrt:
            return ("Single", NORT, "KeptRuntime", False)
        if full:
            return ("Single", FULL, "AddedRuntime", runtime_changed(full, env))
        return "NoAsset"

    if full is None:
        if nrt:
            return ("Single", NORT, "FullToNoRuntime", False)
        return "NoAsset"
    changed = runtime_changed(full, env)
    if not changed:
        if nrt:
            return ("Single", NORT, "FullToNoRuntime", False)
        return ("Single", FULL, "KeptRuntime", False)
    if nrt:
        return ("AskUser", NORT, FULL)
    return ("Single", FULL, "AddedRuntime", True)


def asset(pkg, *, abi="arm64-v8a", variant="release", built=1000, ver="r1"):
    return {
        "package": pkg,
        "abi": abi,
        "variant": variant,
        "builtAt": built if pkg == FULL else 0,
        "runtimeVersion": ver if pkg == FULL else None,
    }


def env(*, mode=FULL, local_built=1000, local_ver="r1", abi="arm64-v8a", variant="release"):
    return {
        "mode": mode,
        "localBuiltAt": local_built,
        "localVersion": local_ver,
        "abi": abi,
        "variant": variant,
    }


def main() -> int:
    print("== 1. F2 形态决策（auto） ==")
    both_same_runtime = [asset(FULL, built=1000), asset(NORT)]
    # 运行时无变更 → 拿 noruntime 省 250MB
    check(
        "full + 运行时未变 + 两种都有 → noruntime",
        pick(both_same_runtime, env(mode=FULL, local_built=1000), AUTO),
        ("Single", NORT, "FullToNoRuntime", False),
    )
    # 运行时有变更（远端 builtAt 更新）→ 让用户选
    newer = [asset(FULL, built=2000, ver="r2"), asset(NORT)]
    check(
        "full + 运行时有变更 + 两种都有 → AskUser",
        pick(newer, env(mode=FULL, local_built=1000), AUTO),
        ("AskUser", NORT, FULL),
    )
    # 运行时有变更但只有 full → 直接 full，且要预告首启耗时
    check(
        "full + 运行时有变更 + 只有 full → full(prepare)",
        pick([asset(FULL, built=2000, ver="r2")], env(mode=FULL), AUTO),
        ("Single", FULL, "AddedRuntime", True),
    )
    # 只有 noruntime 时（发布侧缺 full）：不重解压
    check(
        "full + 只有 noruntime → noruntime",
        pick([asset(NORT)], env(mode=FULL), AUTO),
        ("Single", NORT, "FullToNoRuntime", False),
    )
    # noruntime 用户继续保持精简
    check(
        "noruntime + 两种都有 → noruntime",
        pick(newer, env(mode=NORT, local_built=1000), AUTO),
        ("Single", NORT, "KeptRuntime", False),
    )
    # noruntime 用户索引缺 noruntime → 退回 full，需重解压
    check(
        "noruntime + 只有 full → full(prepare)",
        pick([asset(FULL, built=2000, ver="r2")], env(mode=NORT, local_built=1000), AUTO),
        ("Single", FULL, "AddedRuntime", True),
    )

    print("== 2. keep 策略（保守，旧行为） ==")
    check(
        "keep + full → 始终 full",
        pick(both_same_runtime, env(mode=FULL, local_built=1000), KEEP),
        ("Single", FULL, "KeptRuntime", False),
    )
    check(
        "keep + noruntime → 始终 noruntime",
        pick(both_same_runtime, env(mode=NORT), KEEP),
        ("Single", NORT, "KeptRuntime", False),
    )

    print("== 3. 匹配维度：绝不按文件名猜 ==")
    check(
        "abi 不匹配 → NoAsset",
        pick([asset(FULL, abi="x86_64")], env(abi="arm64-v8a"), AUTO),
        "NoAsset",
    )
    check(
        "签名类型不匹配（release 构建只看 release 包） → NoAsset",
        pick([asset(FULL, variant="debug")], env(variant="release"), AUTO),
        "NoAsset",
    )

    print("== 3.5 本地运行时版本未知时的保守语义（v1.4.38） ==")
    # v1.4.37 及更早的写戳 bug 会在 .runtime.json 里留下 version="unknown"/builtAt=0。
    # 这种"不知道"绝不能当成"变了"：否则存量 full 用户每次升级都被劝多下 130MB，
    # 且要在升级 APK 的同时换装运行时（换装窗口远大于单纯升级）。
    unknown_env = env(mode=FULL, local_built=0, local_ver="unknown")
    check(
        "full + 本地 unknown + 两种都有 → noruntime（不打扰用户）",
        pick([asset(FULL, built=2000, ver="r2"), asset(NORT)], unknown_env, AUTO),
        ("Single", NORT, "FullToNoRuntime", False),
    )
    check(
        "full + 本地 unknown + 只有 full → full 但不预告重解压",
        pick([asset(FULL, built=2000, ver="r2")], unknown_env, AUTO),
        ("Single", FULL, "KeptRuntime", False),
    )
    # 真的没装（localVersion=None）仍要按"变更"处理：那是首启中断后必须补装
    check(
        "full + 本地未装（None） + 两种都有 → AskUser",
        pick([asset(FULL, built=2000, ver="r2"), asset(NORT)], env(mode=FULL, local_built=0, local_ver=None), AUTO),
        ("AskUser", NORT, FULL),
    )

    print("== 4. Kotlin 侧结构断言（防单边漂移） ==")
    src = open(PICKER, encoding="utf-8").read()
    for marker, desc in [
        ("it.variant == env.variant", "按 variant 过滤"),
        ("it.abi == env.abi", "按 abi 过滤"),
        ("it.isFull", "识别 full 资产"),
        ("it.isNoRuntime", "识别 noruntime 资产"),
        ("PickResult.AskUser(appOnly = it, withRuntime = full)", "运行时变更时二选一"),
        ("remoteBuiltAt > 0 && localBuiltAt > 0", "运行时变更优先用 builtAt 判定"),
        ("return remoteBuiltAt > localBuiltAt", "builtAt 严格大于才算变更"),
        ("if (localVersion == VERSION_UNKNOWN)", "本地版本未知不当成变更"),
        ('private const val VERSION_UNKNOWN = "unknown"', "占位值与索引/manifest 一致"),
    ]:
        check(f"结构：{desc}", marker in src, True)

    print()
    if failures:
        print(f"FAILED ({len(failures)}): {failures}")
        return 1
    print("All asset-picker checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
