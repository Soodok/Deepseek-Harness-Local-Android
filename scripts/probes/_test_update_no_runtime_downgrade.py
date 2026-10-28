#!/usr/bin/env python3
"""full 包不得把运行时**降级**（v1.5.0）。

## 风险场景

`full` 安装包里自带 `runtime.zip`。用户可能已经通过「关于 → 运行时更新」把运行时
升到比当前 APK 内更新的版本（常见：先升 APK，再单独升运行时；或回退到旧 APK 排查问题）。
这时如果 APK 一装就把包内旧运行时解压覆盖，会出现：

  - 运行时从 v2026.12.03 掉回 v2026.11.01（用户只装了个 patch，功能反而坏了）；
  - 首启还要白等 1–3 分钟重解压。

两道闸门必须都在：
 1. **选包侧**（`UpdateAssetPicker.runtimeChanged`）：远端 full 的 `builtAt` **严格大于**
    本机才算"有运行时变更"；否则优先拿 `noruntime`，连运行时都不碰。
 2. **安装侧**（`RuntimeInstaller.ensureInstalled`）：包内 `builtAt` 比已装的旧 →
    直接复用已装运行时（`reuse = true`），不重装。

本探针复刻两处判定并断言 Kotlin 侧结构仍在。

运行：python scripts/probes/_test_update_no_runtime_downgrade.py
"""
from __future__ import annotations

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
INSTALLER = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/RuntimeInstaller.kt")
PICKER = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update/UpdateAssetPicker.kt")
SERVICE = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update/AppUpdateDownloadService.kt")

failures: list[str] = []


def check(name: str, got, want) -> None:
    if got == want:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name} — got {got!r}, want {want!r}")
        failures.append(name)


# ── 复刻 1：选包侧"运行时是否变更"（UpdateAssetPicker.runtimeChanged） ──────


def runtime_changed(remote_built: int, local_built: int, remote_ver: str, local_ver: str) -> bool:
    if remote_built > 0 and local_built > 0:
        return remote_built > local_built
    if not remote_ver:
        return False
    if not local_ver:
        return True
    return remote_ver != local_ver


def pick_auto_full(remote_built: int, local_built: int, has_noruntime: bool):
    """当前是 full + 索引里两种都有时的选择。"""
    changed = runtime_changed(remote_built, local_built, "r", "r")
    if not changed:
        return "noruntime" if has_noruntime else "full"
    return "ask-user" if has_noruntime else "full+prepare"


# ── 复刻 2：安装侧复用判定（RuntimeInstaller.ensureInstalled） ────────────


def reuse(
    *,
    package: str,
    bundled: bool,
    installed_built: int,
    bundled_built: int,
    installed_ver: str,
    bundled_ver: str,
) -> bool:
    """True = 复用已装运行时（不重装），False = 用包内的装一遍。"""
    if package == "noruntime":
        return True
    if not bundled:
        return True
    if installed_built > 0 and bundled_built > 0 and installed_built > bundled_built:
        return True
    if not bundled_ver:
        return True
    if installed_ver:
        return installed_ver == bundled_ver
    return False


def main() -> int:
    print("== 1. 安装侧：包内运行时更旧时不得覆盖 ==")
    check(
        "已装 2000 > 包内 1000 → 复用（不降级）",
        reuse(
            package="app.dsh.mobile",
            bundled=True,
            installed_built=2000,
            bundled_built=1000,
            installed_ver="r2",
            bundled_ver="r1",
        ),
        True,
    )
    check(
        "已装 1000 < 包内 2000 → 重装（正常升级）",
        reuse(
            package="app.dsh.mobile",
            bundled=True,
            installed_built=1000,
            bundled_built=2000,
            installed_ver="r1",
            bundled_ver="r2",
        ),
        False,
    )
    check(
        "builtAt 相同 + 版本相同 → 复用",
        reuse(
            package="app.dsh.mobile",
            bundled=True,
            installed_built=1000,
            bundled_built=1000,
            installed_ver="r1",
            bundled_ver="r1",
        ),
        True,
    )
    check(
        "builtAt 缺失时退回版本串：不同 → 重装",
        reuse(
            package="app.dsh.mobile",
            bundled=True,
            installed_built=0,
            bundled_built=0,
            installed_ver="r1",
            bundled_ver="r2",
        ),
        False,
    )
    check(
        "noruntime 包 → 永远复用本机运行时",
        reuse(
            package="noruntime",
            bundled=False,
            installed_built=0,
            bundled_built=0,
            installed_ver="r1",
            bundled_ver="",
        ),
        True,
    )

    print("== 2. 选包侧：旧的 builtAt 不算「有运行时变更」 ==")
    check(
        "远端 1000 < 本机 2000 → 无变更",
        runtime_changed(1000, 2000, "r1", "r2"),
        False,
    )
    check(
        "无变更 + 有 noruntime → 选 noruntime（连运行时都不碰）",
        pick_auto_full(1000, 2000, has_noruntime=True),
        "noruntime",
    )
    check(
        "有变更 + 有 noruntime → 交用户二选一",
        pick_auto_full(2000, 1000, has_noruntime=True),
        "ask-user",
    )
    check(
        "builtAt 相同 → 不算变更（严格大于才算）",
        runtime_changed(1000, 1000, "r1", "r1"),
        False,
    )

    print("== 3. Kotlin 侧结构断言 ==")
    installer = open(INSTALLER, encoding="utf-8").read()
    picker = open(PICKER, encoding="utf-8").read()
    service = open(SERVICE, encoding="utf-8").read()

    for marker, desc in [
        ("installed.builtAt > manifest.runtime.builtAt", "安装侧：已装更新则复用"),
        ('manifest.packageName == "noruntime"', "安装侧：noruntime 包直接复用"),
        ("!manifest.runtime.bundled", "安装侧：无内置运行时直接复用"),
    ]:
        check(f"结构：{desc}", marker in installer, True)

    for marker, desc in [
        ("if (remoteBuiltAt > 0 && localBuiltAt > 0) return remoteBuiltAt > localBuiltAt", "选包侧：builtAt 严格大于"),
        ("if (!changed) {", "选包侧：无变更走省流量分支"),
    ]:
        check(f"结构：{desc}", marker in picker, True)

    check(
        "结构：APK 下载服务不碰运行时安装（只落 APK）",
        "RuntimeInstaller." not in service,
        True,
    )

    print()
    if failures:
        print(f"FAILED ({len(failures)}): {failures}")
        return 1
    print("All no-runtime-downgrade checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
