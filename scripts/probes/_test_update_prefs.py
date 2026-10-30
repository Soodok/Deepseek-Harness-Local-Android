#!/usr/bin/env python3
"""更新偏好 / 检查时机 / 忽略语义回归（v1.4.37，§6 §11）。

## 为什么要这条探针

这三件事全是"没人会手动回归"的行为，而且错了不会崩、只会静默变行为：

 - **忽略语义**：`isIgnored(code)` 用 `code <= ignored` 判定 —— 意思是"忽略到这个版本为止"，
   更高的版本自动恢复提示。若哪天被改成 `==`，用户会重新被同一个版本骚扰；改成
   `>=` 则永久静默（比崩溃更糟，用户以为坏了）。
 - **检查时机**：冷启动必查（不受 24h 限制）、常驻 24h 一次、10 分钟最小间隔。
   把 20s 改成 0 会和首启解压抢 IO；把 10 分钟删掉会被"反复杀进程重开"打爆限额。
 - **设置开关真的被读**：`auto_check` / `wifi_only` / `proxy_fallback` /
   `package_strategy` 四个键在设置页写入后必须有人消费，否则开关只是装饰。

探针用 Python 复刻判定函数，再断言 Kotlin 侧仍是同一套结构（关键常量/分支还在）。
两边任何一侧漂移都会失败，逼人明确决定"改行为"还是"改预期"。

运行：python scripts/probes/_test_update_prefs.py
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PREFS = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update/AppUpdatePrefs.kt")
SCHED = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update/AppUpdateScheduler.kt")
SERVICE = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update/AppUpdateDownloadService.kt")
SETTINGS = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/SettingsActivity.kt")

MIN_GAP_MS = 10 * 60 * 1000
COLD_START_MS = 20 * 1000
RESIDENT_MS = 24 * 60 * 60 * 1000
PART_TTL_MS = 7 * 24 * 60 * 60 * 1000

failures: list[str] = []


def check(name: str, got, want) -> None:
    if got == want:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name} — got {got!r}, want {want!r}")
        failures.append(name)


# ── 复刻：忽略语义（AppUpdatePrefs.isIgnored） ────────────────────────────


def is_ignored(ignored_code: int, version_code: int) -> bool:
    """忽略到"这个版本为止"：<= ignored 的都不提示，更高的自动恢复。"""
    return ignored_code > 0 and version_code <= ignored_code


# ── 复刻：检查时机（AppUpdateScheduler.runCheck 的守卫） ──────────────────


def should_check(*, force: bool, auto_check: bool, now: int, last_started: int) -> bool:
    if not force and not auto_check:
        return False
    if not force and now - last_started < MIN_GAP_MS:
        return False
    return True


def main() -> int:
    print("== 1. 忽略语义：只拦这一个版本，更高版本恢复 ==")
    check("未忽略 → 提示", is_ignored(0, 1200), False)
    check("忽略 v1200 → v1200 不提示", is_ignored(1200, 1200), True)
    check("忽略 v1200 → v1199 不提示（历史版本）", is_ignored(1200, 1199), True)
    check("忽略 v1200 → v1201 恢复提示（关键）", is_ignored(1200, 1201), False)
    check("忽略记录被清除（0）后恢复提示", is_ignored(0, 1200), False)

    print("== 2. 检查时机：冷启动必查 / 常驻 24h / 10 分钟保护 ==")
    check("自动检查关 + 非手动 → 不查", should_check(force=False, auto_check=False, now=10**9, last_started=0), False)
    check("自动检查关 + 手动「立即检查」→ 仍要查", should_check(force=True, auto_check=False, now=10**9, last_started=0), True)
    check("10 分钟内重复触发 → 跳过（保护限额）", should_check(force=False, auto_check=True, now=MIN_GAP_MS - 1, last_started=0), False)
    check("超过 10 分钟 → 允许", should_check(force=False, auto_check=True, now=MIN_GAP_MS + 1, last_started=0), True)
    check("手动检查无视 10 分钟保护", should_check(force=True, auto_check=True, now=1, last_started=0), True)

    print("== 3. Kotlin 侧常量与分支（防单边漂移） ==")
    sched = open(SCHED, encoding="utf-8").read()
    prefs = open(PREFS, encoding="utf-8").read()
    service = open(SERVICE, encoding="utf-8").read()

    for marker, desc in [
        ("COLD_START_DELAY_MS = 20_000L", "冷启动延迟 20s（等引擎 READY）"),
        ("RESIDENT_INTERVAL_MS = 24L * 60 * 60 * 1000", "常驻 24h"),
        ("MIN_GAP_MS = 10L * 60 * 1000", "10 分钟最小间隔"),
        ("if (!force && !AppUpdatePrefs.autoCheck(ctx)) return", "自动检查开关生效（手动不受限）"),
        ("if (!force && now - lastStartedAt < MIN_GAP_MS)", "节流仅作用于自动检查"),
        ("if (since < RESIDENT_INTERVAL_MS) return", "onForeground 只补 24h 一次"),
        ("AppUpdatePrefs.autoDownloadedCode(ctx) == info.versionCode", "同一版本只自动下一次"),
        ("AppUpdatePrefs.isIgnored(ctx, info.versionCode)", "忽略的版本不自动下载"),
        ("AppUpdatePrefs.wifiOnly(ctx) && !NetworkState.isUnmetered(ctx)", "仅 WiFi 策略生效"),
    ]:
        check(f"结构：{desc}", marker in sched, True)

    for marker, desc in [
        ("return ignored > 0 && versionCode <= ignored", "忽略判定是 <="),
        ("PART_TTL_MS = 7L * 24 * 60 * 60 * 1000", ".part 7 天过期"),
        ("fun cleanupStale(ctx: Context, keepVersionCode: Int)", "非当前版本目录清理"),
        ("fun deleteDownloaded(ctx: Context)", "「删除安装包」入口存在"),
    ]:
        check(f"结构：{desc}", marker in prefs, True)

    check(
        "结构：下载前先清残留",
        "AppUpdatePrefs.cleanupStale(this, info.versionCode)" in service,
        True,
    )

    print("== 4. 设置项：每个键都有人消费 ==")
    settings = open(SETTINGS, encoding="utf-8").read()
    for call, key in [
        ("AppUpdatePrefs.setAutoCheck", "update_auto_check"),
        ("AppUpdatePrefs.setWifiOnly", "update_wifi_only"),
        ("AppUpdatePrefs.setProxyFallback", "update_index_proxy_fallback"),
        ("AppUpdatePrefs.setPackageStrategy", "update_package_mode_strategy"),
        ("AppUpdatePrefs.clearIgnored", "update_ignored_version_code"),
    ]:
        check(f"设置页写 {key}", call in settings, True)

    for consumer, key in [
        ("AppUpdatePrefs.autoCheck(ctx)", "update_auto_check"),
        ("AppUpdatePrefs.wifiOnly(ctx)", "update_wifi_only"),
        ("AppUpdatePrefs.proxyFallback(ctx)", "update_index_proxy_fallback"),
        ("AppUpdatePrefs.packageStrategy(ctx)", "update_package_mode_strategy"),
    ]:
        src = prefs + sched + open(
            os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update/AppUpdateChecker.kt"),
            encoding="utf-8",
        ).read()
        check(f"有消费者读 {key}", consumer in src, True)

    print("== 5. 键名纪律：全部 update_ 前缀（设置页/探针统一扫描的前提） ==")
    keys = re.findall(r'const val KEY_[A-Z_]+ = "([^"]+)"', prefs)
    check("键数量 >= 12", len(keys) >= 12, True)
    check("全部以 update_ 开头", all(k.startswith("update_") for k in keys), True)
    for key in [
        "update_last_check_at",
        "update_last_result",
        "update_pending_json",
        "update_auto_downloaded_code",
        "update_prompted_version_code",
        "update_mode_choice_code",
        "update_chosen_package",
        "update_downloaded_version_code",
        "update_downloaded_path",
    ]:
        check(f"运行期状态键存在：{key}", key in keys, True)

    print()
    if failures:
        print(f"FAILED ({len(failures)}): {failures}")
        return 1
    print("All update-prefs / timing checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
