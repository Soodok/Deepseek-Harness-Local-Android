#!/usr/bin/env python3
"""UI token 门禁：把「设计约定」变成会红的检查。

依据团队引入的 `ai-ui-design-prompts` 技能里那十条验收清单，挑出**能脚本化**的四条
做成门禁。核心思路与 scripts/check-i18n.py 一致：以当前状态为基线，**只许减不许增**，
防止下一次改版又悄悄加回第五套间距/字号/颜色。

  A. 间距：布局里出现的 dp 间距必须落在基线集合内；新增值必须是 8 的倍数。
  B. 字号：textSize 档位只许减不许增；新增档位必须落在收敛目标里。
  C. 颜色字面量：每个布局的 #RRGGBB 数量不得超过基线（只许减少）。
  D. 视觉基线锚点：卡片描边、分组圆角描边、MD3 语义色回映射、DynamicColors 门控
     —— 这四条是 2026-11-10 那轮 UI 修复的结论，谁改回去就立刻红。

运行：python scripts/check-ui-tokens.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')

# ---------- 基线（2026-11-10 实测） ----------
# 布局里出现过的 dp 间距值。只许删，新增必须 8 倍数。
SPACING_BASELINE = {
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 16, 18, 20, 21, 22,
    24, 26, 28, 32, 40, 44, 60, 66, 68, 72,
}
# 现有 14 档字号基线：只许减不许增。
TEXT_SIZE_BASELINE = {9.5, 10.0, 10.5, 11.0, 12.0, 13.0, 14.0, 15.0,
                      16.0, 17.0, 18.0, 20.0, 22.0, 26.0}
# 新增字号只能从收敛目标里挑（字号版的「8 的倍数」）。
TEXT_SIZE_TARGET = {11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 20.0, 26.0}
# 每个布局允许的 hex 字面量上限（终端/日志页是刻意的终端配色，已计入）。
HEX_BASELINE = {
    'activity_terminal.xml': 7,
    'activity_log_viewer.xml': 6,
    'activity_about.xml': 2,
}

SPACING_RE = re.compile(
    r'android:(?:layout_margin(?:Start|End|Top|Bottom|Horizontal|Vertical)?'
    r'|padding(?:Start|End|Top|Bottom|Horizontal|Vertical|Left|Right)?)="(\d+)dp"'
)
TEXT_SIZE_RE = re.compile(r'android:textSize="([0-9.]+)sp"')
HEX_RE = re.compile(r'"#[0-9A-Fa-f]{6,8}"')

FAILED = []


def check(name, ok, detail=''):
    if ok:
        print('PASS  ' + name)
    else:
        print('FAIL  ' + name + (('  [' + detail + ']') if detail else ''))
        FAILED.append(name)


def layouts():
    d = os.path.join(RES, 'layout')
    return sorted(os.path.join(d, f) for f in os.listdir(d) if f.endswith('.xml'))


def read(p):
    with io.open(p, encoding='utf-8') as f:
        return f.read()


spacings, text_sizes, hex_counts = set(), set(), {}
for path in layouts():
    text = read(path)
    spacings.update(int(m.group(1)) for m in SPACING_RE.finditer(text))
    text_sizes.update(float(m.group(1)) for m in TEXT_SIZE_RE.finditer(text))
    n = len(HEX_RE.findall(text))
    if n:
        hex_counts[os.path.basename(path)] = n

# ---------- A. 间距 ----------
new_spacings = sorted(v for v in spacings if v not in SPACING_BASELINE)
check('A1 spacing: no value outside baseline (%d values)' % len(spacings),
      not new_spacings, 'new: %s' % new_spacings)
check('A2 spacing: any new value must be a multiple of 8',
      all(v % 8 == 0 for v in new_spacings), 'offending: %s' % [v for v in new_spacings if v % 8])
# 信息项：当前 8 倍数占比，用于追踪收敛进度（不作门禁）
multiples8 = sum(1 for v in spacings if v % 8 == 0)
print('INFO  spacing: %d/%d values are multiples of 8 (%.0f%%)'
      % (multiples8, len(spacings), 100.0 * multiples8 / max(1, len(spacings))))

# ---------- B. 字号 ----------
new_sizes = sorted(v for v in text_sizes if v not in TEXT_SIZE_BASELINE)
check('B1 text size: no tier outside the baseline (%d tiers in use)' % len(text_sizes),
      not new_sizes, 'new: %s' % new_sizes)
check('B2 text size: any new tier must be in the convergence target',
      all(v in TEXT_SIZE_TARGET for v in new_sizes),
      'offending: %s' % [v for v in new_sizes if v not in TEXT_SIZE_TARGET])
check('B3 text size: tier count stays at or below 14 (converge downwards)',
      len(text_sizes) <= 14, 'now %d' % len(text_sizes))
print('INFO  text size: %s' % sorted(text_sizes))

# ---------- C. 颜色字面量 ----------
over = {k: v for k, v in hex_counts.items() if v > HEX_BASELINE.get(k, 0)}
check('C1 colour literals: per-layout count never grows (%d layout(s) use literals)' % len(hex_counts),
      not over, 'over baseline: %s' % over)

# ---------- D. 视觉基线锚点 ----------
def res_path(*parts):
    return os.path.join(RES, *parts)


bg_card = read(res_path('drawable', 'bg_card.xml'))
check('D1 card token keeps a 1dp stroke (separation must survive dark mode)',
      '<stroke' in bg_card and '1dp' in bg_card
      and ('@color/stroke' in bg_card or '?attr/dshStroke' in bg_card))
check('D2 group card corners come from md3_card_radius',
      '@dimen/md3_card_radius' in bg_card or '18dp' in bg_card)

group = os.path.join(RES, 'drawable', 'bg_md3_group.xml')
check('D3 group border drawable exists (one card per group, not per row)',
      os.path.exists(group) and '<stroke' in read(group))

themes = read(os.path.join(RES, 'values', 'themes.xml'))
# v1.6.x 主题重做后，语义色统一映射到 md3_* 设计令牌（同一份色板，只是命名对齐 MD3）。
check('D4 MD3 semantic colours map back to our palette',
      'name="colorOnSurface">@color/md3_on_surface<' in themes
      and 'name="colorSurface">@color/md3_surface<' in themes
      and 'name="colorPrimary">@color/accent<' in themes)
# 完整色阶：缺一个就会让对应组件退化成默认色
for token in ('colorSurfaceContainer', 'colorSurfaceContainerHigh',
              'colorSurfaceContainerHighest', 'colorOutlineVariant',
              'colorError', 'colorOnPrimary'):
    check('D4 theme defines %s' % token, 'name="%s">' % token in themes)

dshapp = read(os.path.join(ROOT, 'app', 'src', 'main', 'java', 'app', 'dsh', 'mobile',
                           'DshApp.kt'))
themed = read(os.path.join(ROOT, 'app', 'src', 'main', 'java', 'app', 'dsh', 'mobile',
                           'ThemedActivity.kt'))
# 动态取色按 Google 官方做法（v1.6.x 重做）：
#   · 唯一注册点是 Application.onCreate 的 applyToActivitiesIfAvailable；
#   · Activity 不得 setTheme()，否则会冲掉官方在 onActivityPreCreated 注入的覆盖层；
#   · 不允许自造 content-based 色板（那不是 Monet，会把语义色换成中性灰）。
check('D5 DynamicColors registered in Application',
      'DynamicColors.applyToActivitiesIfAvailable' in dshapp)
check('D5 dynamic colour follows the user switch',
      'ThemeAccent.dynamicEnabled' in dshapp)
settheme_code = [l.split('//')[0] for l in themed.splitlines() if 'setTheme(' in l.split('//')[0]]
check('D5 Activity does not call setTheme', not settheme_code, str(settheme_code[:2]))
check('D5 no content-based pseudo dynamic colour',
      'setContentBasedSource' not in dshapp and 'setContentBasedSource' not in themed)
# 主题只能有一套：重复主题必然漏映射色阶（Theme.Dsh.MD3 就漏过 container*）
themes_txt = read(os.path.join(RES, 'values', 'themes.xml'))
check('D5 single Material 3 DayNight theme',
      'name="Theme.Dsh.MD3"' not in themes_txt
      and 'Theme.Material3.DayNight' in themes_txt)

icon_tint = 0
app_tint = 0
for path in layouts():
    text = read(path)
    icon_tint += text.count('android:tint=')
    app_tint += text.count('app:tint=')
check('D6 icons use android:tint (app:tint is ignored by framework ImageView)',
      app_tint == 0, '%d app:tint left' % app_tint)
print('INFO  tint: %d android:tint / %d app:tint' % (icon_tint, app_tint))

print()
if FAILED:
    print('FAILED (%d):' % len(FAILED))
    for f in FAILED:
        print('  - ' + f)
    sys.exit(1)
print('All UI token checks passed.')
