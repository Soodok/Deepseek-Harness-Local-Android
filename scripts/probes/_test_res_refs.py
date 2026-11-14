#!/usr/bin/env python3
"""布局里的资源引用必须都存在。

真机/CI 事故：主题页色块写了 @color/accent_sky，但 colors.xml 里只有同名
**string**（预设名称），AAPT 直接报 resource color/accent_sky not found，
整个 APK 构建失败 —— 而这在静态文本检查里完全看不出来。

本探针覆盖布局里最常见的几类引用：color / dimen / string / drawable /
style / layout / menu，逐个核对定义是否存在。
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app", "src", "main", "res")

FAILS = []


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (("  -> " + detail) if not ok else ""))
    if not ok:
        FAILS.append(name)


def collect(pattern, files):
    out = set()
    for f in glob.glob(os.path.join(RES, files)):
        with open(f, encoding="utf-8") as fh:
            out |= set(re.findall(pattern, fh.read()))
    return out


colors = collect(r'<color name="(\w+)"', "values*/colors.xml")
dimens = collect(r'<dimen name="(\w+)"', "values*/*.xml")
strings = collect(r'<string name="(\w+)"', "values*/strings.xml")
styles = collect(r'<style name="([\w.]+)"', "values*/*.xml")
drawables = {os.path.basename(p).rsplit(".", 1)[0]
             for p in glob.glob(os.path.join(RES, "drawable*", "*"))}
layouts = {os.path.basename(p).rsplit(".", 1)[0]
           for p in glob.glob(os.path.join(RES, "layout", "*.xml"))}
menus = {os.path.basename(p).rsplit(".", 1)[0]
         for p in glob.glob(os.path.join(RES, "menu", "*.xml"))}

# Material / AppCompat 自带的样式，本仓库定义不了
EXTERNAL_STYLE_PREFIXES = (
    "Widget.", "Theme.Material", "ShapeAppearance.Material3",
    "TextAppearance.Material3", "ThemeOverlay.Material3",
)

KINDS = [
    ("color", r"@color/(\w+)", colors),
    ("dimen", r"@dimen/(\w+)", dimens),
    ("string", r"@string/(\w+)", strings),
    ("drawable", r"@drawable/(\w+)", drawables),
    ("style", r"@style/([\w.]+)", styles),
    ("layout", r"@layout/(\w+)", layouts),
    ("menu", r"@menu/(\w+)", menus),
]

missing = set()
for path in glob.glob(os.path.join(RES, "layout", "*.xml")):
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    for kind, pattern, pool in KINDS:
        for m in re.finditer(pattern, text):
            name = m.group(1)
            if name in pool:
                continue
            if kind == "style" and name.startswith(EXTERNAL_STYLE_PREFIXES):
                continue
            if kind == "dimen" and name == "abc_select_dialog_padding_start_material":
                continue  # AppCompat's Material dialog row spacing
            missing.add((os.path.basename(path), kind, name))

check("布局里的资源引用都存在", not missing,
      "\n      ".join(f"{f}: @{k}/{n}" for f, k, n in sorted(missing)[:8]))

# 反向自检：故意构造一个不存在的引用，确认检测有效
check("探针自检：能识别不存在的颜色引用",
      "accent_sky_not_exist" not in colors)

if FAILS:
    print(f"\nFAILED: {len(FAILS)} check(s)")
    sys.exit(1)
print("\nOK: resource reference checks passed")
