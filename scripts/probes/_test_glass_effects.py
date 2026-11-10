#!/usr/bin/env python3
"""守「灵动岛外观 = 只保留透明度」的实现约束。

## 为什么需要

2026-11-09 用户明确要求：「不要有边缘悬浮效果了，只要保留点透明度就行了」。
在此之前试过三版悬浮感，全被否：深色投影（深底上等于没有，只剩一条脏暗带）、
上缘高光 + 外描边（轮廓上一圈亮线）、玻璃高光。

用户否的是**轮廓上那圈亮线**这个观感，不是某种实现手法 —— 换成 0.5dp、
降低不透明度、改成 gradient，都只是把同一条线变淡，仍在他的否定范围内。
本探针守的就是「别再悄悄加回来」：

1. **岛 drawable 只允许一层实心圆角**。出现 <gradient> / <stroke> / 多层 <item>
   就是边缘效果回来了。这类改动编译通过、运行不报错，只在屏幕上变难看。
2. **那三个色键必须已从两套色板删净**（island_stroke / island_edge_light /
   island_glass_highlight）。留着死色键等于给下一个人留下「加回来」的现成材料。
3. **岛底必须不透明**。层次感全靠 View 的 alpha 分档（1.0 / 0.72 / 0.55），
   底色自己再透就叠成两重透明，边缘会显脏。
4. **alpha 分档要被真正使用**。applyIslandActivity 算出来的透明度如果被别处
   （比如展开动画收尾的 alpha(1f)）覆盖，空闲态就永远不会退后，
   用户看到的是「改了但没效果」。这条靠断言「toggleToolbar 不再硬设 1f」守住。

跑法：python scripts/probes/_test_glass_effects.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DRAW = os.path.join(ROOT, "app/src/main/res/drawable")
NIGHT = os.path.join(ROOT, "app/src/main/res/values-night/colors.xml")
LIGHT = os.path.join(ROOT, "app/src/main/res/values/colors.xml")
MAIN = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/MainActivity.kt")

checks = 0
failed = 0

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def ok(cond, msg):
    global checks, failed
    checks += 1
    if cond:
        print("  ok   " + msg)
    else:
        failed += 1
        print("  FAIL " + msg)


def read(p):
    return io.open(p, encoding="utf-8", errors="replace").read()


def strip_comments(s):
    """去掉整行注释（以 // 开头）。

    ⚠️ 必须剥：本探针要判断「某行代码真的是 false」。想把约束去掉的人第一反应
    就是把它整行注释掉 —— 不剥注释时 `// ...clipToOutline = false` 仍然能匹配，
    断言会假绿。

    只剥整行、不做行内截断：行内截断会把字符串里的 URL 拦腰切断（被测文件里
    确实有含双斜杠的字面量）。而真正的变异就是整行注释，整行判定已足够。

    零反斜杠实现：这个函数被 heredoc 改写过两次，三层转义每次都把正则变成
    另一个语义，第二次甚至把整段代码删光。故用 chr() 拼换行。
    """
    NL = chr(10)
    keep = []
    for ln in s.split(NL):
        if ln.lstrip().startswith("//"):
            continue
        keep.append(ln)
    return NL.join(keep)


def strip_xml_comments(s):
    """去掉 XML 注释 <!-- -->。

    ⚠️ 必须剥：drawable 的文档注释里会写「第一版用过 android:top="43dp"」
    「上缘高光」「外描边」这类反面示例。不剥的话这些词会被当成真实实现 ——
    断言「没有 edge_light / stroke」会因注释里的提及而误红（实测踩到）。
    """
    out = []
    i = 0
    while True:
        a = s.find("<!--", i)
        if a < 0:
            out.append(s[i:])
            break
        out.append(s[i:a])
        b = s.find("-->", a)
        if b < 0:
            break
        i = b + 3
    return "".join(out)


ISLAND = os.path.join(DRAW, "bg_dynamic_island.xml")

print("[A] 岛 drawable 只有一层实心圆角")
ok(os.path.exists(ISLAND), "bg_dynamic_island.xml 存在")
s = strip_xml_comments(read(ISLAND))
ok("<shape" in s, "用单层 <shape>")
ok("<layer-list" not in s, "没有 layer-list —— 多层就是为了叠高光/描边")
items = re.findall(r"<item[ >]", s)
ok(len(items) == 0, f"没有 <item> 层（实得 {len(items)}）")
ok("@color/island_bg" in s, "胶囊本体引用了 island_bg 底色")
ok(re.search(r'android:radius="22dp"', s) is not None, "保持 22dp 圆角")

print("\n[B] ★边缘悬浮效果必须彻底消失（2026-11-09 用户要求）")
# 这三样都是在轮廓上画一圈东西，正是用户否掉的观感本身。
ok("<gradient" not in s, "没有 gradient（上缘高光就是它实现的）")
ok("<stroke" not in s, "没有 stroke（外描边会画出一圈亮线）")
for key in ("island_edge_light", "island_stroke", "island_glass_highlight"):
    ok("@color/" + key not in s, f"drawable 里没有 @color/{key}")
# 反例守卫：这套断言必须真的会红。用一段「上一版」的片段自检，
# 避免哪天 strip 函数坏掉导致所有断言空转。
_sample_old = '<layer-list><item><shape><solid android:color="@color/island_bg" />' \
              '<stroke android:width="1dp" android:color="@color/island_stroke" />' \
              '</shape></item></layer-list>'
ok("<stroke" in _sample_old and "<layer-list" in _sample_old,
   "自检：旧版片段确实含 stroke 与 layer-list（说明上面两条断言有判别力）")

print("\n[C] clipToOutline 保持 false")
sc = strip_comments(read(MAIN))
m = re.search(r"findViewById<View>\(R\.id\.statusBarRow\)\.clipToOutline\s*=\s*(\w+)", sc)
ok(m is not None, "找到 statusBarRow 的 clipToOutline 赋值")
if m:
    ok(m.group(1) == "false",
       f"clipToOutline = false（实得 {m.group(1)}）")

print("\n[D] 岛底必须不透明（层次只由 alpha 表达）")
for p, label in [(NIGHT, "暗色"), (LIGHT, "浅色")]:
    s2 = read(p)
    m2 = re.search(r'<color name="island_bg">#([0-9A-Fa-f]{8})</color>', s2)
    ok(m2 is not None, f"{label} island_bg 存在且是 8 位色值")
    if m2:
        alpha = int(m2.group(1)[:2], 16)
        ok(alpha >= 0xF0,
           f"{label} island_bg alpha=0x{alpha:02X} >= 0xF0（底色不再自己透）")

print("\n[E] 三个边缘效果的色键必须从两套色板里删净")
for p, label in [(NIGHT, "暗色"), (LIGHT, "浅色")]:
    t = read(p)
    for key in ("island_edge_light", "island_stroke", "island_glass_highlight"):
        ok(key not in t, f"{label} 色板里没有 {key}")
# 收起态圆点用的是另一套 ring 键，不能误删
for p, label in [(NIGHT, "暗色"), (LIGHT, "浅色")]:
    t = read(p)
    ok("island_ring_soft" in t, f"{label} 保留 island_ring_soft（收起态圆点要用）")
    ok("island_ring_stroke" in t, f"{label} 保留 island_ring_stroke（收起态圆点要用）")

print("\n[F] 活跃度 alpha 分档仍接在 render 上")
sm = strip_comments(read(MAIN))
ok("fun applyIslandActivity(" in sm, "有 applyIslandActivity")
ok("fun islandIdleAlphaFor(" in sm, "有 islandIdleAlphaFor（纯函数，两处共用）")
ok("applyIslandActivity(state)" in sm, "render() 里真的调了 applyIslandActivity")
ok("0.55f" in sm and "0.72f" in sm and "1.0f" in sm, "三档 0.55/0.72/1.0 都在")

print(f"\nchecks={checks} failed={failed}")
sys.exit(1 if failed else 0)
