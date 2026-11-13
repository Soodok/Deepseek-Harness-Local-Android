#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守「Linux 模式下健康检查/修复必须按模式分派」这条不变量。

## 为什么需要这个探针（v1.5.1 审查发现的真实缺口）

`EXT_LINUX_DOWNLOAD_ENABLED` 打开后，扩展中心在容器模式下才真正可用。但当时
「一键检测」与它弹窗里的「修复」按钮**仍走 Termux 的老路径**：

```kotlin
private fun runHealthCheck() {
    val health = manager.checkHealth()   // ← 看 engine/extensions/<id>/MARKER
    ...
    setNeutralButton(...) { _, _ -> confirmRepair(broken) }  // ← manager.enqueue
}
```

`ExtensionManager.checkHealth()` 判断「装没装」的依据是 **MARKER 文件**，那是
Termux `.deb` 解包体系的产物。Linux 模式把包装进 rootfs 的全局位置（`apk add` /
`apt-get`），**根本不会创建 MARKER**。后果：

1. `installed` 恒为空 → 用户点「一键检测」永远看到「还没有装任何扩展」，
   哪怕他刚在同一页装成功了五个。这是**功能说谎**，不是小瑕疵。
2. 万一有结果，点「修复」会 `manager.enqueue()` —— 走 Termux 的下载解包队列，
   把 `.deb` 解到 `engine/extensions/`，而容器里的包库毫无变化。里外两套。

## 修法（本探针守的就是它）

- `runHealthCheck()` 开头按 `linuxMode` 分派到 `runLinuxHealthCheck()`。
- `runLinuxHealthCheck()` 只问 `pkgManager.installed()`，用与行标签**同一个**
  状态源（`LinuxExtensionBridge.state(..., linuxFamily)`），否则会出现
  「弹窗说可用、行上显示不完整」的自相矛盾。
- 它的「修复」按钮调 `repairBroken()`（已有 Linux 分支），**不调** `confirmRepair`。
- `confirmRepair()` 自己加一道防线：Linux 模式直接转 `repairBroken()` 并打日志，
  防止将来别处误调。

## 反向验证过的断言

把 `if (linuxMode) { runLinuxHealthCheck(); return }` 删掉 → `[A1]` 变红；
把 `repairBroken()` 换成 `confirmRepair(...)` → `[A4]` 变红。

跑法：python scripts/probes/_test_linux_ext_health.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ACT = os.path.join(
    ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "ExtensionStoreActivity.kt")

checks = 0
failed = 0


def ok(cond, msg, detail=""):
    global checks, failed
    checks += 1
    if cond:
        print("  [OK]   " + msg)
    else:
        failed += 1
        print("  [FAIL] " + msg + ((" - " + detail) if detail else ""))


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def strip_comments(src):
    """去掉行注释，避免「注释里提到某函数」被误判成「真的调用了它」。

    这个坑很实在：runLinuxHealthCheck 的正文里有一句
        // 绝不走 confirmRepair()（那是 Termux 下载队列）
    而断言 [C1] 检查的正是「正文不得出现 confirmRepair」—— 不剥注释就会把
    「声明不做」读成「做了」，探针直接假红。
    """
    return re.sub(r"//[^\n]*", "", src)


def fun_body(src, name):
    """抠出 `private fun <name>(...) {` 到匹配的 `    }` 之间的函数体。

    这个项目里 Activity 的方法都在 4 空格缩进层，闭合 `    }` 唯一可靠，
    比数花括号简单且够用（方法内不会有同缩进的 `}`）。
    """
    m = re.search(
        r"private fun\s+" + re.escape(name) + r"\s*\([^)]*\)\s*\{(.*?)\n    \}",
        src, re.S)
    return strip_comments(m.group(1)) if m else None


