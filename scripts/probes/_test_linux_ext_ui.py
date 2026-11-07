#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""扩展中心 Linux 模式接线的静态自检。

## 为什么需要这个探针

`LinuxExtensionBridge` / `LinuxPackageManager` 是 Linux 容器模式的包管理地基，
但它们曾经**写好了却没人调用** —— `ExtensionStoreActivity` 里零引用，
Linux 模式下点「安装」走的还是 Termux 的 `.deb` 下载链路，用户看到的是
「Download failed」而不是「装不上包」。这种「实现了但没接线」的缺陷
编译不报错、UI 不崩溃，只能靠静态断言发现。

所以本探针检查四件事：

1. **接线完整性**：Activity 必须真的引用了 bridge 与 package manager，
   且状态查询、安装、卸载三条路径都分了模式。
2. **状态查询统一**：不能有任何一处直接调 `manager.state(...)` 绕过
   `stateOf(...)`（否则 Linux 模式下那一行会显示原生模式的状态）。
3. **文案语义**：Linux 模式不能复用「Download / Activated」这类 Termux 语义，
   必须有独立的 `ext_linux_*` 字符串，且五语齐全。
4. **不可用条目**：`unavailableReason` 非空的条目在 Linux 模式下必须渲染成
   「不可用」而不是可点的「安装」。

## 与 `_test_linux_ext_bridge.py` 的分工

那个探针管**映射表本身对不对**（包名是否真实存在于 Alpine 索引）；
本探针管**映射表有没有被真正用上**。两者互补，缺一不可：映射表全对但没接线，
用户依然装不上任何东西。

## 退出码

0 = 全部通过；1 = 有失败项。
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ACT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/ExtensionStoreActivity.kt")
BRIDGE = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LinuxExtensionBridge.kt")
PKGMGR = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LinuxPackageManager.kt")
RES = os.path.join(ROOT, "app/src/main/res")

LOCALES = ["values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"]

checks = 0
failed = 0


def ok(name, cond, detail=""):
    global checks, failed
    checks += 1
    if cond:
        print("  [OK]   %s" % name)
    else:
        failed += 1
        print("  [FAIL] %s%s" % (name, ("  <- " + detail) if detail else ""))


def read(p):
    return io.open(p, encoding="utf-8").read()


