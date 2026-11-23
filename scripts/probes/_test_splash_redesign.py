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
check("ring uses the Material circular indicator", "CircularProgressIndicator" in layout_splash)
check("determinate progress uses the real fraction", "val progress = fraction ?: 0.25f" in ctrl_code and "progress * 10000" in ctrl_code)
check("indeterminate mode reflects unknown progress", "fraction == null && !failed" in ctrl_code)

# ── 4. 动画必须能停 ─────────────────────────────────────────────────
check("controller hides the indicator when closing", "ring.visibility = View.INVISIBLE" in ctrl_code)
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
check("splash background uses semantic surface", 'android:background="?attr/colorSurface"' in layout_splash)
check("splash text uses semantic roles", '?attr/colorOnSurface' in layout_splash)
check("no hardcoded card container in the splash layout", "splash_card" not in layout_splash)
check("splash body is width-capped in code", "dp(420)" in ctrl_code or "dp(420)" in ctrl)

# ── 7. 鲸鱼在浅色模式下必须可见 ───────────────────────────────────────
#
# 鲸鱼图形 ic_launcher_foreground 是官方 logo 的白色 path，与桌面图标共用，
# 不能改。而浅色启动背景是近白渐变（#F8FBFF -> #E4EBF3），白鲸落在上面实测
# 对比度只有 1.04:1 —— 等于看不见，用户看到的是空白加一个环。
#
# 所以 layout 上必须有 tint，且 tint 颜色必须 day/night 分叉：
# 默认（浅色）是蓝色，night（深色）是白色。
# 下面用相对亮度算真实对比度，而不是断言「有个颜色名」—— 后者在有人把
# 浅色值也改成白色时照样全绿。
_LAYOUT_SPLASH = read(os.path.join(
    ROOT, "app/src/main/res/layout/view_boot_splash.xml"))
check("splash logo is tinted (not left at the shared white vector)",
      re.search(r'android:id="@\+id/splashLogo"[^>]*android:tint=', _LAYOUT_SPLASH, re.S)
      is not None
      or re.search(r'android:tint="@color/splash_logo_tint"', _LAYOUT_SPLASH) is not None)
check("splash logo uses the on-surface role",
      'android:tint="?attr/colorOnSurface"' in _LAYOUT_SPLASH)


def _hex_of(name, path):
    m = re.search(r'<color name="%s">#([0-9A-Fa-f]{6,8})</color>' % re.escape(name), read(path))
    if not m:
        return None
    h = m.group(1)
    return h[2:] if len(h) == 8 else h   # AARRGGBB -> RRGGBB


def _rel_lum(rgb):
    r, g, b = (int(rgb[i:i + 2], 16) / 255.0 for i in (0, 2, 4))
    f = lambda c: c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)


def _contrast(a, b):
    la, lb = _rel_lum(a), _rel_lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


_val = os.path.join(ROOT, "app/src/main/res/values/colors.xml")
_night = os.path.join(ROOT, "app/src/main/res/values-night/colors.xml")
_light_tint = _hex_of("splash_logo_tint", _val)
_night_tint = _hex_of("splash_logo_tint", _night)
check("splash_logo_tint is defined in both palettes",
      _light_tint is not None and _night_tint is not None)

import xml.etree.ElementTree as ET
for qualifier in ("values", "values-night"):
    palette = ET.parse(os.path.join(ROOT, "app/src/main/res", qualifier, "md3_palettes.xml"))
    for style in palette.getroot().findall("style"):
        roles = {item.get("name"): (item.text or "").strip().lstrip("#")
                 for item in style.findall("item")}
        surface, logo = roles.get("colorSurface"), roles.get("colorOnSurface")
        check("%s %s logo contrast >=3:1" % (qualifier, style.get("name")),
              bool(surface and logo and _contrast(surface, logo) >= 3.0))

# ── 引导页完成页鲸鱼 ──────────────────────────────────────────────────
# 它底下压着 bg_onboarding_glow 径向光晕，而光晕在运行时被染成用户选的强调色
# （OnboardingActivity.playDoneAnimation 里 selectedAccent.argb）。所以同一只鲸鱼
# 要落在五种光晕上 —— 这里必须按每种强调色各算一遍，只看纯背景是不够的。
_LAYOUT_ONB = read(os.path.join(
    ROOT, "app/src/main/res/layout/activity_onboarding.xml"))
check("onboarding logo is tinted",
      re.search(r'android:id="@\+id/doneLogo"[^>]*android:tint=', _LAYOUT_ONB, re.S)
      is not None)
check("onboarding logo tint does not reuse the splash tint",
      "@color/onboarding_logo_tint" in _LAYOUT_ONB
      and "@color/splash_logo_tint" not in _LAYOUT_ONB)

_onb_light = _hex_of("onboarding_logo_tint", _val)
_onb_night = _hex_of("onboarding_logo_tint", _night)
check("onboarding_logo_tint is defined in both palettes",
      _onb_light is not None and _onb_night is not None)

# 强调色是唯一真源，从 Accent.kt 解析，避免探针里抄一份会漂移的常量
_ACCENT_RE = re.compile(r'\b[A-Z][A-Z_]+\("[a-z]+",\s*R\.string\.\w+,\s*0x[Ff]{2}([0-9A-Fa-f]{6})')
_accents = _ACCENT_RE.findall(read(os.path.join(
    ROOT, "app/src/main/java/app/dsh/mobile/Accent.kt")))
check("probe found all five accent colors in Accent.kt", len(_accents) == 5)


def _composite(bg_hex, fg_hex, alpha):
    """把 fg 以 alpha 叠到 bg 上，返回 #RRGGBB。模拟光晕中心的不透明度。"""
    out = []
    for i in (0, 2, 4):
        b = int(bg_hex[i:i + 2], 16)
        f = int(fg_hex[i:i + 2], 16)
        out.append(round(b * (1 - alpha) + f * alpha))
    return "".join("%02X" % c for c in out)


if _onb_light and _onb_night and _accents:
    _glow = 0.40          # bg_onboarding_glow 的 startColor #66FFFFFF ≈ 40%
    _onb_bg_light = _hex_of("bg", _val)
    _onb_bg_night = _hex_of("bg", _night)
    # 最坏情况是光晕中心（alpha 最高），边缘更透明所以更接近纯背景，不必单独测
    _worst = min(
        _contrast(_onb_light, _composite(_onb_bg_light, a, _glow))
        for a in _accents
    )
    check("light-mode onboarding whale is visible on every accent glow "
          "(>=3:1 worst case; got %.2f:1)" % _worst, _worst >= 3.0)
    _worst_n = min(
        _contrast(_onb_night, _composite(_onb_bg_night, a, _glow))
        for a in _accents
    )
    check("dark-mode onboarding whale is visible on every accent glow "
          "(>=3:1 worst case; got %.2f:1)" % _worst_n,
          _onb_night.upper() == "FFFFFF" and _worst_n >= 3.0)
    # 反向自检：白鲸在浅色光晕上必须被这条规则判为不合格，否则门槛形同虚设
    _white_worst = min(
        _contrast("FFFFFF", _composite(_onb_bg_light, a, _glow)) for a in _accents
    )
    check("the same rule rejects the old white whale on light glows "
          "(%.2f:1)" % _white_worst, _white_worst < 3.0)

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
