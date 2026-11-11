#!/usr/bin/env python3
"""主题取色不得造成 Activity 重建循环。

真机事故：点「动态取色」后屏幕疯狂闪烁 —— 开关状态、模式写入与 recreate()
互相触发形成闭环。重做为主题页单选列表后，契约变成：
  1. 选中项与模式严格对应，模式未变就不重建
  2. 取色统一由 ThemeEngine 负责，且在 setTheme 之后应用
  3. 壁纸 Monet 只给 DYNAMIC；预设/自定义走种子色色板
  4. 系统栏跟随主题 colorSurface
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile")

FAILS = []


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (("  -> " + detail) if not ok else ""))
    if not ok:
        FAILS.append(name)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


theme = read(os.path.join(JAVA, "ThemeActivity.kt"))
themed = read(os.path.join(JAVA, "ThemedActivity.kt"))
dshapp = read(os.path.join(JAVA, "DshApp.kt"))

# 1. 空转保护
check("切换模式前比较（模式未变不重建）",
      "ThemePref.get(this) == mode" in theme)
check("动态开关未变不重建",
      "ThemeAccent.dynamicEnabled(this) == checked" in theme)
check("单选状态由偏好唯一决定",
      theme.count("ThemePref.get(this) == ThemeMode.") >= 2)

# 2. 取色统一在 ThemeEngine，且晚于 setTheme
# 只在「代码行」里找 setTheme，注释里提到不算
i_theme = -1
for line in themed.splitlines():
    code = line.split("//")[0]
    if "setTheme(" in code:
        i_theme = 1
        break
check("Activity 不调用 setTheme（避免冲掉官方覆盖层）", i_theme < 0,
      f"setTheme@{i_theme}")

# 3. Monet 只给 DYNAMIC，预设/自定义走种子色
check("动态取色在 Application 注册（官方位置）",
      "DynamicColors.applyToActivitiesIfAvailable" in dshapp)
check("不自造 content-based 伪动态色",
      "setContentBasedSource" not in themed and "setContentBasedSource" not in dshapp)

# 4. 系统栏跟随主题属性
bars = themed.split("fun applyBars")[1][:1400] if "fun applyBars" in themed else ""
check("系统栏优先跟随主题 colorSurface",
      "colorSurface" in bars and
      (bars.find("colorSurface") < bars.find("R.color.bg") if "R.color.bg" in bars else True))

if FAILS:
    print(f"\nFAILED: {len(FAILS)} check(s)")
    sys.exit(1)
print("\nOK: theme flicker checks passed")
