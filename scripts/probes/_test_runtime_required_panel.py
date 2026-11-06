# -*- coding: utf-8 -*-
"""运行时必选面板 + 进度条比例契约锁（v1.6.18）。

## 锁的三个用户报障

1. **「下载运行时的提示会连续出现两次」**
   `showRuntimeSetupDialog()` 旧实现每次都 `AlertDialog.Builder(...).show()`，
   而它被 `onResume()` 无条件调用 —— 切前台、亮屏、从设置页返回都会再弹一个。
   断言：持有 `runtimeSetupDialog` 引用并在入口判 `isShowing`（同 `gateDialog` 范式）。

2. **「用户不下载就一直卡在界面不动」**
   旧面板有「稍后」按钮（`setNegativeButton(..., null)`，点它什么都不做），
   返回键/点外部也能关掉。关掉后引擎不会被拉起，启动遮罩永远等不到 READY。
   断言：`setCancelable(false)` + `setCanceledOnTouchOutside(false)` +
   **不存在 negative button** + `runtime_setup_later` 字符串已删除。

3. **「才下载 10MB 那个（进度条）就跑满了」**
   `ProgressBar` 的 `android:max` 默认是 100，而代码写的是
   `p.progress = (frac * 10000).toInt()` —— 1% 就超过 max 直接满格。
   断言：两个 ProgressBar 都显式 `android:max="10000"`，且代码里没有别处
   把 progress 写成 0..1 或 0..100 的混合口径。

## 为什么断言「不存在 negative button」而不只是 setCancelable

`setCancelable(false)` 只挡返回键与点外部。**负面按钮仍然可点**，而且点了之后
`AlertDialog` 会自动 dismiss —— 那正是「点『稍后』→ 界面卡死」那条路径。
两个都要锁。
"""

import io
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAIN = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/MainActivity.kt")
LAYOUT = os.path.join(ROOT, "app/src/main/res/layout/activity_main.xml")
STRINGS = os.path.join(ROOT, "app/src/main/res/values/strings.xml")

checks = []


def check(name, cond):
    checks.append((name, bool(cond)))


def read(p):
    return io.open(p, encoding="utf-8").read()


main_src = read(MAIN)
layout = read(LAYOUT)

# ── 1. 判重（不再弹两次） ────────────────────────────────────────────
check(
    "holds a runtime setup dialog reference",
    "private var runtimeSetupDialog: AlertDialog?" in main_src,
)
check(
    "bails out when the dialog is already showing",
    "if (runtimeSetupDialog?.isShowing == true) return" in main_src,
)
check(
    "assigns the reference before showing",
    re.search(r"runtimeSetupDialog = dialog\s*\n\s*dialog\.show\(\)", main_src) is not None,
)
# 旧实现的无条件 show 必须消失
check(
    "no unconditional Builder().show() left in setup dialog",
    ".setNegativeButton(R.string.runtime_setup_later, null)" not in main_src,
)

# ── 2. 不可取消、无负面出口 ─────────────────────────────────────────
check("dialog is not cancelable", ".setCancelable(false)" in main_src)
check(
    "dialog ignores outside taps",
    "setCanceledOnTouchOutside(false)" in main_src,
)
check(
    "dialog swallows the back key",
    "KEYCODE_BACK" in main_src and "setOnKeyListener" in main_src,
)
check(
    "no negative button in the runtime panel",
    "setNegativeButton" not in main_src.split("private fun showRuntimeSetupDialog")[1].split("private fun ")[0],
)
# 「稍后」文案必须彻底删掉，否则后续很容易被再加回来
for loc in ["values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"]:
    p = os.path.join(ROOT, "app/src/main/res/%s/strings.xml" % loc)
    if not os.path.isfile(p):
        continue
    check("%s has no runtime_setup_later" % loc, "runtime_setup_later" not in read(p))

# 「必选」语义必须写进文案，不能只是去掉按钮
vals = read(STRINGS)
i = vals.find('name="runtime_setup_message"')
check("message states the step is required", i > 0 and "required" in vals[i:i + 600])
check(
    "title says runtime is required to continue",
    re.search(r'name="runtime_setup_title">[^<]*required', vals) is not None,
)

# ── 3. 进度条比例 ───────────────────────────────────────────────────
# 两个 ProgressBar 都必须显式 max=10000（代码按 *10000 写入）
for pid in ("installProgress", "splashProgress"):
    m = re.search(
        r'android:id="@\+id/%s"[\s\S]{0,400}?android:max="10000"' % re.escape(pid),
        layout,
    )
    check("%s declares max=10000" % pid, m is not None)

# 代码里的 progress 写入口径必须都是 *10000（不能混 0..1 或 0..100）
writes = re.findall(r"\.progress\s*=\s*([^\n]+)", main_src)
check("there is at least one progress write", len(writes) > 0)
bad = [w for w in writes if "10000" not in w]
check("every progress write uses the 0..10000 scale", not bad)

# ── 输出 ────────────────────────────────────────────────────────────
passed = sum(1 for _, ok in checks if ok)
failed = [n for n, ok in checks if not ok]
for n, ok in checks:
    print(("  PASS  " if ok else "  FAIL  ") + n)
print()
print("%d/%d checks passed" % (passed, len(checks)))
if failed:
    print("FAILED:", ", ".join(failed))
    sys.exit(1)
print("All runtime panel checks passed.")
