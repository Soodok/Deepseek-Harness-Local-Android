#!/usr/bin/env python3
"""主题取色不得造成页面重建 / 闪烁。

## 历史与当前契约

**v1.7.14**：点「动态取色」后疯狂闪烁 —— 开关还原 → 回调 → recreate 的闭环。
**v1.8.1**：动态分支自己 recreate + Activity 注入 DynamicColors 改 Resources，
一次点击两次 relaunch。

**2026-11 收敛（当前）**：全站唯一一套 Material 3 主题（Theme.Dsh），
原「经典 / Material 3」风格切换与 ThemeOverlay.Dsh.Style.* 已删除。
主题页只剩：外观模式（浅/深/跟随系统）+ 配色方案（系统动态 + 四套色板）。

本探针守住：
  1. 外观模式与配色选择都先比对再写盘重建（空转保护，无重建闭环）；
  2. 配色覆盖层在主题初始化后应用；
  3. DynamicColors 只在 Application 注册一次，Activity 不注入；
  4. 系统栏跟着主题的页面底色走。
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile")
FAILS = []


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (("  -> " + detail) if not ok else ""))
    if not ok:
        FAILS.append(name)


def read(name):
    with open(os.path.join(JAVA, name), encoding="utf-8") as f:
        return f.read()


def strip_comments(src):
    """剥掉块注释与行注释 —— 注释里提到某个 API 不算调用。"""
    import re as _re
    src = _re.sub(r'/\*[\s\S]*?\*/', '', src)
    return _re.sub(r'//[^\n]*', '', src)


theme = read("ThemeActivity.kt")
selector = read("ThemePaletteSelector.kt")
themed = read("ThemedActivity.kt")
dshapp = read("DshApp.kt")
accent = read("ThemeAccent.kt")

# ---- 1. 空转保护：偏好没变就不写盘、不重建 ----
check("外观模式未变就不重建",
      re.search(r'if\s*\(ThemePref\.get\(this\)\s*==\s*mode\)\s*return@setOnClickListener\s*'
                r'ThemePref\.set\(this,\s*mode\)\s*recreate\(\)', theme) is not None)
check("配色未变就不重建",
      re.search(r'if\s*\(id\s*!=\s*ThemeAccent\.selected\(this\)\)\s*\{\s*'
                r'ThemeAccent\.setSelected\(this,\s*id\)\s*recreate\(\)', theme) is not None)
check("配色选择器只在选中项变化时回调",
      re.search(r'setOnClickListener\s*\{\s*if\s*\(id\s*!=\s*currentId\)\s*\{\s*'
                r'currentId\s*=\s*id\s*updateChecked\(\)\s*onSelected\(id\)', selector) is not None)

# ---- 2. 单选状态来自偏好 ----
check("外观模式单选状态来自偏好",
      all('ThemePref.get(this) == ThemeMode.' + mode in theme for mode in ('LIGHT', 'DARK', 'SYSTEM')))
check("配色状态来自偏好",
      'ThemePaletteSelector(' in theme and 'ThemeAccent.selected(this)' in theme
      and 'paletteSelector.setSelectedId(ThemeAccent.selected(this))' in theme)

# ---- 3. 主题分派与配色覆盖层 ----
themed_code = strip_comments(themed)
check("全站固定单一 Material 3 主题",
      not re.search(r"^\s*setTheme\(", themed_code, re.M))
check("配色覆盖层在主题初始化后应用",
      re.search(r'ThemeManager\.syncAppCompatNight\(this\)\n'
                r'\s*val paletteOverlay = ThemeAccent\.overlayRes\(this\)\n'
                r'\s*if \(paletteOverlay != 0\) theme\.applyStyle\(paletteOverlay, true\)',
                themed_code) is not None)

# ---- 4. DynamicColors 只在 Application 注册，Activity 不注入 ----
dshapp_code = strip_comments(dshapp)
check("DynamicColors 在 Application 注册且受开关约束",
      "DynamicColors.applyToActivitiesIfAvailable" in dshapp_code
      and "ThemeAccent.dynamicEnabled" in dshapp_code)
check("Activity 不注入 DynamicColors",
      "DynamicColors.applyTo" not in themed_code)
check("没有 content-based 伪动态色",
      "setContentBasedSource" not in themed_code and "setContentBasedSource" not in strip_comments(theme))

# ---- 5. 系统栏跟随页面底色 ----
check("系统栏走 barBackground()", "barBackground(activity)" in themed_code)

# ---- 6. 不支持动态色时回落蓝色色板 ----
check("不支持动态色时回落蓝色色板",
      "if (dynamicSupported) 0 else R.style.ThemeOverlay_Dsh_Palette_Blue" in accent)


if FAILS:
    print(f"\nFAILED: {len(FAILS)} check(s)")
    sys.exit(1)
print("\nOK: theme flicker checks passed")
