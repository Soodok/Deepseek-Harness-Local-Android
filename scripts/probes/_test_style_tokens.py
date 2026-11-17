#!/usr/bin/env python3
"""界面风格令牌和动态配色的静态契约。

<<<<<<< HEAD
经典和 Material 使用独立的令牌覆盖层，后者引用 Material 语义色，
以便系统动态取色和预设色板都能生效。日夜模式分别检查完整性和分叉；
通知与浮层资源仍须保持不依赖 Activity 主题的静态颜色。
=======
背景：布局/drawable 里原先是 441 处 `@color/X` 静态引用 —— 资源解析期就定型，
运行时换主题改不到它。所以「切风格」在旧实现里等于没切：Theme.Dsh 与
Theme.Dsh.MD3 的语义色全映射到同一张旧色板，画出来一模一样。

v1.8.0 把 403 处核心语义色升级成 `?attr/dshXxx`，两套主题各给一套值。
本探针守住这条链路的四个断点：

  T1 每个 dsh* 属性都在 Theme.Dsh 与 Theme.Dsh.MD3 里有定义（漏一个就掉色）
  T2 两个主题的同名属性取值不同（相同就等于没分叉，正是旧实现的问题）
  T3 values-night 也两套齐全（漏了夜间直接崩或落回默认灰）
  T4 通知/浮层用的 drawable 必须还是静态 @color（它们在没有 Activity 主题的
     Service 上下文里取图，?attr 会解析到 application theme 拿到错的色）

v1.8.1 追加形态层（用户报「MD3 不就改了个颜色吗」）：

  T5 形态令牌 dsh*Radius / dsh*Stroke 两套齐全且取值不同（只分叉颜色不算换风格）
  T6 卡片/分组/图标底圈的圆角与描边必须走 ?attr，不能再写死 18dp / 1dp
  T7 弹窗不能再在 show 之后 setBackgroundDrawableResource（这就是「先闪一下
     再归位」的根因：窗口已 attach，换掉带 inset 的 MaterialShapeDrawable
     会让面板首帧后突然撑大）
>>>>>>> upstream/main
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


<<<<<<< HEAD
def styles(path):
    root = ET.parse(path).getroot()
    return {
        style.get("name"): {item.get("name"): (item.text or "").strip()
                            for item in style.findall("item")}
        for style in root.findall("style")
    }
=======
def strip_xml_comments(txt):
    """去掉 <!-- --> 注释。

    ⚠️ 必须剥：这些 drawable 的注释里会**点名** ?attr（解释为什么不能用它），
    不剥就会把注释当成违规 —— 反向验证时把 fillColor 改回 ?attr 反而"通过"，
    因为注释里的 ?attr 一直在。判据必须只看真正生效的属性。
    """
    return re.sub(r"<!--.*?-->", "", txt, flags=re.S)


def style_items(path, style):
    txt = read(path)
    m = re.search(r'<style name="' + re.escape(style) + r'"[^>]*>(.*?)</style>', txt, re.S)
    if not m:
        return {}
    body = re.sub(r"<!--.*?-->", "", m.group(1), flags=re.S)
    return dict(re.findall(r'<item name="(dsh\w+)">([^<]+)</item>', body))
>>>>>>> upstream/main


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

# ---- T5 形态令牌两套齐全且真分叉 ----
SHAPE_TOKENS = [
    "dshCardRadius", "dshCardStroke", "dshIconChipRadius",
    "dshIconChipStroke", "dshButtonRadius", "dshControlRadius", "dshGridCellRadius",
]
for k in SHAPE_TOKENS:
    check(f"{k} 已在 attrs_style.xml 声明", k in declared, "缺声明")
    a = light_classic.get(k)
    b = light_md3.get(k)
    check(f"{k} 经典/材料3 都有值", a is not None and b is not None, f"{a} / {b}")
    check(f"{k} 两套取值不同（形态真分叉）", a != b, f"{a} vs {b}")

# ---- T6 圆角/描边必须走令牌，不许写死 ----
SHAPE_DRAWABLES = {
    "bg_card.xml": ["?attr/dshCardRadius", "?attr/dshCardStroke"],
    "bg_md3_card.xml": ["?attr/dshCardRadius", "?attr/dshCardStroke"],
    "bg_md3_group.xml": ["?attr/dshCardRadius", "?attr/dshCardStroke"],
    "bg_md3_seg_top.xml": ["?attr/dshCardRadius"],
    "bg_md3_seg_single.xml": ["?attr/dshCardRadius"],
    "bg_md3_seg_bottom.xml": ["?attr/dshCardRadius"],
    "bg_md3_grid_cell.xml": ["?attr/dshGridCellRadius"],
    "bg_md3_icon_chip.xml": ["?attr/dshIconChipRadius"],
    "bg_md3_input.xml": ["?attr/dshControlRadius"],
}
for name, needles in SHAPE_DRAWABLES.items():
    fp = os.path.join(RES, "drawable", name)
    if not os.path.exists(fp):
        check(f"{name} 存在", False, "文件缺失")
        continue
    txt = read(fp)
    for needle in needles:
        check(f"{name} 用 {needle}", needle in txt)

# 布局里 22 处 MaterialCardView 的圆角与描边
layout_dir = os.path.join(RES, "layout")
hard_corner = []
hard_stroke = []
for fn in sorted(os.listdir(layout_dir)):
    if not fn.endswith(".xml"):
        continue
    txt = read(os.path.join(layout_dir, fn))
    if re.search(r'app:cardCornerRadius="\d+dp"', txt):
        hard_corner.append(fn)
    if re.search(r'app:strokeWidth="\d+dp"', txt):
        hard_stroke.append(fn)
check("布局卡片圆角全部走 ?attr/dshCardRadius", not hard_corner, ",".join(hard_corner))
check("布局卡片描边全部走 ?attr/dshCardStroke", not hard_stroke, ",".join(hard_stroke))

# ---- T7 弹窗不许在 show 之后换背景 ----
themed_kt = read(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/ThemedActivity.kt"))
appear_kt = read(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/AppearanceActivity.kt"))
dialog_theme = os.path.join(RES, "values", "md3_dialog_theme.xml")
# 只看**真实调用**：先剥掉 Kotlin 注释（KDoc / 行注释），否则注释里写的
# "window?.setBackgroundDrawableResource(...)" 会被误判成调用。
def strip_kotlin_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


CALL_RE = re.compile(r"window\s*\??\.\s*setBackgroundDrawableResource\s*\(")
# 扫**全仓所有 Kotlin**，不只这两个文件 —— 只查两个文件的话，别人（或未来自己）
# 在第三个 Activity 里重新引入这个调用，探针照样绿（M3 变异实测就是这样漏的）。
java_dir = os.path.join(ROOT, "app/src/main/java")
offenders = []
for base_dir, _dirs, files in os.walk(java_dir):
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        fp = os.path.join(base_dir, fn)
        if CALL_RE.search(strip_kotlin_comments(read(fp))):
            offenders.append(os.path.relpath(fp, ROOT).replace(os.sep, "/"))
check("全仓没有『show 之后换弹窗背景』的调用", not offenders, ",".join(offenders))
check("存在弹窗主题 md3_dialog_theme.xml", os.path.exists(dialog_theme))
if os.path.exists(dialog_theme):
    dt = read(dialog_theme)
    check("弹窗主题基于 MaterialAlertDialog.Material3",
          'parent="MaterialAlertDialog.Material3"' in dt)
    check("弹窗圆角走 ?attr/dshCardRadius", "?attr/dshCardRadius" in dt)
for themes_path, label in ((light_path, "values"), (night_path, "values-night")):
    txt = read(themes_path)
    check(f"{label} 两套主题都设了 materialAlertDialogTheme",
          txt.count('materialAlertDialogTheme">@style/ThemeOverlay.Dsh.MaterialAlertDialog') == 2,
          f"count={txt.count('materialAlertDialogTheme')}")

# ---- T4 通知/浮层图标必须保持静态 ----
MUST_STAY_STATIC = ["ic_download", "ic_health", "ic_pet"]
for name in MUST_STAY_STATIC:
    p = os.path.join(RES, "drawable", name + ".xml")
    if not os.path.exists(p):
        continue
    txt = strip_xml_comments(read(p))
    check(f"{name} 不用 ?attr（通知栏无 Activity 主题）", "?attr/" not in txt)

# 无障碍悬浮窗里**所有**图标同理（v1.8.2 真机事故：ic_mic 漏网）
#
# 为什么单独查：StatusOverlay.show() 用 `LayoutInflater.from(svc.applicationContext)`
# 取图 —— 服务上下文没有 Activity 主题，?attr 会落到 application theme（Light 变体）
# 解析。而同窗口的面板底色 bg_overlay_panel 用的是**静态** @color/surface_glass
# （跟随系统夜间）。两个解析源不一致，夜间就是「浅色面板 + 白色图标」，
# 图标整个看不见（实测该区域内非底色像素 0 个）。
#
# 判据取「overlay_status.xml 引用的全部 drawable」，而不是维护一份手写白名单 ——
# 手写清单漏一个就是这个 bug，而这正是它当初被漏掉的方式。
_m_layout = read(os.path.join(RES, "layout", "overlay_status.xml"))
_overlay_drawables = sorted(set(re.findall(r"@drawable/([A-Za-z0-9_]+)", _m_layout)))
check("overlay_status.xml 解析到 drawable 引用", len(_overlay_drawables) >= 3,
      "解析到：%s" % ", ".join(_overlay_drawables))
for name in _overlay_drawables:
    p = os.path.join(RES, "drawable", name + ".xml")
    if not os.path.exists(p):
        continue
    txt = strip_xml_comments(read(p))
    check(f"{name} 不用 ?attr（无障碍悬浮窗跑在服务上下文，没有 Activity 主题）",
          "?attr/" not in txt,
          "该 drawable 被 overlay_status.xml 引用；服务上下文里 ?attr 会解析到 "
          "application theme，与静态面板底色错配，夜间图标会消失")

# 闪屏与浮层背景同理
for name in ("splash_bg", "bg_dynamic_island_dot", "bg_overlay_panel", "bg_overlay_ball"):
    p = os.path.join(RES, "drawable", name + ".xml")
    if not os.path.exists(p):
        continue
    txt = strip_xml_comments(read(p))
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
