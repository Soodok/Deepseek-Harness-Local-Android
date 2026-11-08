#!/usr/bin/env python3
"""守「玻璃质感」的三条实现约束。

## 为什么需要

这三条都是**改坏了界面会静默变差、但不报任何错**的类型：

1. **layer-list 叠层顺序**。`<item>` 的顺序 = 绘制顺序，后画的在上层。
   把高光写在最前面，它会被后面不透明的底色整个盖住 —— 界面上看起来
   就是「没有高光」，编译通过、运行不崩、也没有警告。实测踩过：
   岛内竖直采样全是同一个值 (38,38,38)，改成「底色→高光→描边」后
   才出现 (64,64,64) → (39,39,39) 的梯度。

2. **半透明度不能压太低**。灵动岛里是状态文字。降到 72% 时 11sp 文字在
   浅色网页上只剩 4.1:1，低于 WCAG AA 的 4.5:1 —— 视觉上"更透了"，
   代价是读不出来。

3. **alpha 分档要被真正使用**。applyIslandActivity 算出来的透明度如果
   被别处（比如展开动画收尾的 alpha(1f)）覆盖，空闲态就永远不会退后，
   用户看到的是"改了但没效果"。这条靠断言「toggleToolbar 不再硬设 1f」守住。

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
    """去掉整行注释（以 // 开头）与块注释。

    ⚠️ 必须剥：本探针要判断「某调用真的存在」。想把调用去掉的人第一反应
    就是把它整行注释掉 —— 不剥注释时 applyIslandActivity(state) 那行的
    注释版本仍然能匹配，断言会假绿（实测踩到：注释掉调用后探针依然全绿）。

    为什么只剥整行、不做行内截断：行内截断会把字符串里的 URL 拦腰切断。
    被测的 MainActivity.kt 里确实有含双斜杠的 URL 字面量。而真正的变异
    就是整行注释，整行判定已足够，且不会误删任何代码。

    零反斜杠实现：此函数被 heredoc 改写过两次，三层转义每次都把正则变成
    另一个语义，第二次甚至把整段代码删光。所以这里用字符类 + 逐行判定 +
    chr() 拼换行，完全绕开转义。"""
    NL = chr(10)
    STAR = "[" + chr(42) + "]"
    s = re.sub("/" + STAR + ".*?" + STAR + "/", "", s, flags=re.S)
    keep = []
    for ln in s.split(NL):
        if ln.lstrip().startswith("//"):
            continue
        keep.append(ln)
    return NL.join(keep)


# 探针只管**灵动岛与浮层**这三处玻璃。扩展中心的玻璃卡（bg_glass_card.xml）
# 已按用户要求回退删除（「感觉不如之前那种」），不再是这里的验收对象。
GLASS_FILES = [
    "bg_dynamic_island.xml",
    "bg_overlay_panel.xml",
]

print("[A] 玻璃 drawable 必须是 layer-list，且三层齐全")
for name in GLASS_FILES:
    p = os.path.join(DRAW, name)
    ok(os.path.exists(p), f"{name} 存在")
    if not os.path.exists(p):
        continue
    s = read(p)
    ok("<layer-list" in s, f"{name} 用 layer-list（否则做不出上下不同亮）")
    n_items = len(re.findall(r"<item[ >]", s))
    ok(n_items >= 3, f"{name} 至少 3 层（实得 {n_items}）")
    ok("@color/surface_glass" in s or "@color/island_bg" in s,
       f"{name} 引用了半透明底色")
    ok("@color/stroke_glass" in s or "@color/island_stroke" in s,
       f"{name} 引用了内描边色")

print("\n[B] ★叠层顺序：底色必须在高光之前（先画=在下）")
# 这是本探针最重要的一条。判断方法：在文件里定位「高光 gradient」与
# 「半透明 solid」各自第一次出现的位置，solid 必须在前。
for name in GLASS_FILES:
    p = os.path.join(DRAW, name)
    if not os.path.exists(p):
        continue
    s = read(p)
    body = s[s.index("<layer-list"):]
    m_hi = re.search(r"<gradient", body)
    m_bg = re.search(r"<solid[^>]*@color/(surface_glass|island_bg)", body)
    if not m_hi or not m_bg:
        ok(False, f"{name} 找不到高光或底色（无法判序）")
        continue
    ok(m_bg.start() < m_hi.start(),
       f"{name} 底色在高光之前（否则高光被盖住，界面上等于没有）")

print("\n[C] 半透明度不能压到影响可读性")
# island_bg / surface_glass 的 alpha 必须 >= 0xD0（约 81%）。
# 依据：72% 时 11sp 文字对浅色网页只有 4.1:1，低于 4.5:1。
def alpha_of(color_hex):
    """#AARRGGBB -> alpha int"""
    h = color_hex.lstrip("#")
    return int(h[:2], 16) if len(h) == 8 else 255


for p, label in [(NIGHT, "暗色"), (LIGHT, "浅色")]:
    s = read(p)
    m = re.search(r'<color name="island_bg">#([0-9A-Fa-f]{8})</color>', s)
    ok(m is not None, f"{label} island_bg 存在且是 8 位色值")
    if m:
        a = alpha_of(m.group(1))
        ok(a >= 0xD0,
           f"{label} island_bg alpha=0x{a:02X} >= 0xD0（再低文字读不清）")
    m = re.search(r'<color name="island_glass_highlight">#([0-9A-Fa-f]{8})</color>', s)
    ok(m is not None, f"{label} island_glass_highlight 已定义")
    if m:
        a = alpha_of(m.group(1))
        # 浅色主题上高光不该比暗色更亮：白底叠白高光看不出层次
        limit = 0x20 if label == "暗色" else 0x1C
        ok(a <= limit, f"{label} 高光 alpha=0x{a:02X} <= 0x{limit:02X}（过亮会糊成一片）")

print("\n[D] 活跃度 alpha 分档真的接上了")
s = strip_comments(read(MAIN))
ok("fun applyIslandActivity(" in s, "有 applyIslandActivity")
ok("fun islandIdleAlphaFor(" in s, "有 islandIdleAlphaFor（纯函数，两处共用）")
ok("applyIslandActivity(state)" in s, "render() 里真的调了 applyIslandActivity")
# 三档必须存在
ok("0.55f" in s, "空闲档 0.55f 存在")
ok("0.72f" in s, "正常档 0.72f 存在")
ok("1.0f" in s, "活跃档 1.0f 存在")

print("\n[E] ★展开动画不得硬设 alpha(1f)（会覆盖活跃度透明度）")
# toggleToolbar 的展开收尾曾经是 `.alpha(1f)`，每次手动收起再展开，
# 空闲的岛都会回到满不透明 —— 改了半天等于没改。
m = re.search(r"row\.animate\(\)\.scaleX\(1f\)\.scaleY\(1f\)\.alpha\(([^)]+)\)", s)
ok(m is not None, "找到展开动画的收尾 alpha 表达式")
if m:
    ok(m.group(1).strip() != "1f",
       f"展开收尾用状态目标值而不是硬编码 1f（实得 alpha({m.group(1).strip()})）")
ok("lastRenderedState" in s, "记录了 lastRenderedState 供动画收尾查询")

print("\n[F] 亮/暗两套色值都定义了（缺一个会在该主题下崩）")
for key in ("surface_glass", "stroke_glass", "island_glass_highlight"):
    ok(f'name="{key}"' in read(NIGHT), f"暗色定义了 {key}")
    ok(f'name="{key}"' in read(LIGHT), f"浅色定义了 {key}")

print(f"\nchecks={checks} failed={failed}")
sys.exit(1 if failed else 0)
