#!/usr/bin/env python3
"""设置页主题一致性（v1.6.x 官方架构版）。

新架构（见 docs/md3/README.md）：
  · 只有一份 values/themes.xml，parent 是 Theme.Material3.DayNight.*
  · 深色差异只放 values-night/colors.xml（DayNight 自动切换）
  · Activity 不调用 setTheme()，动态取色由 Application 在
    onActivityPreCreated 注入覆盖层

本探针守住：
  [A] 单一主题存在，且是 DayNight + Material3
  [B] 布局里用到的每个 ?attr/color* 都在主题里被显式映射
      （否则该控件会退回 Material 默认色，与设计令牌脱节）
  [C] values-night 覆盖了所有日间颜色令牌（深色不缺色）
  [D] 没有副本主题 / 没有 Activity 自己 setTheme
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app", "src", "main", "res")
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile")

FAILS = []


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (("  -> " + detail) if not ok else ""))
    if not ok:
        FAILS.append(name)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


themes_path = os.path.join(RES, "values", "themes.xml")
themes = read(themes_path)

# ---------- [A] 单一 DayNight 主题 ----------
m = re.search(r'<style\s+name="Theme\.Dsh"\s+parent="([^"]+)"', themes)
check("[A1] 存在 Theme.Dsh 且声明 parent", m is not None)
if m:
    parent = m.group(1)
    check("[A2] parent 是 Material 3 DayNight", "Theme.Material3.DayNight" in parent, parent)
check("[A3] 不再有 Theme.*.MD3 副本主题",
      re.search(r'<style\s+name="Theme\.[\w.]+\.MD3"', themes) is None)
check("[A4] 不再有 values-night/themes.xml（DayNight 已覆盖）",
      not os.path.exists(os.path.join(RES, "values-night", "themes.xml")))

# ---------- [B] 布局用到的 color 属性都在主题里映射 ----------
mapped = set(re.findall(r'<item name="(color[\w]+)">', themes))
need = set()
for p in glob.glob(os.path.join(RES, "layout", "activity_*.xml")):
    need |= set(re.findall(r'\?attr/(color\w+)', read(p)))
# android: 命名空间的属性（colorBackground 等）由平台提供
missing = sorted(need - mapped)
check("[B1] 布局用到的 MD3 语义色都被主题映射", not missing, "缺: %s" % missing[:8])
check("[B2] 关键色阶齐全",
      {"colorPrimary", "colorOnSurface", "colorSurface",
       "colorSurfaceContainer", "colorSurfaceVariant", "colorOutline"} <= mapped)

# ---------- [C] values-night 颜色令牌齐全 ----------
def color_names(path):
    return set(re.findall(r'<color name="(\w+)"', read(path)))

day = color_names(os.path.join(RES, "values", "colors.xml"))
night = color_names(os.path.join(RES, "values-night", "colors.xml"))
missing_night = sorted(n for n in day if n not in night and n.startswith(("md3_", "accent")))
check("[C1] values-night 覆盖 md3_*/accent* 令牌", not missing_night, str(missing_night[:8]))

# ---------- [D] Activity 不自己 setTheme ----------
bad = []
for p in glob.glob(os.path.join(JAVA, "*.kt")):
    if re.search(r"^\s*setTheme\(", read(p), re.M):
        bad.append(os.path.basename(p))
check("[D1] Activity 不调用 setTheme（否则冲掉动态取色覆盖层）", not bad, str(bad))

# ---------- [E] 动态取色注册在 Application ----------
dshapp = read(os.path.join(JAVA, "DshApp.kt"))
check("[E1] Application 注册了 DynamicColors",
      "DynamicColors.applyToActivitiesIfAvailable" in dshapp)
check("[E2] 动态取色受用户开关控制",
      "ThemeAccent.dynamicEnabled" in dshapp)

if FAILS:
    print(f"\nFAILED: {len(FAILS)} check(s)")
    for f in FAILS:
        print("  -", f)
    sys.exit(1)
print("\nOK: settings theme consistency checks passed")
