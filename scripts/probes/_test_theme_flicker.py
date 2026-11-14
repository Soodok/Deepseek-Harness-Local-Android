#!/usr/bin/env python3
"""主题切换不得造成 Activity 重建循环或错误的动态取色。

单选列表替代旧开关后，选中项由偏好决定，只有选择变化才重建。
官方 Monet 只用于受支持的系统配色，预设配色使用对应主题覆盖层；
不支持 Monet 时保留选择并使用蓝色色板，系统栏必须跟随实际页面背景。
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


theme = read("ThemeActivity.kt")
selector = read("ThemePaletteSelector.kt")
themed = read("ThemedActivity.kt")
dshapp = read("DshApp.kt")
accent = read("ThemeAccent.kt")

# Both user-driven changes are guarded before persisting and recreating.
check("unchanged appearance mode does not recreate",
      re.search(r'if\s*\(ThemePref\.get\(this\)\s*==\s*mode\)\s*return@setOnClickListener\s*'
                r'ThemePref\.set\(this,\s*mode\)\s*recreate\(\)', theme) is not None)
check("unchanged palette does not recreate",
      re.search(r'if\s*\(id\s*!=\s*ThemeAccent\.selected\(this\)\)\s*\{\s*'
                r'ThemeAccent\.setSelected\(this,\s*id\)\s*recreate\(\)', theme) is not None)
check("appearance radio state comes from preference",
      all('ThemePref.get(this) == ThemeMode.' + mode in theme for mode in ('LIGHT', 'DARK', 'SYSTEM')))
check("palette state comes from preference",
      'ThemePaletteSelector(' in theme and 'ThemeAccent.selected(this)' in theme
      and 'paletteSelector.setSelectedId(ThemeAccent.selected(this))' in theme)
check("palette selection only invokes callback on a changed click",
      re.search(r'setOnClickListener\s*\{\s*if\s*\(id\s*!=\s*currentId\)\s*\{\s*'
                r'currentId\s*=\s*id\s*updateChecked\(\)\s*onSelected\(id\)', selector) is not None)
check("restoring selection does not invoke callback",
      re.search(r'fun setSelectedId\(selectedId: String\)\s*\{\s*'
                r'currentId\s*=\s*normalizedId\(selectedId\)\s*updateChecked\(\)\s*\}', selector) is not None)

check("unchanged style does not recreate",
      re.search(r'if\s*\(wanted\s*!=\s*StylePref\.style\(this\)\)\s*\{\s*'
                r'StylePref\.setStyle\(this,\s*wanted\)\s*recreate\(\)', theme) is not None)

# DynamicColors is installed once at application level and must not be reset by the activity.
code_lines = [line.split("//")[0] for line in themed.splitlines()]
check("activity does not call setTheme", not any('setTheme(' in line for line in code_lines))
check("application registers official dynamic colors with preference guard",
      'DynamicColors.applyToActivitiesIfAvailable(' in dshapp
      and re.search(r'\.setPrecondition\s*\{\s*activity,\s*_\s*->\s*'
                    r'ThemeAccent\.dynamicEnabled\(activity\)\s*\}', dshapp) is not None)
check("dynamic colors require system selection and device support",
      'selected(ctx) == SYSTEM && dynamicSupported' in accent)
check("unavailable dynamic selection uses blue overlay without changing preference",
      'if (dynamicSupported) 0 else R.style.ThemeOverlay_Dsh_Palette_Blue' in accent
      and 'if (stored in paletteIds) return stored!!' in accent)
check("preset palettes have distinct overlays",
      all(re.search(r'\b%s\s*->\s*R\.style\.ThemeOverlay_Dsh_Palette_%s\b' % pair, accent)
          for pair in [('BLUE', 'Blue'), ('TEAL', 'Teal'), ('VIOLET', 'Violet'), ('AMBER', 'Amber')]))
check("no content-based pseudo dynamic colors",
      all('setContentBasedSource' not in source for source in (theme, themed, dshapp, accent)))

# Every palette defines the page background as its surface; the bars must follow it.
bars = themed.split('fun applyBars', 1)[1].split('fun IslandPalette.themed', 1)[0] if 'fun applyBars' in themed else ''
check("system bars use resolved background",
      'val bg = barBackground(activity)' in bars
      and 'activity.window.statusBarColor = bg' in bars and 'activity.window.navigationBarColor = bg' in bars)
check("all system bars resolve colorSurface with static fallback",
      re.search(r'private fun barBackground\(activity: Activity\): Int\s*\{\s*'
                r'val tv = android\.util\.TypedValue\(\)\s*'
                r'val ok = activity\.theme\.resolveAttribute\(\s*'
                r'com\.google\.android\.material\.R\.attr\.colorSurface,\s*tv,\s*true,?\s*\)', bars) is not None
      and 'if (!ok) return activity.getColor(R.color.bg)' in bars
      and 'runCatching { activity.getColor(tv.resourceId) }.getOrDefault(activity.getColor(R.color.bg))' in bars
      and 'tv.data' in bars)
for loc in ('values', 'values-night'):
    resources = ET.parse(os.path.join(ROOT, 'app', 'src', 'main', 'res', loc, 'md3_palettes.xml')).getroot()
    styles = {style.get('name'): {item.get('name'): item.text for item in style.findall('item')}
              for style in resources.findall('style')}
    check(loc + ': preset page backgrounds match their surface',
          all((items := styles.get('ThemeOverlay.Dsh.Palette.' + name, {})).get('colorSurface')
              and items.get('colorSurface') == items.get('android:colorBackground')
              and items.get('colorSurface') == items.get('android:windowBackground')
              for name in ('Blue', 'Teal', 'Violet', 'Amber')))

if FAILS:
    print(f"\nFAILED: {len(FAILS)} check(s)")
    sys.exit(1)
print("\nOK: theme flicker checks passed")
