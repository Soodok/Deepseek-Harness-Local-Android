#!/usr/bin/env python3
<<<<<<< HEAD
"""主题切换不得造成 Activity 重建循环或错误的动态取色。

单选列表替代旧开关后，选中项由偏好决定，只有选择变化才重建。
官方 Monet 只用于受支持的系统配色，预设配色使用对应主题覆盖层；
不支持 Monet 时保留选择并使用蓝色色板，系统栏必须跟随实际页面背景。
=======
"""主题取色不得造成页面重建 / 闪烁。

## 两代事故，两代判据

**v1.7.14（PR #102 移植）**：点「动态取色」后屏幕疯狂闪烁。根因是闭环 ——
开关 checked 被 onSaveInstanceState 存下 → recreate() → 还原成 true →
再次触发监听 → 又写偏好并 recreate()。当时的契约是「空转保护 + saveEnabled=false
+ DynamicColors 在 setTheme 之后应用」。

**v1.8.1（本轮）**：闭环早修好了，但真机/模拟器实测（Android 14 / API 34）
点一下「动态取色」仍有 **2 次** `Schedule relaunch activity` —— 用户报的
「点击一下是直接闪屏的」。原因是两处独立触发：

  · DYNAMIC 分支自己调 recreate()；
  · ThemedActivity 在 DYNAMIC 下注入 `DynamicColors.applyToActivityIfAvailable`，
    它在 API 31+ 走 ColorResourcesOverride → ResourcesLoader 替换颜色资源，
    **改动本 Activity 的 Resources 配置** → 系统再派发一次 configuration change
    → 第二次 relaunch。

修法（订单式三条）：
  1. 强调色只影响运行时取色，不影响主题 → **就地换色，不 recreate**；
  2. 不再注入 DynamicColors（它改 Resources）→ 改由 [SystemAccent] 直接读
     `android.R.color.system_accent1_*`；
  3. 系统栏无条件读 `?attr/dshBg`（不再依赖 Monet 覆盖过的 colorSurface）。

本探针守住上面这几条，另外保留 v1.7.14 的两条防回归（空转保护 / 断开还原回路）。
>>>>>>> upstream/main
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


<<<<<<< HEAD
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
=======
def strip_comments(src):
    """剥掉块注释与行注释 —— 注释里提到某个 API 不算调用。

    这条不是洁癖：v1.8.1 的 ThemedActivity 里有一段「原来这里调了
    DynamicColors.applyToActivityIfAvailable」的说明文字，不做剥离的话
    「不再注入 DynamicColors」这条断言永远判红。
    """
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


theme = strip_comments(read(os.path.join(JAVA, "ThemeActivity.kt")))
themed = strip_comments(read(os.path.join(JAVA, "ThemedActivity.kt")))
system_accent = read(os.path.join(JAVA, "SystemAccent.kt"))
layout_theme = read(os.path.join(RES, "layout", "activity_theme.xml"))

# ---- 1. v1.7.14 防回归：空转保护 + 断开还原回路 ----
check("动态开关未变就不重建",
      "already == checked" in theme, "缺少 mode 与 checked 的比对")
check("开关不保存实例状态（断开还原回路）",
      'android:saveEnabled="false"' in layout_theme)
check("开关接在 swDynamic 上（不是别处）",
      re.search(r'id="@\+id/swDynamic"[\s\S]{0,400}?saveEnabled="false"', layout_theme) is not None)

# ---- 2. v1.8.1：不再注入 DynamicColors ----
# 判据看的是**真实调用**（剥注释后仍出现的 DynamicColors.applyTo*）
check("ThemedActivity 不再注入 DynamicColors（它改 Resources 会触发第二次 relaunch）",
      "DynamicColors.applyTo" not in themed)
check("ThemedActivity 仍保留 setTheme 分派（风格切换靠它）",
      "setTheme(if (followsStyle" in themed)

# ---- 3. 强调色就地换色，不 recreate ----
# DYNAMIC 分支必须调 reapaintAccentLive()，并且**不能**跟在它后面 recreate()
dyn_block = ""
m = re.search(r"val already = ThemeAccent\.mode\(this\).*?\n        \}", theme, re.S)
if m:
    dyn_block = m.group(0)
check("动态取色分支存在", bool(dyn_block))
check("动态取色走就地换色 reapaintAccentLive()",
      "reapaintAccentLive()" in dyn_block)
check("动态取色分支不再 recreate()",
      "recreate()" not in dyn_block,
      "recreate 会让整页重建 —— 强调色只影响运行时取色，不必重建")
# 取 reapaintAccentLive 的函数体：从它自己起，到下一个 "private fun" 前为止。
# （注释已被剥掉，所以不能用 "// ----" 当边界——那条边界在剥注释后就消失了，
#   会把整份文件的 recreate() 都算进来，判据宽到必然假红。）
live_body = ""
if "private fun reapaintAccentLive" in theme:
    live_body = theme.split("private fun reapaintAccentLive")[1].split("private fun ")[0]
check("reapaintAccentLive 不调 recreate（就地换色，不重建页面）",
      bool(live_body) and "recreate(" not in live_body,
      f"body={live_body[:120]!r}")
check("reapaintAccentLive 会重铺强调色与摘要",
      "AccentTint.apply" in live_body and "refreshSummary()" in live_body)

# ---- 4. 动态取色从系统资源直接读，不碰主题 ----
check("存在 SystemAccent.kt", bool(system_accent))
check("SystemAccent 读 system_accent1",
      "system_accent1" in system_accent)
check("Accent.resolve 的动态分支走 SystemAccent",
      "SystemAccent.primary(" in read(os.path.join(JAVA, "Accent.kt")))
check("Accent.resolve 的动态分支不再读 MaterialColors.getColor(colorPrimary)",
      "MaterialColors.getColor" not in strip_comments(read(os.path.join(JAVA, "Accent.kt"))))

# ---- 5. 系统栏无条件读风格令牌 ----
check("系统栏走 barBackground()", "val bg = barBackground(activity)" in themed)
bars = themed.split("private fun barBackground")[1][:900] if "private fun barBackground" in themed else ""
check("barBackground 读 ?attr/dshBg", "R.attr.dshBg" in bars)
check("barBackground 不再解析主题 colorSurface（Monet 已不注入，读了也是卡片色）",
      "colorSurface" not in bars)
# 判据钉住「函数体就是一行表达式」这条形式，而不是某个变量名：
# 只要有人重新引入 dynamic 分支（if/三元/提前返回），这条就会红。
check("barBackground 是无条件表达式（没有 dynamic 分支）",
      re.search(r"private fun barBackground\(activity: Activity\): Int =\s*\n\s*activity\.\w+\(R\.attr\.dshBg, R\.color\.bg\)",
                themed) is not None)

# ---- 6. 不自造 content-based 伪动态色 ----
check("不用 setContentBasedSource（避免中性色把卡片层次洗掉）",
      "setContentBasedSource" not in themed and "setContentBasedSource" not in theme)

# ---- 7. dynamicSupported 回落 ----
accent_kt = read(os.path.join(JAVA, "ThemeAccent.kt"))
check("不支持 Monet 时回落预设",
      "parsed == Mode.DYNAMIC && !dynamicSupported" in accent_kt)
>>>>>>> upstream/main

if FAILS:
    print(f"\nFAILED: {len(FAILS)} check(s)")
    sys.exit(1)
print("\nOK: theme flicker checks passed")
