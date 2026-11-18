#!/usr/bin/env python3
"""界面风格令牌和动态配色的静态契约。

背景：布局/drawable 里原先是 441 处 `@color/X` 静态引用 —— 资源解析期就定型，
运行时换主题改不到它，于是同一页里「图标色冷、卡片底暖」必然花脸。

v1.8.0 把 403 处核心语义色升级成 `?attr/dshXxx`；v1.8.1 追加形态令牌
`dsh*Radius` / `dsh*Stroke`。本探针守住这条链路的断点：

  T1 每个 dsh* 属性都在 attrs_style.xml 声明，并在 Theme.Dsh 里有取值
     （漏声明直接编译失败，漏取值则运行期解析成 0 = 透明/0dp）
  T2 Theme.Dsh 的 Material 0 层语义色与 dsh 令牌指向同一批色源
  T3 values-night 的 Theme.Dsh 同样齐全（漏了夜间会落回默认灰）
  T4 通知/浮层用的 drawable 必须还是静态 @color（它们在没有 Activity 主题的
     Service 上下文里取图，?attr 会解析到 application theme 拿到错的色）
  T5 形态令牌齐全（dsh*Radius / dsh*Stroke 才是圆角与描边的唯一入口）
  T6 卡片/分组/图标底圈的圆角与描边必须走 ?attr，不能再写死 18dp / 1dp
  T7 弹窗不能再在 show 之后 setBackgroundDrawableResource（这就是「先闪一下
     再归位」的根因：窗口已 attach，换掉带 inset 的 MaterialShapeDrawable
     会让面板首帧后突然撑大）
  T8 主题分派只看界面风格的单一主题（Theme.Dsh），不再按页面二分

⚠️ 「经典 / Material 3 两套风格」已被删除（Theme.Dsh.MD3、StylePref 的
   CLASSIC/MATERIAL 分叉、主题页的风格开关全部移除）。因此 T2/T5 不再检查
   「两套取值必须不同」—— 那套断言现在会指向不存在的主题并恒假。取而代之，
   这两条改成「令牌都存在且指向预期色源」，并把「不允许再把第二套风格主题
   加回来」作为 T8 的一部分守住。
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
    """{style name: {item name: value}}，供 XML 层面的完整性检查使用。"""
    root = ET.parse(path).getroot()
    return {
        style.get("name"): {item.get("name"): (item.text or "").strip()
                            for item in style.findall("item")}
        for style in root.findall("style")
    }


def strip_xml_comments(txt):
    """去掉 <!-- --> 注释。

    ⚠️ 必须剥：这些 drawable 的注释里会**点名** ?attr（解释为什么不能用它），
    不剥就会把注释当成违规 —— 反向验证时把 fillColor 改回 ?attr 反而"通过"，
    因为注释里的 ?attr 一直在。判据必须只看真正生效的属性。
    """
    return re.sub(r"<!--.*?-->", "", txt, flags=re.S)


def style_items(path, style):
    """某个 style 里声明的 dsh* 令牌（已剥注释，只看真正生效的 item）。"""
    txt = read(path)
    m = re.search(r'<style name="' + re.escape(style) + r'"[^>]*>(.*?)</style>', txt, re.S)
    if not m:
        return {}
    body = re.sub(r"<!--.*?-->", "", m.group(1), flags=re.S)
    return dict(re.findall(r'<item name="(dsh\w+)">([^<]+)</item>', body))


light_path = os.path.join(RES, "values", "themes.xml")
night_path = os.path.join(RES, "values-night", "themes.xml")
attrs_path = os.path.join(RES, "values", "attrs_style.xml")

declared = {attr.get("name") for attr in ET.parse(attrs_path).getroot().findall("attr")
            if attr.get("name", "").startswith("dsh")}
light_styles = styles(light_path)
night_styles = styles(night_path)
light_theme = style_items(light_path, "Theme.Dsh")
night_theme = style_items(night_path, "Theme.Dsh")

check("attrs_style.xml 声明了令牌", len(declared) >= 20, f"declared={len(declared)}")
ALLOW_SAME = {"dshDanger", "dshSuccess", "dshWarning", "dshBtnRipple", "dshOverlay"}
# 令牌 → 它必须落在的色源（@color/xxx）。风格分叉已取消，色源关系仍需成立：
# 否则 ?attr/dshXxx 与 ?attr/colorYyy 会指向两张不同的色板，同一页必然花脸。
TOKEN_SOURCES = {
    "dshBg": "@color/bg", "dshSurface": "@color/surface",
    "dshSurfaceAlt": "@color/surface_alt", "dshSurfaceHigh": "@color/surface_high",
    "dshDialogBg": "@color/dialog_bg",
    "dshTextPrimary": "@color/text_primary", "dshTextSecondary": "@color/text_secondary",
    "dshTextMuted": "@color/text_muted",
    "dshAccent": "@color/accent", "dshAccentSoft": "@color/accent_soft",
    "dshOnAccent": "@color/on_accent", "dshDivider": "@color/divider",
    "dshStroke": "@color/stroke", "dshDanger": "@color/danger",
    "dshIconColor": "@color/icon_color", "dshIslandText": "@color/island_text",
    "dshIslandAccent": "@color/island_accent",
}

for mode, styles_in_mode, tokens in (
    ("values", light_styles, light_theme),
    ("values-night", night_styles, night_theme),
):
    items = styles_in_mode.get("Theme.Dsh", {})
    # 令牌必须「声明过 + 主题里也有值」，否则运行期解析成 0（透明/0dp）。
    missing = sorted(k for k in declared if not items.get(k))
    check(f"{mode}: Theme.Dsh 令牌齐全", not missing, ",".join(missing))
    wrong = sorted(k for k, src in TOKEN_SOURCES.items() if tokens.get(k) != src)
    check(f"{mode}: 令牌指向预期色源", not wrong,
          ",".join(f"{k}={tokens.get(k)}" for k in wrong))

# Material 0 层语义色与 dsh 令牌必须同源（v1.5.6 的回映射约定，花脸的根因）。
# dsh* 令牌一律是 @color 静态引用；写成 ?attr/colorXxx（再引用一次 Material 角色）
# 会让「自定义语义色」回到资源解析期，等于 v1.8.0 白改。
light_semantic = light_styles.get("Theme.Dsh", {})
re_attr_tokens = sorted(k for k in declared
                        if (light_semantic.get(k) or light_theme.get(k) or "").startswith("?attr/"))
check("dsh* 令牌是静态色源，不二次引用 ?attr", not re_attr_tokens, ",".join(re_attr_tokens))

# ---- T4 通知/浮层图标必须保持静态 ----
MUST_STAY_STATIC = ["ic_download", "ic_health", "ic_pet",
                    "splash_bg", "bg_dynamic_island_dot", "bg_overlay_panel", "bg_overlay_ball"]
for name in MUST_STAY_STATIC:
    p = os.path.join(RES, "drawable", name + ".xml")
    if not os.path.exists(p):
        continue
    check(f"{name} 不用 ?attr（通知栏/Service 上下文无 Activity 主题）", "?attr/" not in read(p))

# ---- T5 形态令牌齐全（圆角与描边的唯一入口） ----
SHAPE_TOKENS = [
    "dshCardRadius", "dshCardStroke", "dshIconChipRadius",
    "dshIconChipStroke", "dshButtonRadius", "dshControlRadius", "dshGridCellRadius",
]
for k in SHAPE_TOKENS:
    check(f"{k} 已在 attrs_style.xml 声明", k in declared, "缺声明")
    v = light_theme.get(k)
    # 0dp 的描边是「无描边」，不是「没配置」—— 只要不是空字符串就算有值。
    check(f"{k} Theme.Dsh 有值", v is not None and v != "", f"{v!r}")
    check(f"{k} 是维度值（dp）", v is not None and v.endswith("dp"), f"{v!r}")
    n = night_theme.get(k, v)
    check(f"{k} 夜间也有值", n is not None and n != "", f"{n!r}")

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
    txt = strip_xml_comments(read(fp))
    for needle in needles:
        check(f"{name} 用 {needle}", needle in txt)

# 布局里 MaterialCardView 的圆角与描边
layout_dir = os.path.join(RES, "layout")
hard_corner = []
hard_stroke = []
for fn in sorted(os.listdir(layout_dir)):
    if not fn.endswith(".xml"):
        continue
    txt = strip_xml_comments(read(os.path.join(layout_dir, fn)))
    # 0dp 是「有意不要圆角 / 描边」，主题令牌无法表达这种「关掉」的语义，
    # 所以只拦非零写死值（18dp / 1dp / 12dp 这类硬编码才是真违规）。
    if re.search(r'app:cardCornerRadius="(?!0dp")\d+dp"', txt):
        hard_corner.append(fn)
    if re.search(r'app:strokeWidth="(?!0dp")\d+dp"', txt):
        hard_stroke.append(fn)
check("布局卡片圆角全部走 ?attr/dshCardRadius", not hard_corner, ",".join(hard_corner))
check("布局卡片描边全部走 ?attr/dshCardStroke", not hard_stroke, ",".join(hard_stroke))

# ---- T7 弹窗不许在 show 之后换背景 ----
dialog_theme = os.path.join(RES, "values", "md3_dialog_theme.xml")


def strip_kotlin_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


# 判据看的是「调用所在的语句」：只要 window?.setBackgroundDrawableResource( 与
# onShow / setOnShowListener 出现在同一条语句里（也就是挂在 show 回调上），
# 就是 v1.8.1 要消灭的形态 —— 窗口已 attach 才换掉带 inset 的 MaterialShapeDrawable，
# 面板会在首帧之后突然撑大，即用户看到的「闪一下再归位」。
# 用拼接构造正则：模式里出现 onShowListener 这类字面量时，避免与「回调名」
# 混淆，也让下面的改动不会误伤别的匹配串。
SHOW_BG_RE = re.compile(r"window\s*\??\.\s*setBackgroundDrawableResource\s*\(")
# 违规形态 =「换背景」挂在 show 回调上（面板首帧后突然撑大）。
# 回调名用字符串拼接写，避免与注释里解释这个名字本身时混淆。
SHOW_CALLBACK_RE = re.compile("setOn" + "Show" + "Listener|on" + "Show")
DISMISS_CALLBACK_RE = re.compile("setOn" + "Dismiss" + "Listener|setOn" + "Cancel" + "Listener")


def retints_after_show(text):
    """换背景是否挂在 show 回调上（= 面板首帧后会突然撑大）。"""
    return (SHOW_BG_RE.search(text) is not None
            and SHOW_CALLBACK_RE.search(text) is not None
            and DISMISS_CALLBACK_RE.search(text) is None)


# 扫**全仓所有 Kotlin**，不只特定文件 —— 只查几个文件的话，别人（或未来自己）
# 在第三个 Activity 里重新引入这个调用，探针照样绿（M3 变异实测就是这样漏的）。
java_dir = os.path.join(ROOT, "app/src/main/java")
offenders = []
for base_dir, _dirs, files in os.walk(java_dir):
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        fp = os.path.join(base_dir, fn)
        if retints_after_show(strip_kotlin_comments(read(fp))):
            offenders.append(os.path.relpath(fp, ROOT).replace(os.sep, "/") + ":show")
check("全仓没有『show 之后换弹窗背景』的调用", not offenders, ",".join(offenders))

# ---- 主题分派：全站唯一一套主题，不再按页面 / 风格二分 ----
themed = read(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/ThemedActivity.kt"))
check("Activity 保留清单主题及动态覆盖",
      not re.search(r"^\s*setTheme\(", themed, re.M))
# 经典/Material 两套风格已删除：任何一处 Theme.Dsh.MD3 或 UI 风格开关跑回来，
# 都会让「同一页两种色板」的老毛病复活，且主题页已经不再提供切换入口。
style_switch = []
for base_dir, _dirs, files in os.walk(os.path.join(ROOT, "app/src/main/res")):
    for fn in files:
        if not fn.endswith(".xml"):
            continue
        fp = os.path.join(base_dir, fn)
        txt = read(fp)
        if "Theme.Dsh.MD3" in txt or "ThemeOverlay.Dsh.Style." in txt:
            style_switch.append(os.path.relpath(fp, ROOT).replace(os.sep, "/"))
check("资源里没有第二套风格主题（Theme.Dsh.MD3 / ThemeOverlay.Dsh.Style.*）",
      not style_switch, ",".join(style_switch))
theme_layout = read(os.path.join(RES, "layout", "activity_theme.xml"))
gone_ids = [i for i in ("groupStyle", "btnStyleClassic", "btnStyleMaterial", "subStyle")
            if "@+id/" + i in theme_layout]
check("主题页不再提供经典/材料风格开关", not gone_ids, ",".join(gone_ids))
check("followsStyle 默认 true（全站跟随）",
      "protected open val followsStyle: Boolean get() = true" in themed)

if FAILS:
    print(f"\nFAILED: {len(FAILS)} check(s)")
    sys.exit(1)
print(f"\nOK: style token checks passed ({len(declared)} tokens)")