def main():
    print("=" * 68)
    print("Extension store: Linux-mode health check / repair dispatch")
    print("=" * 68)

    if not os.path.isfile(ACT):
        print("missing: %s" % ACT)
        return 1
    act = read(ACT)

    print("\n[A] 检测入口必须分派")
    rh = fun_body(act, "runHealthCheck")
    ok(rh is not None, "[A0] runHealthCheck() body extracted")
    if rh is None:
        return finish()
    ok("if (linuxMode) { runLinuxHealthCheck(); return }" in rh,
       "[A1] runHealthCheck dispatches to runLinuxHealthCheck on linuxMode",
       "Linux mode would fall through to manager.checkHealth() (MARKER-based)")
    # 分派必须在 manager.checkHealth() 之前（否则已经跑完原生路径了）
    if "manager.checkHealth()" in rh:
        ok(rh.index("runLinuxHealthCheck") < rh.index("manager.checkHealth()"),
           "[A2] dispatch happens BEFORE manager.checkHealth()",
           "calling the native check first defeats the dispatch")
    else:
        ok(False, "[A2] native path still exists (sanity: activity really parsed)")

    lh = fun_body(act, "runLinuxHealthCheck")
    ok(lh is not None, "[A3] runLinuxHealthCheck() exists")

    print("\n[B] Linux 检测只能问包数据库，且与行标签同源")
    if lh is not None:
        ok("pkgManager.installed()" in lh or "pkgManager.installed()" in act,
           "[B1] queries pkgManager.installed() (apk/dpkg database)",
           "must not rely on MARKER files")
        # v1.7.12：健康检查与行标签必须**同一个入口**（现在是 stateOf() →
        # ExtensionVersionPlan 的条目级判定）。旧断言锁的是 LinuxExtensionBridge.state(，
        # 而那个函数的语义是「选中的那一版装没装」，正是这次要修的 bug 来源。
        ok("stateOf(it)" in lh or "stateOf(" in lh,
           "[B2] uses stateOf() — the exact same entry-level judge as the row labels",
           "dialog and row would otherwise contradict each other")
        ok("linuxFamily" in lh,
           "[B3] passes linuxFamily (distro-aware)")
        ok("manager.checkHealth()" not in lh,
           "[B4] does NOT call manager.checkHealth()",
           "that is the Termux MARKER-based checker")
        # 三态口径：可用 vs 不完整
        ok("ACTIVATED" in lh and "DOWNLOADED" in lh,
           "[B5] distinguishes healthy (ACTIVATED) from partial (DOWNLOADED)")

    print("\n[C] Linux 的「修复」不能走 Termux 队列")
    if lh is not None:
        ok("confirmRepair" not in lh,
           "[C1] does not call confirmRepair (Termux download queue)",
           "would unpack .deb into engine/extensions instead of the container")
        ok("repairBroken()" in lh,
           "[C2] repairs via repairBroken() — which has a Linux branch")

    print("\n[D] 双保险：confirmRepair 自己也要挡住 Linux 模式")
    cr = fun_body(act, "confirmRepair")
    ok(cr is not None, "[D0] confirmRepair() body extracted")
    if cr is not None:
        ok("if (linuxMode)" in cr,
           "[D1] confirmRepair guards on linuxMode",
           "a future caller could still push Termux downloads")
        ok("repairBroken()" in cr,
           "[D2] the guard delegates to repairBroken() instead of silently proceeding")
        # 防线必须在 enqueue 之前
        if "manager.enqueue" in cr:
            ok(cr.index("if (linuxMode)") < cr.index("manager.enqueue"),
               "[D3] guard sits before manager.enqueue()",
               "guard after enqueue would be dead code")

    print("\n[E] repairBroken 的 Linux 分支不能被这次改动弄丢")
    rb = fun_body(act, "repairBroken")
    ok(rb is not None, "[E0] repairBroken() body extracted")
    if rb is not None:
        ok("if (linuxMode)" in rb, "[E1] repairBroken keeps its Linux branch")
        ok("pkgManager.install(" in rb,
           "[E2] Linux branch re-installs via pkgManager (apk/apt)",
           "the Linux repair must go through the distro package manager")

    print("\n[F] 反向自检：正则真的能识别这些形态")
    fake_bad = "private fun runHealthCheck() {\n        val h = manager.checkHealth()\n    }"
    fb = fun_body(fake_bad, "runHealthCheck")
    ok(fb is not None and "runLinuxHealthCheck" not in fb,
       "[F1] a non-dispatching body is correctly seen as missing the dispatch "
       "(guard against a dead regex)")

    return finish()


def finish():
    print("\n" + "-" * 68)
    print("checks=%d failed=%d" % (checks, failed))
    if failed:
        print("RESULT: FAILED")
        return 1
    print("RESULT: PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
