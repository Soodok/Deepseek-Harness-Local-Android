#!/usr/bin/env python3
"""Guard bottom dock integration; Android interaction tests remain necessary."""
from pathlib import Path
import xml.etree.ElementTree as ET
import sys

root = Path(__file__).resolve().parents[2]
java = root / 'app/src/main/java/app/dsh/mobile'
res = root / 'app/src/main/res'
main = (java / 'MainActivity.kt').read_text(encoding='utf-8')
controller = (java / 'DshBottomToolbarController.kt').read_text(encoding='utf-8')
settings = (java / 'AppearanceActivity.kt').read_text(encoding='utf-8')
prefs = (java / 'CustomVisualPref.kt').read_text(encoding='utf-8')
chrome = (java / 'IslandChrome.kt').read_text(encoding='utf-8')
layout = ET.parse(res / 'layout/view_dsh_bottom_toolbar.xml').getroot()
a = '{http://schemas.android.com/apk/res/android}'
app = '{http://schemas.android.com/apk/res-auto}'
items = {x.get(a + 'id'): x for x in layout.iter() if x.get(a + 'id')}
bar = items.get('@+id/dsh_bottom_toolbar_expanded')
checks = {
    'bottom position can be chosen and persisted':
        'IslandDock.BOTTOM' in settings and 'setIslandDock(this, docks[which])' in settings
        and 'island_bottom_along_ratio' in prefs and 'BOTTOM' in chrome,
    'official Material docked toolbar handles three actions':
        bar is not None and bar.tag == 'com.google.android.material.dockedtoolbar.DockedToolbarLayout'
        and all('@+id/dsh_bottom_toolbar_' + x in items for x in ('home', 'menu', 'settings')),
    'system insets are owned by activity':
        bar is not None and bar.get(app + 'paddingBottomSystemWindowInsets') == 'false'
        and 'val viewportBottom = bottomPad + bottomToolbarInset' in main
        and 'wlp.bottomMargin = viewportBottom' in main,
    'toolbar opens existing menu and settings':
        'override fun onMenu() { toggleSidePanel() }' in main
        and 'SettingsActivity::class.java' in main,
    'collapse and expand use accessible clicks and persisted drag':
        'collapseButton.setOnClickListener' in controller
        and 'collapsedView.setOnClickListener' in controller
        and 'view.performClick()' in controller
        and 'setIslandBottomAlongRatio(this@MainActivity, ratio)' in main
        and 'collapsedView.performLongClick()' in controller
        and 'override fun onMoveDock()' in main,
    'keyboard temporarily hides toolbar without mistaking navigation for IME':
        'toolbar.setShown(active && !bottomToolbarImeVisible)' in main
        and 'insets.systemWindowInsetBottom >= (96 * d).toInt()' in main
        and 'content.getLocationOnScreen(location)' in main
        and 'insets.isVisible(android.view.WindowInsets.Type.ime())' in main,
    'webview recovery keeps original stacking and dimensions':
        'parent?.indexOfChild(webView)' in main
        and 'parent?.addView(fresh, index.coerceAtLeast(0), params)' in main,
}
for name, ok in checks.items():
    print(('PASS ' if ok else 'FAIL ') + name)
if not all(checks.values()):
    sys.exit(1)
