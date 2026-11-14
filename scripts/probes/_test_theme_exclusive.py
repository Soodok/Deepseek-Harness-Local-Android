#!/usr/bin/env python3
"""主题页必须是原生 MD3 的单选 + 开关结构（v1.6.x 官方架构）。

历史：这一页曾用「开关 + 胶囊 + 三条滑杆」混排，用户看不出当前生效项。
现在按官方 Material 3 规范拆成两组：
  1. 外观模式：MaterialRadioButton 单选（浅色/深色/跟随系统）
  2. 动态取色：MaterialSwitch（Android 12+ 系统 Monet）

本探针守住：
  · 单选状态由 ThemePref 唯一决定，模式未变不重建
  · 动态开关状态由 ThemeAccent.dynamicEnabled 决定，未变不重建
  · 不再有预设色板 / 自定义色板 / 滑杆（那是自造配色，非官方动态取色）
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


theme = read(os.path.join(JAVA, "ThemeActivity.kt"))
layout = read(LAYOUT)

# 外观模式单选
check("三个外观模式都有单选按钮",
      all(f"rbMode{x}" in layout for x in ("Light", "Dark", "System")))
check("整行可点切换模式", "setOnClickListener" in theme and "findViewById<View>(rowId)" in theme)
check("模式未变不重建", "ThemePref.get(this) == mode" in theme)

selector = read(os.path.join(JAVA, "ThemePaletteSelector.kt"))
preferences = read(os.path.join(JAVA, "ThemeAccent.kt"))
check("配色使用共享单选组件", "ThemePaletteSelector(" in theme and "paletteContainer" in layout)
check("动态色与四个预设互斥", all('addOption(' + x in selector for x in ('DYNAMIC', 'BLUE', 'TEAL', 'VIOLET', 'AMBER'))
      and 'button.isChecked = id == currentId' in selector)
check("同一选项不重建", "id != ThemeAccent.selected(this)" in theme)
check("旧设备禁用系统动态色", "id == DYNAMIC && !dynamicAvailable" in selector and "isEnabled = !unavailable" in selector)
check("预设可在所有版本使用", "fun overlayRes(ctx: Context)" in preferences)
check("不将预设冒充系统 Monet", "setContentBasedSource" not in theme)

if FAILS:
    print(f"\nFAILED: {len(FAILS)} check(s)")
    sys.exit(1)
print("\nOK: theme exclusivity checks passed")
