#!/usr/bin/env python3
"""灵动岛「收起态展开只撑开一次」（v1.7.14）契约探针。

锁住的事实：点一次收起态把手，岛只能有一次撑开动作。

回归长什么样（v1.7.13 及以前，用户报的「连续展开两次」）：
    islandCollapsed = false
    renderIslandVisibility()        # ← 同一帧把 row 设成 VISIBLE，
                                    #   此刻 scale/alpha 还是收起收尾复位的 (1,1,1)
    handle.animate()...withEndAction {
        row.scaleX = 0.7f; row.scaleY = 0.4f; row.alpha = 0f   # ← 120ms 后才压小
        renderIslandVisibility()
        row.post { row.animate()...start() }                  # ← 第二次撑开
    }
于是屏幕上是：满宽亮相一次 → 消失/缩到很小 → 再撑开（还带 Overshoot 过冲）。
录屏逐帧量测证实：胶囊宽度出现两轮「窄→宽」，中间三帧整条不见。

因此本探针断言展开分支里：
  1. 三件套起点属性（row.scaleX/scaleY/alpha）必须**早于**
     renderIslandVisibility() 写入 —— 渲染的第一帧就该是小而透明；
  2. 不得再出现 handle 的淡出动画把撑开往后推迟（handle 在展开态恒 hidden，
     对它 animate 只是空转，却制造了「先亮相再压小」的空窗）；
  3. 整段只能有一次 renderIslandVisibility() 与一次 row.animate()。

⚠️ 断言前必须剥掉注释：这段代码的注释里就写着 renderIslandVisibility()，
   不剥会把注释当成调用，位置比较和计数全错（初版就是这么假红的）。

运行：python scripts/probes/_test_island_expand_once.py
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
    out = []
    for line in src.split('\n'):
        out.append(line.split('//', 1)[0])
    return '\n'.join(out)


main = read('app/src/main/java/app/dsh/mobile/MainActivity.kt')

# ---- 切出展开分支 ----
# ⚠️ 不能用 main.find('islandCollapsed = false')：字段声明
#    「private var islandCollapsed = false」出现在文件里更早的位置。
#    先定位 toggleToolbar 的 if (!islandCollapsed) {，再取 } else { 之后。
cstart = main.find('if (!islandCollapsed) {')
assert cstart >= 0, 'toggleToolbar collapse branch not found'
mid = main.find('} else {', cstart)
assert mid > cstart, 'toggleToolbar else branch not found'
end = main.find('\n    }\n', mid)
assert end > mid, 'toggleToolbar terminator not found'
seg = strip_comments(main[mid:end])
collapse = strip_comments(main[cstart:mid])

check('expand branch is non-trivial', len(seg) > 200, 'len=%d' % len(seg))
check('expand branch clears islandCollapsed first', 'islandCollapsed = false' in seg)
check('expand branch renders through renderIslandVisibility()',
      'renderIslandVisibility()' in seg)

# ---- 1. 起点属性必须早于渲染（「两次展开」的直接成因）----
i_render = seg.find('renderIslandVisibility()')
for prop, want in (('row.scaleX', '0.7f'), ('row.scaleY', '0.4f'), ('row.alpha', '0f')):
    token = '%s = %s' % (prop, want)
    i_prop = seg.find(token)
    check('expand branch sets %s before render' % token,
          i_prop >= 0 and i_prop < i_render,
          'prop@%d render@%d' % (i_prop, i_render))

# 起点不能是满尺寸：否则第一帧就是整条岛
head = seg.split('renderIslandVisibility')[0]
check('expand branch does NOT start from full scale on x', 'row.scaleX = 1f' not in head)
check('expand branch does NOT start from opaque alpha', 'row.alpha = 1f' not in head)

# ---- 2. 不靠 handle 动画做时序门（展开态 handle 恒 GONE，动画空转）----
check('expand branch has no handle.animate() gate', 'handle.animate()' not in seg)
check('expand branch has no withEndAction gate', 'withEndAction' not in seg)

# ---- 3. 只渲染一次、只撑开一次 ----
check('expand branch renders exactly once',
      seg.count('renderIslandVisibility()') == 1,
      'count=%d' % seg.count('renderIslandVisibility()'))
check('expand branch animates the row exactly once',
      seg.count('row.animate()') == 1,
      'count=%d' % seg.count('row.animate()'))

# ---- 4. 收尾仍用活跃度透明度，不许硬写 1f（引擎空闲该退到 0.55）----
check('expand branch finishes with islandIdleAlphaFor(lastRenderedState)',
      'islandIdleAlphaFor(lastRenderedState)' in seg)
check('expand branch still re-pivots after layout (width is 0 when GONE)',
      'row.post {' in seg and 'row.pivotX' in seg)
check('expand branch falls back to screen width when row.width is still 0',
      'if (row.width > 0) row.width / 2f' in seg
      and 'resources.displayMetrics.widthPixels / 2f' in seg)

# ---- 5. 契约未破：收起分支仍是「先状态、后渲染」且带淡出动画 ----
check('collapse branch still flips islandCollapsed = true', 'islandCollapsed = true' in collapse)
check('collapse branch still renders before the fade-out',
      0 <= collapse.find('renderIslandVisibility()') < collapse.find('row.animate()'))

total = 18
if FAILED:
    print('')
    print('%d/%d check(s) failed: %s' % (len(FAILED), total, ', '.join(FAILED)))
    sys.exit(1)
print('')
print('OK  all %d checks passed' % total)