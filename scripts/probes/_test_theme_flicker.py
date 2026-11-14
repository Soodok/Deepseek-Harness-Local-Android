#!/usr/bin/env python3
"""主题取色不得造成 Activity 重建循环（移植自 PR #102，按本仓现状改写）。

真机事故（issue/群反馈）：点「动态取色」后屏幕疯狂闪烁。根因是闭环 ——
开关 checked 被 onSaveInstanceState 存下 → recreate() → 还原成 true →
再次触发 setOnCheckedChangeListener → 又写偏好并 recreate()。

本仓与 PR #102 的差异：我们保留 PRESET / CUSTOM 两条取色来源（ThemeAccent.Mode
仍是三值），所以契约不能照抄「删掉 setTheme、全交给 Manifest」。这里的判据是：

  1. 开关未变就不重建（空转保护），且开关自身不保存状态（断开还原回路）
  2. 动态色必须在 setTheme 之后应用（否则覆盖层被冲掉）
  3. 系统栏底色在动态模式下解析主题 colorSurface，其余模式仍用静态 @color/bg
  4. 不自造 content-based 伪动态色
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile")
RES = os.path.join(ROOT, "app", "src", "main", "res")

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
layout_theme = read(os.path.join(RES, "layout", "activity_theme.xml"))

# ---- 1. 空转保护 + 状态不保存 ----
check("动态开关未变就不重建",
      "already == checked" in theme, "缺少 mode 与 checked 的比对")
check("开关不保存实例状态（断开还原回路）",
      'android:saveEnabled="false"' in layout_theme)
check("开关接在 swDynamic 上（不是别处）",
      re.search(r'id="@\+id/swDynamic"[\s\S]{0,400}?saveEnabled="false"', layout_theme) is not None)

# ---- 2. 动态色在 setTheme 之后应用 ----
# 只看代码行，注释里提到不算
code_lines = [l.split("//")[0] for l in themed.splitlines()]
i_set = next((i for i, l in enumerate(code_lines) if "setTheme(" in l), -1)
i_dyn = next((i for i, l in enumerate(code_lines) if "DynamicColors.applyTo" in l), -1)
check("setTheme 仍在（本仓保留三取色来源）", i_set >= 0, f"setTheme@{i_set}")
check("动态色在 setTheme 之后应用", i_set >= 0 and i_dyn > i_set,
      f"setTheme@{i_set} DynamicColors@{i_dyn}")

# ---- 3. 系统栏解析主题 colorSurface ----
check("系统栏走 barBackground()", "val bg = barBackground(activity)" in themed)
bars = themed.split("private fun barBackground")[1][:1600] if "private fun barBackground" in themed else ""
check("barBackground 解析 colorSurface",
      "R.attr.colorSurface" in bars)
check("barBackground 有静态兜底",
      "R.color.bg" in bars)
# 只断言「出现了 DYNAMIC」太宽：把守卫改成 always-true 也照样命中（变异 #4 曾假绿）。
# 必须钉住守卫本身，而不是它附近出现过什么常量。
# v1.8.0：静态兜底改成走风格令牌（dshColor），守卫形式随之变化 ——
# 判据钉住的是「非动态模式必须先返回」这条控制流，而不是某个 API 的具体写法。
# 原来写死 getColor(R.color.bg) 会把任何换色的重构都判红，属于判据宽于被测对象。
check("仅动态模式才取主题色（守卫形式）",
      re.search(r"if \(!dynamic\) return activity\.[\w]+\(R\.attr\.dshBg, R\.color\.bg\)", bars)
      is not None)

# ---- 4. 不自造 content-based 伪动态色 ----
check("不用 setContentBasedSource（避免中性色把卡片层次洗掉）",
      "setContentBasedSource" not in themed and "setContentBasedSource" not in theme)

# ---- 5. dynamicSupported 回落 ----
accent = read(os.path.join(JAVA, "ThemeAccent.kt"))
check("不支持 Monet 时回落预设",
      "parsed == Mode.DYNAMIC && !dynamicSupported" in accent)

if FAILS:
    print(f"\nFAILED: {len(FAILS)} check(s)")
    sys.exit(1)
print("\nOK: theme flicker checks passed")
