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


# ── 1. 贴纸长按删除不能依赖 OnLongClickListener ──────────────────────────
expanded = read(APP / "ExpandedStatusBarActivity.kt")
# 注意：只锚定真正的**调用**（".setOnLongClickListener {"），不能匹配文档注释里
# 提到这个名字的句子 —— 注释解释的正是"为什么不能用它"。
check(
    "sticker long-press is self-implemented (postDelayed), not OnLongClickListener",
    ".setOnLongClickListener" not in expanded and "postDelayed(longPress" in expanded,
    "OnTouchListener returning true swallows the gesture; OnLongClickListener never fires",
)
check(
    "long-press timer is cancelled on move",
    "removeCallbacks(longPress)" in expanded and "touchSlopSq" in expanded,
    "dragging must cancel the pending delete",
)
check(
    "long-press timer is cancelled on up/cancel",
    expanded.count("removeCallbacks(longPress)") >= 2,
    "ACTION_UP/ACTION_CANCEL branch must also cancel",
)
check(
    "sticker ops locate by file name, not list index",
    "removeByFile(" in expanded and "moveByFile(" in expanded and "StickerPref.moveTo(" not in expanded,
    "render() rebuilds the list; a captured index can point at another sticker",
)
sticker = read(APP / "StickerPref.kt")
check("StickerPref.removeByFile exists", "fun removeByFile(" in sticker)
check("StickerPref.moveByFile exists", "fun moveByFile(" in sticker)

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
    "onboarding saves all six state keys",
    all(k in ob for k in ("KEY_PAGE", "KEY_LANG", "KEY_ORIENTATION", "KEY_THEME", "KEY_ACCENT", "KEY_PRIV")),
)
check(
    "onboarding pairs the Shizuku listener",
    "addRequestPermissionResultListener" in ob and "removeRequestPermissionResultListener" in ob,
)
check("onboarding back key commits instead of stalling", "override fun onBackPressed()" in ob)

# ── 6. Manifest：新页面一律不导出 ───────────────────────────────────────
manifest = read(ROOT / "app/src/main/AndroidManifest.xml")
for act in ("IslandStatusActivity", "SettingsBgActivity", "StatusBarActivity", "ExpandedStatusBarActivity"):
    seg = manifest[manifest.index('android:name=".%s"' % act):]
    seg = seg[: seg.index("/>")]
    check("%s is not exported" % act, 'android:exported="false"' in seg)

failed = [c for c in checks if not c[1]]
for name, ok, detail in checks:
    print(("  ok    " if ok else "  FAIL  ") + name + (("\n         → " + detail) if not ok and detail else ""))
print()
if failed:
    print("%d/%d checks FAILED" % (len(failed), len(checks)))
    sys.exit(1)
print("All appearance checks passed (%d checks)." % len(checks))
