# -*- coding: utf-8 -*-
"""启动页重做契约锁（v1.6.19）。

## 锁什么

上一版启动页的教训是：**同一块进度条被两条路径写**，而且渲染逻辑散在
Activity 里三处（ticker / 订阅 / 展开步骤各写一遍），改一处要对照三处。
这次重做把状态收进 `SplashController`，这条探针把「不许再长回去」钉住。

四条：

1. **单一数据源**：遮罩的比例只来自 `BootTrace.Install.fraction`，
   `renderProgress()` 不许再写遮罩。
2. **阶段切换清零**：`BootTrace.stage()` 进入传输阶段时把 `install` 归零，
   否则会出现「正在解压 + 下载的 100%」。
3. **环的时间/比例语义**：确定态弧长 = 真实比例（不虚构缓慢增长），
   不确定态是短弧（≤120°），不能是整圈。
4. **动画必须能停**：系统关动画、退到后台、View 脱离窗口都要停 ——
   启动页是最容易留下空转 Animator 的地方。

## 为什么断言「弧长 = 360 * frac」

如果改成 `360 * 0.9f * frac`（假缓慢增长）会显得更「顺滑」，但那是在骗用户：
真实进度 50% 时环只画 45%，用户据此判断要等多久会失准。这条断言就是防这种
「体验优化」把真实性吃掉。
"""

import io
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAVA = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile")

MAIN = os.path.join(JAVA, "MainActivity.kt")
CTRL = os.path.join(JAVA, "ui/SplashController.kt")
RING = os.path.join(JAVA, "ui/SplashRingView.kt")
BOOT = os.path.join(JAVA, "engine/BootTrace.kt")
LAYOUT_MAIN = os.path.join(ROOT, "app/src/main/res/layout/activity_main.xml")
LAYOUT_SPLASH = os.path.join(ROOT, "app/src/main/res/layout/view_boot_splash.xml")

checks = []


def check(name, cond):
    checks.append((name, bool(cond)))


def read(p):
    return io.open(p, encoding="utf-8").read()


def code_only(text):
    out = []
    for line in text.splitlines():
        t = line.strip()
        if t.startswith("//") or t.startswith("*") or t.startswith("/*"):
            continue
        if "//" in line:
            line = line.split("//", 1)[0]
        out.append(line)
    return chr(10).join(out)


for p in (MAIN, CTRL, RING, BOOT, LAYOUT_MAIN, LAYOUT_SPLASH):
    check("exists: %s" % os.path.basename(p), os.path.isfile(p))

main_src = read(MAIN)
ctrl = read(CTRL)
ring = read(RING)
boot = read(BOOT)
layout_main = read(LAYOUT_MAIN)
layout_splash = read(LAYOUT_SPLASH)

main_code = code_only(main_src)
ctrl_code = code_only(ctrl)
ring_code = code_only(ring)

# ── 1. 单一数据源 ───────────────────────────────────────────────────
check("splash layout moved into its own file", "view_boot_splash" in layout_main)
check("activity includes the new layout", 'layout="@layout/view_boot_splash"' in layout_main)
check(
    "MainActivity no longer holds splash text views",
    all(
        name not in main_code
        for name in ("splashStatus", "splashElapsed", "splashDetail", "splashSteps", "splashProgress")
    ),
)
# renderProgress 不许再写遮罩（旧实现的双写点）
i_rp = main_code.find("private fun renderProgress(")
check("renderProgress exists", i_rp > 0)
j = main_code.find("\n    private fun ", i_rp + 10)
rp_body = main_code[i_rp: j if j > 0 else i_rp + 2000]
check("renderProgress does not touch the splash overlay", "splash" not in rp_body.lower())
check("renderProgress still drives the toolbar bar", "installProgress" in rp_body)

check(
    "controller reads fraction from BootTrace.Install",
    "install.fraction" in ctrl_code,
)
check(
    "controller only shows determinate progress while transferring",
    "Stage.DOWNLOADING" in ctrl_code and "Stage.EXTRACTING" in ctrl_code,
)

# ── 2. 阶段切换清零 ─────────────────────────────────────────────────
i_st = boot.find("fun stage(stage: Stage)")
check("BootTrace.stage exists", i_st > 0)
k = boot.find("\n    fun ", i_st + 10)
st_body = boot[i_st: k if k > 0 else len(boot)]
check("stage() resets install on transfer stages", "resetInstall" in st_body)
check(
    "stage() assigns a fresh Install",
    "install = if (resetInstall) Install() else cur.install" in st_body,
)

# ── 3. 环的语义 ─────────────────────────────────────────────────────
check("ring draws a full track first", "canvas.drawArc(rect, 0f, 360f" in ring_code)
check("determinate sweep equals the real fraction", "360f * frac" in ring_code)
check("no fake easing on the determinate arc", "0.9f * frac" not in ring_code)
check("indeterminate sweep is a short arc", "SWEEP_DEG = 90f" in ring_code)
check("indeterminate arc is not a full circle", "SWEEP_DEG = 360f" not in ring_code)

# ── 4. 动画必须能停 ─────────────────────────────────────────────────
check("ring stops when detached", "onDetachedFromWindow" in ring_code and "cancelAnimator()" in ring_code)
check("ring respects system animator setting", "areAnimatorsEnabled()" in ring_code)
check("ring stops on window visibility change", "onWindowVisibilityChanged" in ring_code)
check("controller exposes stopMotion", "fun stopMotion()" in ring_code)
check("controller calls stopMotion before fading out", "ring.stopMotion()" in ctrl_code)
check(
    "controller removes its callbacks on dispose",
    ctrl_code.count("root.removeCallbacks(") >= 3,
)

# ── 5. 最短展示时长与重栅格化窗口 ───────────────────────────────────
check(
    "controller keeps the 180ms re-raster window",
    "+ 180L" in ctrl_code,
)
check("dismiss is idempotent in activity", "if (splashDismissed) return" in main_code)

# ── 6. 主题与尺寸 ───────────────────────────────────────────────────
check("splash palette is decoupled from accent", "splash_text" in read(
    os.path.join(ROOT, "app/src/main/res/values/colors.xml")))
check("night palette defines the same keys", "splash_text" in read(
    os.path.join(ROOT, "app/src/main/res/values-night/colors.xml")))
check("no hardcoded card container in the splash layout", "splash_card" not in layout_splash)
check("splash body is width-capped in code", "dp(420)" in ctrl_code or "dp(420)" in ctrl)

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
print("All splash redesign checks passed.")