def main():
    if not os.path.exists(ACT):
        print("missing: %s" % ACT)
        return 1
    act = read(ACT)
    bridge = read(BRIDGE)
    pkgmgr = read(PKGMGR)

    print("\n[A] 地基文件存在且暴露了必需接口")
    ok("LinuxExtensionBridge.kt exists", os.path.exists(BRIDGE))
    ok("LinuxPackageManager.kt exists", os.path.exists(PKGMGR))
    for fn in ("alpinePackagesOf", "unavailableReason", "state", "install", "remove"):
        ok("bridge has fun %s" % fn, ("fun %s" % fn) in bridge)
    for fn in ("install", "remove", "installed"):
        ok("pkgManager has fun %s" % fn, ("fun %s" % fn) in pkgmgr)

    print("\n[B] ExtensionStoreActivity 真的接上了线")
    ok("imports EngineModeSelector", "import app.dsh.mobile.engine.EngineModeSelector" in act)
    ok("imports LinuxExtensionBridge", "import app.dsh.mobile.engine.LinuxExtensionBridge" in act)
    ok("imports LinuxPackageManager", "import app.dsh.mobile.engine.LinuxPackageManager" in act)
    ok("declares linuxMode", re.search(r"private val linuxMode\b", act) is not None)
    ok("declares pkgManager", "LinuxPackageManager.get(this)" in act)
    ok("declares linuxInstalled cache", "linuxInstalled" in act)
    ok("declares stateOf()", "private fun stateOf(" in act)
    ok("declares linuxInstall()", "private fun linuxInstall(" in act)
    ok("declares linuxRemove()", "private fun linuxRemove(" in act)

    print("\n[C] 所有状态查询都经过 stateOf（不得绕过）")
    # 允许出现在 stateOf 自身实现里的那一处 manager.state
    direct = [m.start() for m in re.finditer(r"manager\.state\(", act)]
    allowed = 0
    for pos in direct:
        head = act[max(0, pos - 200):pos]
        if "if (!linuxMode) " in head:
            allowed += 1
    ok(
        "no state query bypasses stateOf",
        len(direct) == allowed == 1,
        "direct manager.state() calls=%d, allowed=%d (expected exactly 1, inside stateOf)"
        % (len(direct), allowed),
    )
    ok("refreshRow uses stateOf", "val state = stateOf(ext)" in act)
    ok("onAction dispatches on stateOf", "when (stateOf(ext))" in act)

    print("\n[D] 三条动作路径都分了模式")
    ok("onAction has linux branch", "if (linuxMode) {" in act and "linuxRemove(ext)" in act)
    ok("onAction linux install path", act.count("linuxInstall(ext)") >= 2)
    ok("confirmUninstall has linux branch", act.count("if (linuxMode)") >= 4)
    ok("installAllMissing has linux branch", "ext-linux-install-all" in act)
    ok("repairBroken has linux branch", "ext-linux-repair" in act)
    ok("refreshHeader has linux branch", "ext_subtitle_linux" in act)
    ok("refreshRow handles unavailable", "linuxUnavailable" in act)

    print("\n[E] Linux 模式不复用 Termux 语义的文案")
    for key in (
        "ext_subtitle_linux",
        "ext_linux_unavailable",
        "ext_linux_installing",
        "ext_linux_install_done",
        "ext_linux_install_failed",
        "ext_linux_remove_failed",
        "ext_linux_removing",
        "ext_linux_uninstall_msg",
        "ext_linux_reinstall_msg",
        "ext_linux_state_none",
        "ext_linux_state_installed",
        "ext_linux_state_partial",
        "ext_linux_state_unavailable",
        "ext_linux_action_install",
        "ext_linux_action_remove",
        "ext_linux_action_unavailable",
    ):
        ok("activity references R.string.%s" % key, ("R.string.%s" % key) in act)

    print("\n[F] 五语齐全（含 th）")
    declared = {}
    for loc in LOCALES:
        p = os.path.join(RES, loc, "strings.xml")
        if not os.path.exists(p):
            ok("locale %s exists" % loc, False)
            continue
        s = read(p)
        declared[loc] = set(re.findall(r'<string name="([^"]+)"', s))
        ok("locale %s has ext_linux_* set" % loc,
           len([k for k in declared[loc] if k.startswith("ext_linux_")]) >= 16,
           "found %d" % len([k for k in declared[loc] if k.startswith("ext_linux_")]))

    base = declared.get("values", set())
    for loc in LOCALES[1:]:
        if loc not in declared:
            continue
        missing = {k for k in base if k.startswith("ext_linux_")} - declared[loc]
        ok("locale %s covers all ext_linux_* keys" % loc, not missing,
           "missing: %s" % ", ".join(sorted(missing)))

    print("\n[G] 历史坑回归")
    # 坑 1：曾经把 linuxMode 判成「编译期常量」导致切模式后不生效
    ok("linuxMode is lazy (survives activity recreate)",
       re.search(r"private val linuxMode[^\n]*by lazy", act) is not None)
    # 坑 2：曾经每行都调 apk info -v，20 行 = 20 次容器进程 → 页面卡死
    ok("installed snapshot taken once in buildList",
       "if (linuxMode) linuxInstalled = runCatching { pkgManager.installed() }" in act)
    # 坑 3：装完不刷新快照 → 状态点不更新
    ok("snapshot refreshed after install",
       act.count("linuxInstalled = runCatching { pkgManager.installed() }") >= 3)
    # 坑 4：不可用条目曾经仍可点，得到 ERROR: unable to select packages
    ok("unavailable action is disabled",
       "refs.isClickable = false" in act or "refs.action.isClickable = false" in act)

    print("\n[H] Linux 下载通道开关")
    # 2026-11-07 按用户要求开启：容器内 apk add 主链路已在**模拟器**实测通过
    # （apk update 26337 包 / apk add sway 91 包 360MiB / 装完能真开窗）。
    # 断言随之改为「已开启」，并继续守 linuxMode 真的引用它、无旁路直判模式。
    m = re.search(r'const val EXT_LINUX_DOWNLOAD_ENABLED = (true|false)', act)
    ok("declares EXT_LINUX_DOWNLOAD_ENABLED", m is not None)
    if m:
        ok("download channel is enabled", m.group(1) == "true",
           "current value: %s" % m.group(1))
    ok("linuxMode honours the switch",
       re.search(r'EXT_LINUX_DOWNLOAD_ENABLED\s*&&\s*\n?\s*EngineModeSelector\.isLinuxEnabled', act)
       is not None)
    # 关掉开关后 linuxMode 恒 false，所有 if (linuxMode) 分支都是死代码——
    # 前提是**没有别的地方绕过 linuxMode 直接判模式**（否则关了开关仍会走容器）
    ok("no bypass: isLinuxEnabled called only inside linuxMode declaration",
       act.count("EngineModeSelector.isLinuxEnabled(this)") == 1,
       "found %d call sites" % act.count("EngineModeSelector.isLinuxEnabled(this)"))
    # 同理，包管理器/桥接类只能被 linuxMode 分支用到，不能被独立 if 判
    ok("no bypass: LinuxExtensionBridge guarded by linuxMode",
       act.count("LinuxExtensionBridge.") > 0
       and "if (!linuxMode) manager.state(ext.id)" in act)

    print("\n" + "-" * 68)
    print("checks=%d failed=%d" % (checks, failed))
    if failed:
        print("RESULT: FAILED")
        return 1
    print("RESULT: PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
