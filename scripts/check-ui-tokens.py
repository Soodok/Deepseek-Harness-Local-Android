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
# 判据必须落在 <stroke> 属性上，不能全文找 '1dp' —— bg_card.xml 的注释里就写着
# 「旧版是 surface 实心 + 1dp 描边 + 18dp」，全文匹配等于让注释替描边背书。
# 实测：把 stroke 宽度改成 0dp、注释原样保留时，旧判据仍然 PASS（假绿）。
_stroke = re.search(r'<stroke[\s\S]*?/>', bg_card)
check('D1 card token keeps a 1dp stroke (separation must survive dark mode)',
      _stroke is not None
      and re.search(r'android:width="(?:1dp|\?attr/dshCardStroke)"', _stroke.group(0)) is not None
      and ('@color/stroke' in _stroke.group(0) or '?attr/dshStroke' in _stroke.group(0)))
check('D2 group card corners come from md3_card_radius',
      '@dimen/md3_card_radius' in bg_card or '18dp' in bg_card)

group = os.path.join(RES, 'drawable', 'bg_md3_group.xml')
check('D3 group border drawable exists (one card per group, not per row)',
      os.path.exists(group) and '<stroke' in read(group))

themes = read(os.path.join(RES, 'values', 'themes.xml'))
check('D4 MD3 semantic colours map back to our palette',
      'name="colorOnSurface">@color/text_primary<' in themes
      and 'name="colorSurface">@color/surface<' in themes
      and 'name="colorPrimary">@color/accent<' in themes)

src_dir = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'app', 'dsh', 'mobile')
themed_kt = read(os.path.join(src_dir, 'ThemedActivity.kt'))
accent_kt = read(os.path.join(src_dir, 'Accent.kt'))


def strip_kotlin_comments(src):
    """注释里出现 API 名不算调用 —— 本文件多处注释在解释「以前调过什么」。"""
    src = re.sub(r'/\*[\s\S]*?\*/', '', src)
    return re.sub(r'//[^\n]*', '', src)


# Monet 只能给「跟随壁纸」用，而且连它也不能「注入」：
# DynamicColors.applyToActivityIfAvailable 在 API 31+ 走 ColorResourcesOverride →
# ResourcesLoader 替换颜色资源，会改动本 Activity 的 Resources 配置，系统随即再派一次
# configuration change → 第二次 relaunch（真机 / Android 14 实测：点一下闪两次）。
# 所以动态取色改为 SystemAccent 直接读 android.R.color.system_accent1_*，不碰 Resources。
# 本条守住的是「没有任何一处再注入 DynamicColors」＋「动态分支确实走 SystemAccent」。
check('D5 DynamicColors is never injected (it rewrites Resources and re-triggers a relaunch)',
      'DynamicColors.applyTo' not in strip_kotlin_comments(themed_kt)
      and 'setContentBasedSource' not in themed_kt)
check('D5b dynamic accent reads SystemAccent instead of the theme',
      'SystemAccent.primary(' in accent_kt
      and 'MaterialColors.getColor' not in strip_kotlin_comments(accent_kt))

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
