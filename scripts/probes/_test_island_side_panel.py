#!/usr/bin/env python3
"""灵动岛靠边展开（v1.5.6）契约探针。

锁住四类事实，任何一处被改坏都必须变红：

  1. 几何口径：收起 26×44dp、展开 252dp、外凸侧圆角 10/22dp、圆角安全区 48dp、
     展开高度上限 70% 屏高 —— 这些数字散落在 IslandChrome 里，改一个就会和
     设计稿对不上。
  2. 位置计算：贴左边 x=0、贴右边 x=W-w；纵向必须夹在 [48dp, H-h-48dp] 内，
     否则贴边窗口会压进屏幕圆角被裁。
  3. 接线：MainActivity 必须真的走 setupIslandDock → applyDock，toggleToolbar
     必须先分派到 toggleSidePanel；偏好键 island_dock 必须被读写。
  4. 面板内容：重启 / 终端 / 收起 / 插件（含已装列表与「全部」入口）/ 扩展中心 /
     设置 六类条目都在，且插件数据来自 PluginCenter.installedVersions。

运行：python scripts/probes/_test_island_side_panel.py
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


chrome = read('app/src/main/java/app/dsh/mobile/IslandChrome.kt')
panel = read('app/src/main/java/app/dsh/mobile/IslandSidePanel.kt')
main = read('app/src/main/java/app/dsh/mobile/MainActivity.kt')
pref = read('app/src/main/java/app/dsh/mobile/CustomVisualPref.kt')

# ---------- 1. 几何口径 ----------
for const, want in [
    ('COLLAPSED_W_DP', '26'),
    ('COLLAPSED_H_DP', '44'),
    ('PANEL_W_DP', '252'),
    ('CORNER_OUTER_DP', '10'),
    ('CORNER_PANEL_DP', '22'),
    ('EDGE_SAFE_DP', '48'),
    ('MAX_HEIGHT_RATIO', '0.70f'),
]:
    check('geometry constant %s = %s' % (const, want),
          re.search(r'const val %s = %s\b' % (const, re.escape(want)), chrome) is not None)

# ---------- 2. 位置计算 ----------
check('collapsed: LEFT pins x to 0', 'IslandDock.LEFT -> 0' in chrome)
check('collapsed: RIGHT pins x to screenW - w', '(screenW - w).coerceAtLeast(0)' in chrome)
check('collapsed: y clamped by EDGE_SAFE on both ends',
      'coerceIn(safe, (screenH - h - safe)' in chrome)
check('expanded: height clamped by MAX_HEIGHT_RATIO',
      'MAX_HEIGHT_RATIO).roundToInt()' in chrome and 'contentH.coerceIn(' in chrome)
check('expanded: RIGHT mirrors to screenW - w', 'IslandDock.RIGHT -> (screenW - w)' in chrome)
check('outerRadiusPx switches 10 vs 22 by expanded flag',
      'if (expanded) CORNER_PANEL_DP else CORNER_OUTER_DP' in chrome)
check('no Android API imported in IslandChrome (pure math, JVM-testable)',
      'import android' not in chrome and 'import androidx' not in chrome)

# ---------- 3. 接线 ----------
check('MainActivity calls setupIslandDock()', 'setupIslandDock()' in main)
# 分派时允许先关掉运行模式浮层（避免两个浮层叠在一起），所以只锚定调用形态
check('toggleToolbar dispatches to toggleSidePanel when docked',
      'if (islandDock != IslandDock.TOP) {' in main and 'toggleSidePanel(); return }' in main)
check('side dock hides the top XML capsule via state-driven visibility',
      'val showOverlay = !side && !islandCollapsed' in main
      and 'overlay.visibility = if (showOverlay) View.VISIBLE else View.GONE' in main)
check('applyDock restores the XML handle gravity for TOP',
      'Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL' in main)
check('long press cycles TOP -> LEFT -> RIGHT -> TOP',
      'IslandDock.TOP -> IslandDock.LEFT' in main
      and 'IslandDock.LEFT -> IslandDock.RIGHT' in main
      and 'IslandDock.RIGHT -> IslandDock.TOP' in main)
check('dock switch is persisted', 'CustomVisualPref.setIslandDock(this, next)' in main)
check('pref key island_dock exists', 'KEY_DOCK = "island_dock"' in pref)
check('pref reader defaults to TOP',
      'getOrDefault(IslandDock.TOP)' in pref)
check('side handle uses IslandChrome geometry (not hardcoded px)',
      'IslandChrome.collapsedRect(' in main
      and 'COLLAPSED_W_DP' in chrome and 'COLLAPSED_H_DP' in chrome)
check('side panel geometry from IslandChrome.expanded',
      'IslandChrome.expanded(' in main)

# ---------- 4. 面板内容 ----------
check('panel: status header with dot + state + detail',
      'statusText' in panel and 'subText' in panel and 'update(state' in panel)
check('panel: restart row', 'R.string.btn_restart' in panel)
check('panel: terminal row opens TerminalActivity',
      'R.string.btn_terminal' in panel and 'TerminalActivity::class.java' in panel)
check('panel: collapse row fires onCollapse',
      'R.string.btn_hide' in panel and 'onCollapse?.invoke()' in panel)
check('panel: plugin section header with All entry',
      'R.string.setting_plugin' in panel and 'island_side_more' in panel)
check('panel: installed plugins come from PluginCenter.installedVersions',
      'PluginCenter.installedVersions(act)' in panel)
check('panel: empty state text when no plugins',
      'R.string.island_side_no_plugins' in panel)
check('panel: plugin count footer', 'R.string.island_side_plugin_count' in panel)
check('panel: extension center row', 'ExtensionStoreActivity::class.java' in panel)
check('panel: settings row', 'SettingsActivity::class.java' in panel)
check('panel: scrollable when content exceeds 70% height',
      'ScrollView(act)' in panel)

# ---------- 5. 五语种字符串 ----------
for loc in ('values', 'values-zh-rCN', 'values-de', 'values-pt-rBR', 'values-th'):
    s = read('app/src/main/res/%s/strings.xml' % loc)
    missing = [k for k in ('island_side_more', 'island_side_no_plugins',
                           'island_side_plugin_count', 'island_dock_switched')
               if ('name="%s"' % k) not in s]
    check('%s: side-panel strings present' % loc, not missing, ','.join(missing))

print()
if FAILED:
    print('FAILED: %d check(s)' % len(FAILED))
    for f in FAILED:
        print('  - ' + f)
    sys.exit(1)
print('OK: island side panel checks passed')
