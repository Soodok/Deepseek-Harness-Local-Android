#!/usr/bin/env python3
"""界面风格令牌（v1.8.0）静态契约。

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
"""
import os
import re
import sys

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


def style_items(path, style):
    txt = read(path)
    m = re.search(r'<style name="' + re.escape(style) + r'"[^>]*>(.*?)</style>', txt, re.S)
    if not m:
        return {}
    body = re.sub(r"<!--.*?-->", "", m.group(1), flags=re.S)
    return dict(re.findall(r'<item name="(dsh\w+)">([^<]+)</item>', body))


light_path = os.path.join(RES, "values", "themes.xml")
night_path = os.path.join(RES, "values-night", "themes.xml")
attrs_path = os.path.join(RES, "values", "attrs_style.xml")

declared = set(re.findall(r'<attr name="(dsh\w+)"', read(attrs_path)))
light_classic = style_items(light_path, "Theme.Dsh")
light_md3 = style_items(light_path, "Theme.Dsh.MD3")
night_classic = style_items(night_path, "Theme.Dsh")
night_md3 = style_items(night_path, "Theme.Dsh.MD3")

# ---- T1 两个主题都定义齐全 ----
check("attrs_style.xml 声明了令牌", len(declared) >= 20, f"declared={len(declared)}")
missing_l = sorted(declared - set(light_classic))
missing_m = sorted(declared - set(light_md3))
check("Theme.Dsh 定义了全部令牌", not missing_l, ",".join(missing_l))
check("Theme.Dsh.MD3 定义了全部令牌", not missing_m, ",".join(missing_m))

# ---- T2 两套取值必须真的不同 ----
same = sorted(k for k in declared if k in light_classic and k in light_md3
              and light_classic[k] == light_md3[k])
# 少量令牌本来就该同值（功能色 danger/success/warning、ripple、overlay），显式白名单
ALLOW_SAME = {"dshDanger", "dshSuccess", "dshWarning", "dshBtnRipple", "dshOverlay"}
same = [k for k in same if k not in ALLOW_SAME]
check("两套风格取值已分叉（不是同一张色板）", not same, "同值: " + ",".join(same))

# 关键令牌逐个确认指向不同色源
for k in ("dshBg", "dshTextPrimary", "dshAccent", "dshSurfaceAlt"):
    a = light_classic.get(k, "")
    b = light_md3.get(k, "")
    check(f"{k} 两套色源不同", a != b, f"{a} vs {b}")

# ---- T3 夜间两套齐全 ----
check("夜間 Theme.Dsh 令牌齐全", not (declared - set(night_classic)),
      ",".join(sorted(declared - set(night_classic))))
check("夜間 Theme.Dsh.MD3 令牌齐全", not (declared - set(night_md3)),
      ",".join(sorted(declared - set(night_md3))))

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

# ---- 主题分派跟随风格，而不是跟随 usesSettingsBackground ----
themed = read(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/ThemedActivity.kt"))
check("主题分派用 followsStyle + StylePref", 
      "followsStyle && StylePref.isMaterial(this)" in themed)
check("followsStyle 默认 true（全站跟随）",
      "protected open val followsStyle: Boolean get() = true" in themed)

if FAILS:
    print(f"\nFAILED: {len(FAILS)} check(s)")
    sys.exit(1)
print(f"\nOK: style token checks passed ({len(declared)} tokens)")
