#!/usr/bin/env python3
"""界面风格令牌和动态配色的静态契约。

经典和 Material 使用独立的令牌覆盖层，后者引用 Material 语义色，
以便系统动态取色和预设色板都能生效。日夜模式分别检查完整性和分叉；
通知与浮层资源仍须保持不依赖 Activity 主题的静态颜色。
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app", "src", "main", "res")

FAILS = []


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (("  -> " + detail) if not ok else ""))
    if not ok:
        FAILS.append(name)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def styles(path):
    root = ET.parse(path).getroot()
    return {
        style.get("name"): {item.get("name"): (item.text or "").strip()
                            for item in style.findall("item")}
        for style in root.findall("style")
    }


light_path = os.path.join(RES, "values", "themes.xml")
night_path = os.path.join(RES, "values-night", "themes.xml")
attrs_path = os.path.join(RES, "values", "attrs_style.xml")

declared = {attr.get("name") for attr in ET.parse(attrs_path).getroot().findall("attr")
            if attr.get("name", "").startswith("dsh")}
light_styles = styles(light_path)
night_styles = styles(night_path)
classic_name = "ThemeOverlay.Dsh.Style.Classic"
material_name = "ThemeOverlay.Dsh.Style.Material"

# values-night falls back to values for styles it does not override. A night
# override replaces the whole style, so it must independently define all tokens.
check("attrs_style.xml 声明了令牌", len(declared) >= 20, f"declared={len(declared)}")
ALLOW_SAME = {"dshDanger", "dshSuccess", "dshWarning", "dshBtnRipple", "dshOverlay"}
material_roles = {
    "dshBg": "colorSurface", "dshSurface": "colorSurfaceContainer",
    "dshSurfaceAlt": "colorSurfaceContainerLow", "dshSurfaceHigh": "colorSurfaceContainerHigh",
    "dshDialogBg": "colorSurfaceContainerHigh", "dshTextPrimary": "colorOnSurface",
    "dshTextSecondary": "colorOnSurfaceVariant", "dshTextMuted": "colorOnSurfaceVariant",
    "dshAccent": "colorPrimary", "dshAccentSoft": "colorPrimaryContainer",
    "dshOnAccent": "colorOnPrimary", "dshDivider": "colorOutlineVariant",
    "dshStroke": "colorOutlineVariant", "dshDanger": "colorError",
    "dshIconColor": "colorOnSurfaceVariant", "dshIslandText": "colorOnSurfaceVariant",
    "dshIslandAccent": "colorPrimary",
}

for mode, overrides in (("values", {}), ("values-night", night_styles)):
    effective = dict(light_styles)
    effective.update(overrides)
    base = effective.get("Theme.Dsh", {})
    classic = effective.get(classic_name, {})
    material = effective.get(material_name, {})
    for name, items in (("Theme.Dsh", base), (classic_name, classic), (material_name, material)):
        missing = sorted(k for k in declared if not items.get(k))
        check(f"{mode}: {name} 令牌齐全", not missing, ",".join(missing))

    same = sorted(k for k in declared - ALLOW_SAME
                  if classic.get(k) and classic.get(k) == material.get(k))
    check(f"{mode}: 两套风格取值已分叉", not same, "同值: " + ",".join(same))
    for k in ("dshBg", "dshTextPrimary", "dshAccent", "dshSurfaceAlt"):
        a, b = classic.get(k, ""), material.get(k, "")
        check(f"{mode}: {k} 两套色源不同", bool(a and b and a != b), f"{a} vs {b}")
    wrong_roles = sorted(k for k, role in material_roles.items()
                         if material.get(k) != "?attr/" + role)
    check(f"{mode}: Material 令牌跟随动态语义色", not wrong_roles, ",".join(wrong_roles))

# ---- T4 通知/浮层图标必须保持静态 ----
MUST_STAY_STATIC = ["ic_download", "ic_health", "ic_pet"]
for name in MUST_STAY_STATIC:
    p = os.path.join(RES, "drawable", name + ".xml")
    if not os.path.exists(p):
        continue
    txt = read(p)
    check(f"{name} 不用 ?attr（通知栏无 Activity 主题）", "?attr/" not in txt)

# 闪屏与浮层背景同理
for name in ("splash_bg", "bg_dynamic_island_dot", "bg_overlay_panel", "bg_overlay_ball"):
    p = os.path.join(RES, "drawable", name + ".xml")
    if not os.path.exists(p):
        continue
    txt = read(p)
    check(f"{name} 不用 ?attr（Service 上下文取图）", "?attr/" not in txt)

# ---- 风格选择和配色来源相互独立 ----
java = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile")
themed = read(os.path.join(java, "ThemedActivity.kt"))
theme_activity = read(os.path.join(java, "ThemeActivity.kt"))
accent = read(os.path.join(java, "ThemeAccent.kt"))
dshapp = read(os.path.join(java, "DshApp.kt"))
check("主题分派根据偏好快照应用对应覆盖层",
      re.search(r'appliedUiStyle\s*=\s*StylePref\.style\(this\)\s*'
                r'val\s+styleOverlay\s*=\s*if\s*\(followsStyle\s*&&\s*'
                r'appliedUiStyle\s*==\s*StylePref\.Style\.MATERIAL\)\s*\{\s*'
                r'R\.style\.ThemeOverlay_Dsh_Style_Material\s*\}\s*else\s*\{\s*'
                r'R\.style\.ThemeOverlay_Dsh_Style_Classic\s*\}', themed) is not None
      and 'theme.applyStyle(styleOverlay, true)' in themed)
check("followsStyle 默认 true（全站跟随）",
      "protected open val followsStyle: Boolean get() = true" in themed)
check("返回页面时仅风格变化且跟随风格的页面重建",
      re.search(r'fun\s+styleNeedsRecreate\(\):\s*Boolean\s*=\s*'
                r'followsStyle\s*&&\s*appliedUiStyle\s*!=\s*null\s*&&\s*'
                r'appliedUiStyle\s*!=\s*StylePref\.style\(this\)', themed) is not None
      and re.search(r'override\s+fun\s+onResume\(\)\s*\{\s*super\.onResume\(\)\s*'
                    r'if\s*\(styleNeedsRecreate\(\)\)\s*\{\s*recreate\(\)\s*return\s*\}', themed) is not None)
check("风格控件从偏好选中，变更后保存并重建",
      'setupStyle()' in theme_activity
      and 'val current = StylePref.style(this)' in theme_activity
      and 'group.check(if (current == StylePref.Style.MATERIAL) R.id.btnStyleMaterial else R.id.btnStyleClassic)' in theme_activity
      and 'group.addOnButtonCheckedListener' in theme_activity
      and 'if (checkedId == R.id.btnStyleMaterial) StylePref.Style.MATERIAL else StylePref.Style.CLASSIC' in theme_activity
      and re.search(r'if\s*\(wanted\s*!=\s*StylePref\.style\(this\)\)\s*\{\s*'
                    r'StylePref\.setStyle\(this,\s*wanted\)\s*recreate\(\)', theme_activity) is not None)
check("动态色由 Application 按偏好和设备支持应用",
      'DynamicColors.applyToActivitiesIfAvailable(' in dshapp
      and re.search(r'\.setPrecondition\s*\{\s*activity,\s*_\s*->\s*'
                    r'ThemeAccent\.dynamicEnabled\(activity\)\s*\}', dshapp) is not None
      and 'selected(ctx) == SYSTEM && dynamicSupported' in accent)
check("预设色板在风格覆盖层后应用，动态色不被重置",
      re.search(r'theme\.applyStyle\(styleOverlay,\s*true\)\s*'
                r'val\s+paletteOverlay\s*=\s*ThemeAccent\.overlayRes\(this\)\s*'
                r'if\s*\(paletteOverlay\s*!=\s*0\)\s*theme\.applyStyle\(paletteOverlay,\s*true\)', themed) is not None
      and not re.search(r'(?m)^\s*setTheme\s*\(', themed)
      and 'if (dynamicSupported) 0 else R.style.ThemeOverlay_Dsh_Palette_Blue' in accent)
check("预设色板各自映射到对应覆盖层",
      all(re.search(r'\b%s\s*->\s*R\.style\.ThemeOverlay_Dsh_Palette_%s\b' % pair, accent)
          for pair in (("BLUE", "Blue"), ("TEAL", "Teal"), ("VIOLET", "Violet"), ("AMBER", "Amber"))))

if FAILS:
    print(f"\nFAILED: {len(FAILS)} check(s)")
    sys.exit(1)
print(f"\nOK: style token checks passed ({len(declared)} tokens)")
