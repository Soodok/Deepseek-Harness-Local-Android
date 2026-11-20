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
dock_settings = (java / 'IslandDockActivity.kt').read_text(encoding='utf-8')
prefs = (java / 'CustomVisualPref.kt').read_text(encoding='utf-8')
chrome = (java / 'IslandChrome.kt').read_text(encoding='utf-8')
layout = ET.parse(res / 'layout/view_dsh_bottom_toolbar.xml').getroot()
a = '{http://schemas.android.com/apk/res/android}'
app = '{http://schemas.android.com/apk/res-auto}'
items = {x.get(a + 'id'): x for x in layout.iter() if x.get(a + 'id')}
bar = items.get('@+id/dsh_bottom_toolbar_expanded')
nav = items.get('@+id/dsh_bottom_navigation')
menu_root = ET.parse(res / 'menu/bottom_navigation.xml').getroot()
menu_items = {x.get(a + 'id'): x for x in menu_root.findall('item')}
gesture = (java / 'DshDockGestureLayout.kt').read_text(encoding='utf-8')
checks = {
    'bottom position can be chosen and persisted':
        'IslandDockActivity::class.java' in settings
        and 'CustomVisualPref.setIslandDock(this, IslandDock.BOTTOM)' in dock_settings
        and 'island_bottom_along_ratio' in prefs and 'BOTTOM' in chrome,
    'official Material expressive navigation bar handles three actions':
        bar is not None and bar.tag == 'com.google.android.material.dockedtoolbar.DockedToolbarLayout'
        and nav is not None and nav.tag == 'com.google.android.material.bottomnavigation.BottomNavigationView'
        and nav.get('style') == '@style/Widget.Material3Expressive.BottomNavigationView'
        and nav.get(app + 'itemIconGravity') == 'top'
        and all('@+id/dsh_bottom_toolbar_' + x in menu_items for x in ('home', 'menu', 'settings')),
    'home and settings have labels':
        all(menu_items['@+id/dsh_bottom_toolbar_' + name].get(a + 'title')
            for name in ('home', 'settings')),
    'menu remains icon-only and accessible':
        menu_items['@+id/dsh_bottom_toolbar_menu'].get(a + 'title') == ''
        and menu_items['@+id/dsh_bottom_toolbar_menu'].get(app + 'contentDescription') == '@string/bottom_toolbar_menu',
    'toolbar intercepts downswipe but keeps item clicks':
        'onInterceptTouchEvent' in gesture and 'dy > touchSlop' in gesture
        and 'root.onCollapse = { collapseFromUser() }' in controller
        and 'navigation.setOnItemSelectedListener' in controller,
    'system insets are owned by activity':
        bar is not None and bar.get(app + 'paddingBottomSystemWindowInsets') == 'false'
        and 'val viewportBottom = bottomPad + bottomToolbarInset' in main
        and 'wlp.bottomMargin = viewportBottom' in main,
    'toolbar opens existing menu and settings':
        'override fun onMenu() { showBottomMenu() }' in main
        and 'BottomSheetDialog(activity)' in (java / 'DshBottomMenu.kt').read_text(encoding='utf-8')
        and 'SettingsActivity::class.java' in main,
    'collapse and expand use accessible clicks and persisted drag':
        'collapseButton.setOnClickListener' in controller
        and 'collapsedView.setOnClickListener' in controller
        and 'view.performClick()' in controller
        and 'setIslandBottomAlongRatio(this@MainActivity, ratio)' in main
        and 'collapsedView.performLongClick()' in controller
        and 'override fun onMoveDock()' in main
        and 'setIslandBottomPosition(this@MainActivity, xRatio, yRatio)' in main
        and 'parent.addView(controller.floatingView' in main,
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
