#!/usr/bin/env python3
"""灵动岛侧面板状态机契约探针（v1.7.22）。

## 这个探针守的是什么

用户报「灵动岛完全全透明、点屏幕空白处才能唤出来」。根因是侧面板把
`sideHost.visibility == VISIBLE` 当成「面板打开」：

    打开路径 = visibility = VISIBLE → alpha = 0f → 120ms 淡入

只要淡入被打断（切后台 / 锁屏 / 来电 / 旋屏，帧回调停摆，动画冻结在当前值），
host 就永久停在「VISIBLE + 完全透明」，于是：

  · renderIslandVisibility 里 panelOpen 恒 true → showHandle = false
    → 把手 GONE，屏幕上什么都看不见；
  · 那层 MATCH_PARENT、isClickable=true 的透明 scrim 还在最上层吃点击
    → 点任意空白命中它 → 走关闭分支 → 把手回来。

所以这个探针锁三件事：

  1. **状态与视图分离**：必须有显式 sidePanelOpen 字段，且
     renderIslandVisibility 的 panelOpen 读它、不读 sideHost.visibility。
  2. **归零必须一处收口**：resetSidePanel 同时写状态 + visibility + alpha，
     且收尾把 alpha 复位成 1（否则下次打开从 0 起，任何一次打断都停在透明）。
  3. **没有旁路**：任何地方都不许再直接写 sideHost?.visibility = GONE
     绕过状态归零（那会让逻辑状态残留成「开着」，把手照样被藏）。

判据只在**去注释后的代码**上跑：注释里提到旧写法（解释为什么不能那样做）
不该被当成违规。

运行：python scripts/probes/_test_island_side_panel_state.py
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


def strip_comments(src):
    """去掉 // 行注释与 /* */ 块注释，保留行数（空行占位）。"""
    out = []
    in_block = False
    for ln in src.splitlines():
        if in_block:
            if '*/' in ln:
                in_block = False
                ln = ln.split('*/', 1)[1]
            else:
                out.append('')
                continue
        # 块注释起点（同一行内可能同时有代码与注释，只保留注释前部分）
        while '/*' in ln:
            head, rest = ln.split('/*', 1)
            if '*/' in rest:
                ln = head + rest.split('*/', 1)[1]
            else:
                ln = head
                in_block = True
                break
        idx = ln.find('//')
        if idx >= 0:
            # 排除 URL 里的 //（本文件没有，但保守处理）
            ln = ln[:idx]
        out.append(ln)
    return '\n'.join(out)


def check(name, ok, detail=''):
    if ok:
        print('PASS  ' + name)
    else:
        print('FAIL  ' + name + (('  [' + detail + ']') if detail else ''))
        FAILED.append(name)


raw = read('app/src/main/java/app/dsh/mobile/MainActivity.kt')
main = strip_comments(raw)

# ---------- 1. 状态与视图分离 ----------
check('explicit sidePanelOpen state field exists',
      re.search(r'private var sidePanelOpen = false', main) is not None)

# panelOpen 的取值必须来自逻辑状态。允许注释里出现 sideHost.visibility，
# 但赋值语句右边只能是 sidePanelOpen。
check('renderIslandVisibility reads logical state, not view visibility',
      'val panelOpen = sidePanelOpen' in main
      and 'val panelOpen = sideHost?.visibility == View.VISIBLE' not in main)

check('toggleSidePanel decides by logical state',
      'if (sidePanelOpen) {' in main)

# ---------- 2. 归零一处收口 ----------
check('resetSidePanel helper exists', 'private fun resetSidePanel(animate: Boolean)' in main)
check('resetSidePanel clears the logical state first',
      re.search(r'private fun resetSidePanel\(animate: Boolean\) \{\s*\n\s*sidePanelOpen = false', main) is not None)

reset_body = ''
if 'private fun resetSidePanel(animate: Boolean)' in main:
    reset_body = main.split('private fun resetSidePanel(animate: Boolean)')[1].split('private fun ')[0]

# 关键不变量：收尾必须把 alpha 复位成 1。漏了这一步，下一次打开就是
# 「VISIBLE + alpha 0」的起点，任何一次打断都会让面板停在完全透明。
check('resetSidePanel restores alpha to 1 on the immediate path',
      'h.alpha = 1f' in reset_body)
check('resetSidePanel restores alpha to 1 inside the fade-out callback',
      re.search(r'withEndAction \{[^}]*h\.alpha = 1f', reset_body) is not None)
check('resetSidePanel cancels in-flight animations before touching alpha',
      'h.animate().cancel()' in reset_body)

# ---------- 3. 没有旁路 ----------
# 直接写 sideHost?.visibility = GONE / VISIBLE 的地方一律算旁路：
# 它只改视图、不改 sidePanelOpen，逻辑状态会残留成「开着」。
stray = [ln.strip() for ln in main.splitlines()
         if re.search(r'sideHost\?\.visibility\s*=', ln)]
check('no direct sideHost?.visibility writes remain', not stray,
      '; '.join(stray[:4]))

# 打开路径：状态必须在淡入前落定，并取消残留动画
check('open path sets logical state before fading in',
      re.search(r'host\.visibility = View\.VISIBLE\s*\n\s*host\.alpha = 0f\s*\n\s*sidePanelOpen = true', main) is not None)
check('open path cancels a stale animation before starting a new one',
      re.search(r'sidePanelOpen = true\s*\n\s*host\.animate\(\)\.cancel\(\)', main) is not None)

# ---------- 4. 四处「强制关」都走收口 ----------
# 换停靠边 / 回首页 / 贴顶贴底 / 拖拽吸附 —— 这四条路径以前各自写 GONE。
for label in [
    'dock change in onResume',
    'bottom toolbar onHome',
    'applyDock TOP/BOTTOM',
    'snapHandleToEdge',
]:
    check('force-close path routes through resetSidePanel: ' + label,
          main.count('resetSidePanel(animate = false)') >= 4)

# ---------- 5. onResume 自愈 ----------
# 面板逻辑上开着但 alpha 停在中间值（淡入被冻结）→ 回前台补到 1，
# 否则半透明面板会继续吃掉全屏点击。
check('onResume heals a half-faded panel alpha',
      re.search(r'if \(sidePanelOpen\) sideHost\?\.let \{ if \(it\.alpha < 1f\) it\.alpha = 1f \}', main) is not None)

# ---------- 6. 其它读方也改用逻辑状态 ----------
check('render(state) updates panel by logical state',
      re.search(r'if \(sidePanelOpen\) \{\s*\n\s*sidePanel\?\.update', main) is not None)
check('back press closes panel by logical state',
      'if (sidePanelOpen) { closeSidePanelNow(); return }' in main)

print()
if FAILED:
    print('FAILED: %d check(s)' % len(FAILED))
    for f in FAILED:
        print('  - ' + f)
    sys.exit(1)
print('OK: island side panel state checks passed')
