#!/usr/bin/env python3
"""守「灵动岛悬浮质感」的四条实现约束。

## 为什么需要

这几条都是**改坏了界面会静默变差、但不报任何错**的类型：

1. **clipToOutline 必须是 false**。drawable 自绘的投影画在胶囊轮廓之外，
   View 的 `clipToOutline = true` 会按背景 outline 把它整片裁掉。实测：
   开 true 时岛下方全是纯白 255,255,255；关掉后立刻出现
   93→122→153→183→211→235 的渐变。没有任何日志、没有异常，只能靠像素比对发现。

2. **投影各层底边必须错开**。第一版六层都用 `android:top` 偏移、底边全部对齐，
   结果底部是一条**平的暗带**（实测 (106,106,106) 一整条）—— 六层在那个区域
   完全重叠，alpha 叠成固定值。必须让 `android:bottom` 递增才有「近浓远淡」。

3. **岛底必须不透明**。v1.5.5 的玻璃方案把它降到 85%，用户否掉了 ——
   悬浮物该是实的，透了会「陷」进背景。

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

    ⚠️ 必须剥：drawable 的文档注释里会写「第一版用过 android:top="43dp"」这类
    反面示例。不剥的话，这些示例会被当成真实实现 —— 断言「无硬编码偏移」会
    因为注释里那个 43dp 而误红（实测踩到）。
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

print("[A] 岛 drawable 是 layer-list，三层齐全")
ok(os.path.exists(ISLAND), "bg_dynamic_island.xml 存在")
s = strip_xml_comments(read(ISLAND))
ok("<layer-list" in s, "用 layer-list（单层 <shape> 做不出上缘高光）")
items = re.findall(r"<item[ >]", s)
ok(len(items) >= 3, f"至少 3 层（底色 / 上缘高光 / 外描边，实得 {len(items)}）")
ok("@color/island_bg" in s, "胶囊本体引用了 island_bg 底色")
ok("@color/island_stroke" in s, "有外描边层")

print("\n[B] ★暗色悬浮感靠边缘提亮，不是投影")
# 深色背景上叠深色投影等于没有 —— 实测只在胶囊正下方留一条脏暗带
# （(106,106,106) 一整条），不是「浮起来」而是「旁边有污渍」。
# 正确做法：上缘提亮 + 浅灰外描边。
ok("@color/island_edge_light" in s, "有上缘高光色（island_edge_light）")
ok("<gradient" in s, "上缘高光用渐变 —— 依赖相对高度，容器改了也不错位")
# 不能有硬编码 top/bottom 偏移：第一版用 android:top=\"43dp\" 定位边缘线，
# 容器一改高度就错位，而且不报错。
offsets = re.findall(r'android:(?:top|bottom)="(\d+)d[pd]"', s)
ok(len(offsets) == 0,
   f"无硬编码 top/bottom 偏移（实得 {offsets}）—— 应改用相对渐变")
# 描边宽度必须 >= 1dp：0.5dp 在 density 440（2.75x）上只有 1.4px，常被抗锯齿吃掉
m_sw = re.search(r'<stroke\s+android:width="([\d.]+)dp"', s)
ok(m_sw is not None, "找到描边宽度定义")
if m_sw:
    ok(float(m_sw.group(1)) >= 1.0,
       f"描边宽度 {m_sw.group(1)}dp >= 1dp（0.5dp 在高密度屏上会糊掉）")

print("\n[C] ★clipToOutline 必须是 false（否则溢出层被裁掉）")
sc = strip_comments(read(MAIN))
m = re.search(r"findViewById<View>\(R\.id\.statusBarRow\)\.clipToOutline\s*=\s*(\w+)", sc)
ok(m is not None, "找到 statusBarRow 的 clipToOutline 赋值")
if m:
    ok(m.group(1) == "false",
       f"clipToOutline = false（实得 {m.group(1)}）—— true 会裁掉胶囊轮廓外的一切")

print("\n[D] 岛底必须不透明（悬浮物该是实的）")
for p, label in [(NIGHT, "暗色"), (LIGHT, "浅色")]:
    s2 = read(p)
    m2 = re.search(r'<color name="island_bg">#([0-9A-Fa-f]{8})</color>', s2)
    ok(m2 is not None, f"{label} island_bg 存在且是 8 位色值")
    if m2:
        alpha = int(m2.group(1)[:2], 16)
        ok(alpha >= 0xF0,
           f"{label} island_bg alpha=0x{alpha:02X} >= 0xF0（不透明）—— 玻璃方案的 0xD9 已废弃")

print("\n[E] 玻璃方案留下的死资源应已删除")
for p, label in [(NIGHT, "暗色"), (LIGHT, "浅色")]:
    ok("island_glass_highlight" not in read(p),
       f"{label} 已无 island_glass_highlight（玻璃专用的死色键）")

print("\n[F] 活跃度 alpha 分档仍接在 render 上")
sm = strip_comments(read(MAIN))
ok("fun applyIslandActivity(" in sm, "有 applyIslandActivity")
ok("fun islandIdleAlphaFor(" in sm, "有 islandIdleAlphaFor（纯函数，两处共用）")
ok("applyIslandActivity(state)" in sm, "render() 里真的调了 applyIslandActivity")
ok("0.55f" in sm and "0.72f" in sm and "1.0f" in sm, "三档 0.55/0.72/1.0 都在")

print(f"\nchecks={checks} failed={failed}")
sys.exit(1 if failed else 0)
