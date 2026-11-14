#!/usr/bin/env python3
"""侧边灵动岛面板边缘投影（v1.7.14）契约探针。

用户诉求：「侧边灵动岛展开的面板可以加一层边缘阴影效果，不描边」。
「不描边」是硬约束 —— 这条改动不许把 outline 又打开，也不许用会描出硬边的做法。

锁住的四类事实：

  1. 配色：IslandPalette.Colors 必须有 panelShadow 字段，默认 0（0 = 不投阴影）；
     深色 0x7A000000（48% 黑，面板本身全黑，浅了看不出浮起）、
     浅色 0x38000000（22% 黑，再深就像描了一圈粗边，与「不描边」相反）。
  2. 画法：必须走 setShadowLayer + 软件层。硬件画布会静默忽略 shadowLayer 的模糊，
     只剩一个硬边矩形 —— 那正好就是用户不要的「描边」形态，而且不会报错。
  3. 几何：贴屏幕那一侧不投（inset 取 0，画了也出屏，部分机型还会露半像素暗线），
     只给外凸侧圆角、贴边侧直角（与 IslandSidePanel 的 cornerRadii 同序）。
  4. 接线：阴影视图必须垫在 host 索引 0（scrim 与面板之下）；
     预留边 >= 模糊半径 + 纵向偏移，否则外沿被切出平口；
     位置用 margin 精确对齐 m.y - extra，不能用 CENTER_VERTICAL 蒙
     —— 面板高度被 70% 上限截断时 y 是 clamp 出来的，不等于垂直居中。

⚠️ 断言前必须剥注释：这段代码的注释里写满了 setShadowLayer / CENTER_VERTICAL /
   panelShadow 这些字面量，不剥会把注释当实现（本仓已因此假绿过一次）。

运行：python scripts/probes/_test_island_panel_shadow.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FAILED = []


def read(rel):
    with io.open(os.path.join(ROOT, rel), encoding='utf-8') as f:
        return f.read()


def check(name, ok, detail=''):
    if ok:
        print('PASS  ' + name)
    else:
        print('FAIL  ' + name + (('  [' + detail + ']') if detail else ''))
        FAILED.append(name)


def strip_comments(src):
    """去掉 // 行注释与 /* */ 块注释，只留可执行文本。"""
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return '\n'.join(line.split('//', 1)[0] for line in src.split('\n'))


chrome_raw = read('app/src/main/java/app/dsh/mobile/IslandChrome.kt')
main_raw = read('app/src/main/java/app/dsh/mobile/MainActivity.kt')
shadow_raw = read('app/src/main/java/app/dsh/mobile/IslandPanelShadowView.kt')

chrome = strip_comments(chrome_raw)
main = strip_comments(main_raw)
shadow = strip_comments(shadow_raw)

# ---------- 1. 配色 ----------
check('palette declares panelShadow field with default 0',
      re.search(r'val panelShadow: Int = 0,', chrome) is not None)
check('DARK panelShadow = 0x7A000000 (48% black)',
      re.search(r'panelShadow = 0x7A000000', chrome) is not None)
check('LIGHT panelShadow = 0x38000000 (22% black)',
      re.search(r'panelShadow = 0x38000000', chrome) is not None)
# 用户明说「不描边」—— 深色主题的 outline 必须仍然是 0
check('DARK still does not draw an outline (user: 不描边)',
      re.search(r'outline = 0,', chrome) is not None)

# ---------- 2. 画法 ----------
check('shadow view uses setShadowLayer (not elevation)',
      'setShadowLayer(' in shadow and 'elevation' not in shadow)
check('shadow view forces software layer (hw canvas drops the blur)',
      'setLayerType(LAYER_TYPE_SOFTWARE, null)' in shadow)
check('shadow view draws a hand-built round-rect path',
      'path.addRoundRect(' in shadow)
check('shadow view is non-clickable and hidden from a11y',
      'isClickable = false' in shadow
      and 'IMPORTANT_FOR_ACCESSIBILITY_NO' in shadow)
check('shadow view honours a nonzero vertical offset',
      'dyPx' in shadow and 'setShadowLayer(blurPx, 0f, dyPx, shadowColor)' in shadow)

# 贴边侧必须是直角（0f,0f），外凸侧必须是 radiusPx —— 两分支都要
check('docked side keeps square corners (dockLeft)',
      'floatArrayOf(0f, 0f, radiusPx, radiusPx' in shadow)
check('docked side keeps square corners (dockRight)',
      'floatArrayOf(radiusPx, radiusPx, 0f, 0f' in shadow)

# ---------- 3. 接线 ----------
seg_start = main.find('val pal = islandPal()')
assert seg_start >= 0, 'shadow wiring block not found in toggleSidePanel'
seg_end = main.find('host.visibility = View.VISIBLE', seg_start)
assert seg_end > seg_start, 'shadow wiring block terminator not found'
seg = main[seg_start:seg_end]

check('shadow only installed when palette has a shadow colour',
      'if (pal.panelShadow != 0)' in seg)
check('shadow view is added at index 0 (under scrim and panel)',
      'host.addView(shadow, 0,' in seg)
check('shadow fill uses the panel colour',
      'fillColor = pal.panel' in seg)
check('shadow colour comes from the palette token',
      'shadowColor = pal.panelShadow' in seg)
check('reserved margin covers blur + offset',
      re.search(r'val extra = \(blur \+ dy\)\.toInt\(\) \+ dpV\(2\)', seg) is not None)
check('shadow width = panel width + one side margin',
      'm.w + extra' in seg)
check('shadow height = panel height + both margins',
      'm.h + extra * 2' in seg)
check('shadow is positioned by margin, not CENTER_VERTICAL',
      'topMargin = m.y - extra' in seg and 'CENTER_VERTICAL' not in seg)
check('docked edge gets no shadow inset (0, not extra)',
      'insetLeft = if (dockLeft) 0 else extra' in seg
      and 'insetRight = if (dockLeft) extra else 0' in seg)

# ---------- 4. 同轮修掉的布局 bug：面板纵向落位 ----------
# 面板原来用 CENTER_VERTICAL，但 y 是 clamp 出来的，两者不等价 → 面板与阴影整体错开
check('panel content is top-aligned (gravity TOP, not CENTER_VERTICAL)',
      'android.view.Gravity.START or android.view.Gravity.TOP' in main
      and 'android.view.Gravity.END or android.view.Gravity.TOP' in main)
check('panel content lands exactly on the computed m.y',
      'clp.topMargin = m.y' in main)

print()
if FAILED:
    print('FAILED: %d check(s)' % len(FAILED))
    for f in FAILED:
        print('  - ' + f)
    sys.exit(1)
print('OK: island panel shadow checks passed')
