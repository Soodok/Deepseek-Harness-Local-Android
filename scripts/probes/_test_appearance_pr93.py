#!/usr/bin/env python3
"""锁定 PR #93（个性化更新）里几处已修的回归点。

这些 bug 都属"静态可查、运行时难复现"：长按被 OnTouchListener 吃掉、
GradientDrawable 共享实例被连带改色、缓存命中还去解码原图。
一旦有人把它们改回原样，这里立刻变红。
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/app/dsh/mobile"

checks = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))


def read(p):
    return p.read_text(encoding="utf-8")


# ── 1. 状态栏自定义（贴纸/圆点图）已按用户要求整体移除 ────────────────────
# v1.7.6：删掉「外观与显示 → 状态栏」整项。原先这里是一整套贴纸长按删除、
# 拖动定位、圆点渲染的断言；功能删除后改成"确认删干净"，否则断言会因为文件
# 不存在而 FileNotFoundError（而不是给出可读的失败原因）。
for gone in ("ExpandedStatusBarActivity.kt", "StatusBarActivity.kt",
             "IslandStatusActivity.kt", "StickerPref.kt", "IslandDotRenderer.kt"):
    check("%s removed" % gone, not (APP / gone).exists())

# ── 2. dimCards 必须 mutate() 后再改色 ──────────────────────────────────
themed = read(APP / "ThemedActivity.kt")
dim_body = themed[themed.index("private fun dimCards"):]
dim_body = dim_body[: dim_body.index("private fun restoreCardOpacity")]
check(
    "dimCards mutates the drawable before setColor",
    "background?.mutate()" in dim_body and "v.background = bg" in dim_body,
    "shared GradientDrawable instances would be recolored across pages",
)
restore_body = themed[themed.index("private fun restoreCardOpacity"):]
check(
    "restoreCardOpacity mutates too",
    "background?.mutate()" in restore_body[: restore_body.index("private fun eachView")],
)

# ── 3. cachedBlur 必须先查缓存再解码原图 ────────────────────────────────
imagefx = read(APP / "ImageFx.kt")
blur = imagefx[imagefx.index("fun cachedBlur("):]
blur = blur[: blur.index("/** 把最长边限制到")]
i_cache = blur.index("cached.isFile")
i_decode = blur.index("decodeFile(srcFile.absolutePath)")
check(
    "cachedBlur checks the cache file before decoding the source",
    i_cache < i_decode,
    "decoding a 4000x3000 album photo just to hit cache wastes tens of MB",
)

# ── 4. sampleSizeFor 必须同时看宽和高 ───────────────────────────────────
pref = read(APP / "CustomVisualPref.kt")
sample = pref[pref.index("private fun sampleSizeFor"):]
sample = sample[: sample.index("private fun clear(")]
check(
    "sampleSizeFor honours both maxW and maxH",
    "maxH > 0 && nh < maxH" in sample and "maxW <= 0" not in sample,
    "island icon import passes 216x216; old code never checked height",
)

# ── 5. 引导页的状态与监听（原有约束，防回退） ────────────────────────────
ob = read(APP / "OnboardingActivity.kt")
check(
    "onboarding saves current wizard state and persists dynamic color separately",
    all(k in ob for k in ("KEY_PAGE", "KEY_LANG", "KEY_ORIENTATION", "KEY_THEME", "KEY_PRIV"))
    and "ThemeAccent.setDynamicEnabled" in ob,
)
check(
    "onboarding pairs the Shizuku listener",
    "addRequestPermissionResultListener" in ob and "removeRequestPermissionResultListener" in ob,
)
check("onboarding back key commits instead of stalling", "override fun onBackPressed()" in ob)

# ── 6. Manifest：新页面一律不导出 ───────────────────────────────────────
manifest = read(ROOT / "app/src/main/AndroidManifest.xml")
seg = manifest[manifest.index('android:name=".SettingsBgActivity"'):]
seg = seg[: seg.index("/>")]
check("SettingsBgActivity is not exported", 'android:exported="false"' in seg)

# v1.7.6：用户要求删掉「外观与显示 → 状态栏」整项（含展开态背景图、贴纸、
# 收起态图标/形状/尺寸）。下面三条钉住"删干净了"，防止以后有人把入口加回来
# 而代码侧其实已经不通（删除后的空壳比不存在更糟）。
for act in ("StatusBarActivity", "ExpandedStatusBarActivity", "IslandStatusActivity"):
    check("%s removed from manifest" % act, 'android:name=".%s"' % act not in manifest)
    check("%s source removed" % act, not (APP / (act + ".kt")).exists())
for gone in ("StickerPref.kt", "IslandDotRenderer.kt"):
    check("%s removed" % gone, not (APP / gone).exists())
for gone in ("activity_status_bar.xml", "activity_status_expanded.xml", "activity_island_status.xml"):
    check("%s removed" % gone, not (ROOT / "app/src/main/res/layout" / gone).exists())
appearance = read(ROOT / "app/src/main/res/layout/activity_appearance.xml")
check("status bar entry removed from appearance page", "rowIslandDotImage" not in appearance)

failed = [c for c in checks if not c[1]]
for name, ok, detail in checks:
    print(("  ok    " if ok else "  FAIL  ") + name + (("\n         → " + detail) if not ok and detail else ""))
print()
if failed:
    print("%d/%d checks FAILED" % (len(failed), len(checks)))
    sys.exit(1)
print("All appearance checks passed (%d checks)." % len(checks))
