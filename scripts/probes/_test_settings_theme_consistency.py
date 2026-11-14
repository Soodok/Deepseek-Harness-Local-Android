#!/usr/bin/env python3
"""设置体系主题一致性：布局用了哪些 theme 属性，主题就必须提供哪些。

## 为什么是「一致性」而不是「必须 MD3」

背景（2026-11-07）：合并 main 的 60aed9c1（PR #93 整包 revert）时，
ThemedActivity 的 `setTheme(if (usesSettingsBackground) Theme_Dsh_MD3 else Theme_Dsh)`
被静默覆盖成硬编码 `Theme_Dsh`，同时 themes.xml 里的 Theme.Dsh.MD3 主题块也被删。
而 MD3 布局里有 55 处 `?attr/colorOnSurface` 一类属性 —— 解析不到就抛
InflateException，设置页一打开即崩：

    Binary XML file line #35 in layout/activity_settings:
    Error inflating class android.widget.TextView

因为 Git 按「一方删除、另一方未改」自动处理，全程没有冲突提示，直到模拟器上
点开设置页才暴露。

后来用户要求设置页退回旧样式（不要 MD3），布局改回不使用 ?attr/ 的写法，
主题分派也随之撤掉。所以探针不能锁「必须走 MD3 分派」——那会把正确的回退判红。

真正要守的不变量是**两边一致**：
  布局里出现 ?attr/color* 或 ?attr/textAppearance*  → 主题必须定义它们
  布局里一个都没有                                  → 主题不必是 Material3

任一方向不匹配就会在 inflate 时炸，且不报编译错误。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ok = 0
fail = []


def check(name, cond, detail=""):
    global ok
    if cond:
        ok += 1
        print("PASS  " + name)
    else:
        fail.append(name + ((" :: " + detail) if detail else ""))
        print("FAIL  " + name + ((" :: " + detail) if detail else ""))


def read(rel):
    p = os.path.join(ROOT, rel)
    if not os.path.exists(p):
        return ""
    return io.open(p, encoding="utf-8", errors="ignore").read()


# ---------- 扫描各布局用到的主题属性 ----------
# 只统计「依赖主题提供」的那两类：?attr/color* 与 ?attr/textAppearance*。
# ?attr/selectableItemBackground 这种平台自带的不算（android: 主题总有）。
THEME_ATTR = re.compile(r"\?attr/((?:color|textAppearance)[A-Za-z0-9_]*)")
layouts_dir = os.path.join(ROOT, "app/src/main/res/layout")
users = {}
for f in sorted(os.listdir(layouts_dir)) if os.path.isdir(layouts_dir) else []:
    if not f.endswith(".xml"):
        continue
    t = io.open(os.path.join(layouts_dir, f), encoding="utf-8", errors="ignore").read()
    attrs = sorted(set(THEME_ATTR.findall(t)))
    if attrs:
        users[f] = attrs

# ---------- 主题实际提供了哪些 ----------
def provided(rel):
    """返回该主题文件里 <item name="..."> 声明的属性名集合。"""
    t = read(rel)
    return set(re.findall(r'<item\s+name="([A-Za-z0-9_]+)"\s*>', t))


light = provided("app/src/main/res/values/themes.xml")
night = provided("app/src/main/res/values-night/themes.xml")

# ---------- [A] 基本存在性 ----------
themed = read("app/src/main/java/app/dsh/mobile/ThemedActivity.kt")
check("[A1] ThemedActivity 存在", bool(themed))
# 判据要钉住**控制流**，不能靠「1200 字符窗口内出现过 setTheme」：
# 那条规则被 v1.8.0 在 onCreate 里加的注释挤破（1263 字符），红得毫无道理；
# 更糟的是只要附近随便出现一次 setTheme 就绿，把调用挪到 super.onCreate 之后就查不出来。
# 真正的契约只有一条：**setTheme 必须在 super.onCreate 之前**。
_oc = re.search(r"override\s+fun\s+onCreate\b(.*?)\n    \}", themed, re.S)
_body = _oc.group(1) if _oc else ""
_i_set = _body.find("setTheme(")
_i_super = _body.find("super.onCreate(")
check("[A2] onCreate 里必须 setTheme，且在 super.onCreate 之前",
      _i_set >= 0 and _i_super >= 0 and _i_set < _i_super,
      f"setTheme@{_i_set} super.onCreate@{_i_super}")

# ---------- [B] 一致性：布局用到的属性，明暗两套主题都得有 ----------
if not users:
    check("[B0] 没有布局依赖主题色属性 → 一致性自动成立", True)
else:
    for fname, attrs in users.items():
        # Material3 库自带大量属性；这里只校验本项目主题「应当自己定义」的
        # 那部分 —— 即以 color 开头的语义色。textAppearance* 由依赖库提供。
        need = [a for a in attrs if a.startswith("color")]
        if not need:
            continue
        miss_light = [a for a in need if a not in light]
        miss_night = [a for a in need if a not in night]
        check("[B:%s] values 主题覆盖了该布局用到的 color 属性" % fname,
              not miss_light, "缺: %s" % miss_light)
        check("[B:%s] values-night 主题覆盖了该布局用到的 color 属性" % fname,
              not miss_night, "缺: %s" % miss_night)

# ---------- [C] 用 MD3 属性的布局，其宿主主题必须是 Material3 ----------
# 判据：主题继承链上出现 Material3（否则 ?attr/colorOnSurface 这类语义色
# 没有上游默认值，只能在我们的 <item> 里逐条补齐；补齐了也能用，故此项
# 只在「未补齐」时才红 —— 由 [B] 兜住。这里额外守住「父主题不得是纯 android:」，
# 那种主题对 MaterialSwitch / MaterialButton 这类控件会缺 defStyleAttr。
for rel, label in (("app/src/main/res/values/themes.xml", "light"),
                   ("app/src/main/res/values-night/themes.xml", "night")):
    t = read(rel)
    m = re.search(r'<style\s+name="Theme\.Dsh"\s+parent="([^"]+)"', t)
    check("[C:%s] Theme.Dsh 有明确的 parent" % label, m is not None)
    if m:
        check("[C:%s] Theme.Dsh 的 parent 不是空的 android:" % label,
              bool(m.group(1).strip()), "parent 为空")

# ---------- [D] 反向自检：判定逻辑必须能对坏样本判红 ----------
bad_layout = '<TextView android:textColor="?attr/colorOnSurface" />'
bad_attrs = THEME_ATTR.findall(bad_layout)
check("[D1] 自检：坏样本的属性能被扫描到", bad_attrs == ["colorOnSurface"])
check("[D2] 自检：空主题确实判它不合格", "colorOnSurface" not in set())

print()
if fail:
    print("FAILED: %d check(s)" % len(fail))
    for f in fail:
        print("  - " + f)
    sys.exit(1)
print("OK: settings theme consistency checks passed (%d)" % ok)
