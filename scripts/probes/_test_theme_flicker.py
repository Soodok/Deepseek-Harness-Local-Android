#!/usr/bin/env python3
"""动态取色不得造成 Activity 重建循环。

真机事故：点「动态取色」后屏幕疯狂闪烁。
根因是开关状态、模式写入与 recreate() 互相触发，形成重建闭环。

本探针守住四条：
  1. 动态开关的回调里必须比较模式，模式没变就不重建
  2. 开关自身不保存/恢复状态（saveEnabled=false），避免恢复时再次触发回调
  3. 动态色在 setTheme 之后应用（否则会被 setTheme 抹掉）
  4. 系统栏优先取主题 colorSurface，不写死 @color/bg
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile")
LAYOUT = os.path.join(ROOT, "app", "src", "main", "res", "layout", "activity_theme.xml")

FAILS = []


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (("  -> " + detail) if not ok else ""))
    if not ok:
        FAILS.append(name)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


theme_activity = read(os.path.join(JAVA, "ThemeActivity.kt"))
themed = read(os.path.join(JAVA, "ThemedActivity.kt"))
layout = read(LAYOUT)

# 1. 动态开关有模式比较
sw = re.search(r"swDynamic\.setOnCheckedChangeListener\s*\{([\s\S]{0,1200}?)\n        \}", theme_activity)
body = sw.group(1) if sw else ""
check("动态开关回调有「模式未变不重建」保护",
      "ThemeAccent.mode(this) == target" in body,
      "未找到比较逻辑")

# 2. 开关不参与状态恢复
check("动态开关关闭状态恢复（saveEnabled=false）",
      'android:id="@+id/swDynamic"' in layout and 'saveEnabled="false"' in layout)

# 3. 动态色在 setTheme 之后
i_theme = themed.find("setTheme(if (usesSettingsBackground)")
i_apply = themed.find("applyDynamicColor()")
check("动态色在 setTheme 之后应用", i_theme >= 0 and i_apply > i_theme,
      f"setTheme@{i_theme} applyDynamicColor@{i_apply}")

# 4. 系统栏优先主题属性
bars = themed.split("fun applyBars")[1][:1400] if "fun applyBars" in themed else ""
check("系统栏优先跟随主题 colorSurface",
      "R.attr.colorSurface" in bars and
      bars.find("R.attr.colorSurface") < bars.find("R.color.bg"))
check("系统栏设置状态栏与导航栏颜色",
      "statusBarColor = bg" in bars and "navigationBarColor = bg" in bars)

if FAILS:
    print(f"\nFAILED: {len(FAILS)} check(s)")
    sys.exit(1)
print("\nOK: theme flicker checks passed")
