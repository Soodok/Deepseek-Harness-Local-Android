#!/usr/bin/env python3
"""引导页 v2（欢迎页 + 八步、双形态）回归门禁。

## 这份探针守什么

引导页每次改版都会丢东西 —— v1.5.x 那次丢了「返回键能出去」，
再往前丢过 Root 二次确认。所以这里不测「页面长什么样」，
只测**改了会静默坏掉的行为**：

1. 九页顺序与步骤定义一一对应，欢迎页不计入八步；
2. 平板/手机双形态的判据在（>=600dp 走侧栏，否则走胶囊 + 抽屉）；
3. 配色真的重涂到按钮与进度条（v1.5.x 的真 bug：只改卡片文字）；
4. 方向页有真机预览控件且真的联动（不是一张静态图）；
5. 能力开关覆盖设置页的六个域，且「推荐配置」不含高危项；
6. 新增的三项偏好（缩放/布局/沉浸）在 commit 里真的落库；
7. 首页「上一步」用 GONE 而不是 INVISIBLE（否则按钮偏右）；
8. 返回键在首页仍能 commit（历史 bug：卡死出不去）。

## 反向验证怎么用

把实现改坏再看对应 check 是否变红。已验证会变红的四处：
  · 把 btnBack 的 GONE 改回 INVISIBLE      → check 7 红
  · 删掉 updateAccentPreview 里的 progressTintList → check 3 红
  · 把 WIDE_DP 从判据里删掉                → check 2 红
  · 从 RECOMMENDED_CAPS 里加入 NOTIFY_READ → check 5 红
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APP = os.path.join(ROOT, "app", "src", "main")
KT = os.path.join(APP, "java", "app", "dsh", "mobile")
ACT = os.path.join(KT, "OnboardingActivity.kt")
LAYOUT = os.path.join(APP, "res", "layout", "activity_onboarding.xml")
PREVIEW = os.path.join(KT, "ui", "LayoutPreviewView.kt")
GLOW = os.path.join(APP, "res", "drawable", "bg_onboarding_glow.xml")

checks = []


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def check(name, ok, detail=""):
    checks.append((name, ok, detail))
    print(("  ok    " if ok else "  FAIL  ") + name + (("\n         -> " + detail) if not ok and detail else ""))


def fn_body(src, signature):
    """取出一个 Kotlin 函数从签名到配对右花括号的源码；找不到返回空串。

    只查「函数名出现在文件里」抓不到「定义了但没真用上」这类回退，
    所以涉及行为的断言都落到具体函数体上。
    """
    start = src.find(signature)
    if start < 0:
        return ""
    brace = src.find("{", start)
    if brace < 0:
        return ""
    depth = 0
    for i in range(brace, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
    return ""


act = read(ACT)
layout = read(LAYOUT)

print("\n=== 1. 欢迎页 + 八步结构 ===")
# 页面与步骤顺序必须一致；欢迎页显示为封面，不计入进度与步数。
expected_pages = ("pageWelcome", "pageLanguage", "pageOrientation", "pageTheme", "pageAccent",
                  "pageScreen", "pageMode", "pageCaps", "pageDone")
expected_steps = ("ob_wizard_welcome_cover_label", "ob_wizard_language_title",
                  "ob_wizard_orientation_title", "ob_wizard_theme_title", "theme_color_section",
                  "ob_wizard_screen_title", "ob_wizard_mode_title", "ob_wizard_caps_title",
                  "ob_wizard_done_title")
page_block = re.search(r'pages\s*=\s*listOf\((.*?)\n\s*\)', act, re.S)
page_ids = re.findall(r'findViewById\(R\.id\.(page[A-Z]\w+)\)', page_block.group(1)) if page_block else []
step_block = re.search(r'private val steps\s*=\s*listOf\((.*?)\n\s*\)', act, re.S)
step_ids = re.findall(r'Step\(R\.string\.(\w+)\)', step_block.group(1)) if step_block else []
check("nine pages are bound in order", tuple(page_ids) == expected_pages, "found: %r" % (page_ids,))
check("cover and eight steps are declared in order", tuple(step_ids) == expected_steps,
      "found: %r" % (step_ids,))
for pid in expected_pages:
    check("layout declares %s" % pid, '@+id/%s"' % pid in layout)
check("progress max excludes the cover", "progress.max = steps.size - 1" in act)
go_to = fn_body(act, "private fun goTo(")
check("progress and step hint exclude the cover",
      "val stepNo = index" in go_to and "val stepCount = steps.size - 1" in go_to
      and "progress.progress = stepNo" in go_to
      and "ob_wizard_step_fmt, stepNo, stepCount" in go_to)
stepper = fn_body(act, "private fun refreshStepper()")
check("step markers leave the cover unnumbered and count from one",
      'index == 0 -> "\\u00b7"' in stepper and "else -> index.toString()" in stepper)

# 拆页后模式选择与能力开关必须各在自己的页面，不能只保留两个空壳。
mode_page = re.search(r'<ScrollView\s+android:id="@\+id/pageMode"(.*?)</ScrollView>', layout, re.S)
caps_page = re.search(r'<ScrollView\s+android:id="@\+id/pageCaps"(.*?)</ScrollView>', layout, re.S)
check("mode page owns privilege choices and prerequisites",
      bool(mode_page) and all('@+id/%s"' % name in mode_page.group(1)
                              for name in ("privContainer", "capRoot", "capShizuku")))
check("capabilities page owns groups and recommended preset",
      bool(caps_page) and all('@+id/%s"' % name in caps_page.group(1)
                              for name in ("capGroups", "capPanels", "btnCapRecommend")))

print("\n=== 2. 双形态（平板侧栏 / 手机抽屉）===")
check("wide threshold constant exists", "WIDE_DP = 600" in act)
check("form factor keys off screenWidthDp", "screenWidthDp >= WIDE_DP" in act,)
check("side rail is shown on wide screens", "sideRail.visibility = if (wide) View.VISIBLE" in act)
check("steps pill is hidden on wide screens", "btnSteps.visibility = if (wide) View.GONE" in act)
check("config change re-evaluates form factor", "override fun onConfigurationChanged" in act)
check("rail and drawer share one step builder", "railItems.add(stepRow(" in act and "drawerItems.add(stepRow(" in act)
check("step rows are clickable (jumpable)",
      "setOnClickListener" in fn_body(act, "private fun stepRow("))
for vid in ("sideRail", "railSteps", "btnSteps"):
    check("layout declares %s" % vid, '@+id/%s"' % vid in layout)
check("mobile steps use Material bottom sheet", "BottomSheetDialog(this)" in act)

print("\n=== 3. Material 配色方案 ===")
dynamic = fn_body(act, "private fun buildDynamicColorOption(")
check("palette step is built", "buildDynamicColorOption()" in act and bool(dynamic))
check("shared selector is used", "ThemePaletteSelector(" in dynamic)
check("palette selector is attached", "addView(selector)" in dynamic)
check("palette reads shared preference", "ThemeAccent.selected(this)" in dynamic)
check("palette choice persists and recreates", "ThemeAccent.setSelected(this, id)" in dynamic and "recreate()" in dynamic)
check("device support reaches selector", "ThemeAccent.dynamicSupported" in dynamic)
check("old accent enum is not offered", "Accent.entries" not in act and "Accent.set(this" not in act)
accent_preview = fn_body(act, "private fun updateAccentPreview(")
check("palette preview is refreshed", "updateAccentPreview()" in dynamic)
check("palette repaints progress and next button",
      "progress.progressTintList = ColorStateList.valueOf(accent)" in accent_preview
      and "btnNext.backgroundTintList = ColorStateList.valueOf(accent)" in accent_preview)

print("\n=== 4. 方向页真机预览 ===")
check("preview view is a real custom view", os.path.exists(PREVIEW))
if os.path.exists(PREVIEW):
    pv = read(PREVIEW)
    check("preview draws instead of inflating a layout",
          "override fun onDraw" in pv and "LayoutInflater" not in pv)
    check("preview switches orientation form", "var landscape: Boolean" in pv)
    check("preview has explicit measure logic", "override fun onMeasure" in pv)
    check("preview reads the live accent", "Accent.current(context)" in pv)
check("layout embeds the preview view",
      "app.dsh.mobile.ui.LayoutPreviewView" in layout)
check("orientation change drives the preview",
      "preview.landscape = land" in act)
check("preview height differs per orientation",
      "dp(if (land) 132 else 176)" in act,
      "if both orientations share a height the user cannot see the difference")
check("orientation page explains each choice", "ob_wizard_orient_how" in act)
check("orientation cards carry icons",
      "R.drawable.ic_display" in act and "R.drawable.ic_layout" in act)

# 判据是「定义 + 真被调用」：只查函数体字面量的话，删掉调用照样绿（反向验证已踩到）。
check("theme page compares dark and light side by side",
      "private fun buildThemePreview()" in act
      and "buildThemePreview()" in act.split("private fun buildThemePreview()")[0],
      "a preview that is declared but never called shows nothing")
check("theme preview paints the opposite scheme",
      "dark.light = false" in act and "light.light = true" in act)
check("preview view can paint the other scheme", "var light: Boolean" in pv)
check("theme preview labels are bound",
      "R.id.themePreviewLabel2" in act)

print("\n=== 5. 能力开关 ===")
check("capability builder exists", "private fun buildCapabilitySwitches()" in act)
check("cap rows use CapabilityGate", "CapabilityGate.setEnabled(" in act)
check("caps are grouped by domain", "val CAP_GROUPS = listOf(" in act)
# 六个域必须都在：环境 / 通知媒体 / 控制 / 个人数据 / 输入 / 系统
for dom in ("setting_cap_domain_env", "setting_cap_domain_notify", "setting_cap_domain_control",
            "setting_cap_domain_personal", "setting_cap_domain_input", "setting_cap_domain_system"):
    check("cap domain %s is offered" % dom, "R.string." + dom in act)
check("group counters are rendered", "private fun refreshCapCounters()" in act)
# 关态与开态必须一眼可分。android.widget.Switch 被 trackTintList 涂成纯强调色后，
# 两态都是同一个蓝点（实测截图无法区分），故必须用 MaterialSwitch。
check("cap switches use MaterialSwitch",
      "materialswitch.MaterialSwitch(this)" in act
      and "android.widget.Switch(this)" not in act,
      "plain Switch loses its on/off contrast once the track is tinted")
check("recommended preset exists", "private fun applyRecommendedCaps()" in act)

rec_block = re.search(r"val RECOMMENDED_CAPS = listOf\((.*?)\)", act, re.S)
check("recommended caps list is parseable", rec_block is not None)
if rec_block:
    rec = rec_block.group(1)
    # 高危项绝不能进「推荐」：看你在干什么 / 代你说话 / 最高权限
    for banned in ("NOTIFY_READ", "NOTIFY_ACTION", "PERSONAL_SMS", "PERSONAL_CALLS",
                   "INPUTWATCH", "KEYWATCH", "APPDATA", "CapabilityGate.ROOT"):
        check("recommended preset excludes %s" % banned, banned not in rec,
              "high-risk capability must be opted into individually")
    check("recommended preset is not empty", "CapabilityGate." in rec)

print("\n=== 6. 新增三项偏好真的落库 ===")
check("scale is saved on commit", "putInt(KEY_PAGE_SCALE, selectedScale)" in act)
check("layout mode is saved on commit", "SettingsLayoutMode.set(this, selectedLayoutMode)" in act)
check("immersive is saved on commit", "MainActivity.setImmersiveEnabled(this, selectedImmersive)" in act)
check("scale choices are bounded to 50..150",
      "SCALE_CHOICES = listOf(75, 90, 100, 110)" in act,
      "must stay inside AppearanceActivity's MIN_PAGE_SCALE..MAX_PAGE_SCALE")
# 90% 与 100% 曾共用一句「默认，平衡」的副标题，实测两行一字不差分不出差别。
check("each scale step gets its own hint",
      "pct == DEFAULT_PAGE_SCALE -> getString(R.string.ob_wizard_scale_normal)" in act
      and "pct == 100 -> getString(R.string.ob_wizard_scale_actual)" in act,
      "two steps sharing one subtitle makes the choice invisible")
for k in ("KEY_SCALE", "KEY_LAYOUT", "KEY_IMMERSIVE"):
    check("%s survives recreate" % k, "putInt(%s" % k in act or "putString(%s" % k in act
          or "putBoolean(%s" % k in act)

print("\n=== 7. 首页按钮不再偏右 ===")
check("back button hides with GONE, not INVISIBLE",
      "btnBack.visibility = if (index == 0) View.GONE else View.VISIBLE" in act,
      "INVISIBLE still occupies layout_weight and pushes btnNext to the middle")
# btnBack 现在用 wrap_content 自然定宽（不设 weight）。真正要守的是两条：
# 它不占权重（否则挤走 btnNext）+ 首页用 GONE 隐藏 —— 缺一条按钮就会偏右。
check("back button does not claim layout_weight",
      re.search(r'@\+id/btnBack"[^>]*android:layout_weight="[1-9]', layout, re.S) is None)
check("next button takes the full row",
      re.search(r'@\+id/btnNext"[^>]*android:layout_weight="1"', layout, re.S) is not None)
# 按钮条必须在 MaxWidthLinearLayout 内部：放在根容器末尾时，平板上会横跨
# 左侧 206dp 步骤栏（实测「Next」一直延伸到屏幕最左），与内容列不对齐。
check("button bar lives inside the content column",
      layout.index('@+id/btnNext"') < layout.index("</app.dsh.mobile.MaxWidthLinearLayout>"),
      "outside it the bar spans the whole screen and ignores the side rail")

print("\n=== 8. 既有行为不退化 ===")
back_pressed = fn_body(act, "override fun onBackPressed()")
finish = fn_body(act, "private fun finishOnboarding()")
check("back key still completes onboarding from the first page",
      "if (page > 0)" in back_pressed and "goTo(page - 1)" in back_pressed
      and "finishOnboarding()" in back_pressed and "commitNow()" in finish)
check("back key closes the drawer first",
      "stepsDrawer?.isShowing == true" in back_pressed and "openStepsDrawer(false)" in back_pressed)
check("root double warning preserved",
      bool(re.search(r'warnRoot\s*\{\s*warnRootAgain\s*\{[^}]*commitNow\(\)', finish, re.S)))
check("root warning text preserved", "ob_priv_root_paths_warn" in act)
check("root probe stays off the main thread",
      'Thread({' in act and '"probe-root"' in act)
check("shizuku listener is paired",
      "addRequestPermissionResultListener" in act and "removeRequestPermissionResultListener" in act)
# 只否认可执行语句，不否认可注释：文件里确实写着「不能设 requestedOrientation」
# 的说明，用 in 判会把这个说明本身当成违规。
check("orientation is not applied to the dying window",
      "setRequestedOrientation(" not in act and "requestedOrientation =" not in act)
check("wizard state survives recreation",
      all(k in act for k in ("KEY_PAGE", "KEY_LANG", "KEY_ORIENTATION", "KEY_THEME", "KEY_PRIV"))
      and "override fun onSaveInstanceState" in act)

print("\n=== 9. 动效存在且不阻塞 ===")
check("page transition animates", "private fun animateStep(" in act)
check("transition fades and shifts",
      ".alpha(0f)" in act and "translationX" in act)
check("done page animation preserved", "private fun playDoneAnimation()" in act)
# 旧断言只查 "scaleX(1.12f)" 这个字面量 —— 实现是「放大一次再缩回就停」时它照样绿
# （2026-11-11 实测：呼吸只跑一个来回）。现在要求真的无限往复，且离开页面会停。
breath = fn_body(act, "private fun startGlowBreath(")
check("done glow breathes", "1.12f" in breath, "amplitude 1.12f must live in startGlowBreath()")
check("done glow breath repeats forever",
      "ValueAnimator.INFINITE" in breath and "ValueAnimator.REVERSE" in breath,
      "a one-shot grow/shrink is not a breathing loop")
check("done animation hands over to the breath loop",
      "startGlowBreath(" in fn_body(act, "private fun playDoneAnimation()"))
check("breath loop stops when leaving the done page",
      "stopGlowBreath()" in fn_body(act, "private fun goTo("),
      "an infinite animator on a GONE page keeps ticking every frame")
check("breath loop stops on destroy",
      "stopGlowBreath()" in fn_body(act, "override fun onDestroy()"))
check("breath loop pauses while the activity is stopped",
      "glowBreath?.pause()" in fn_body(act, "override fun onStop()")
      and "glowBreath?.resume()" in fn_body(act, "override fun onStart()"))
check("breath loop honours the system 'remove animations' switch",
      "areAnimatorsEnabled()" in breath)
check("selected cards expose radio state",
      "MaterialRadioButton" in act and "isChecked = active" in act
      and "info.isChecked = host.isSelected" in act,
      "selection must be visible and exposed to accessibility services")
check("drawer uses the Material bottom sheet", "BottomSheetDialog(this)" in act)

print("\n=== 10. 实机截图抓出的四处视觉缺陷（2026-11-11）===")
# (a) 光晕半径：gradientRadius 不带单位时按 px 解析。density 2.75 的机器上 "100"
#     只有 36dp 半径，而容器是 200dp —— 光晕比 84dp 的 logo 还小。
glow_xml = read(GLOW)
m_radius = re.search(r'android:gradientRadius="([0-9.]+)(dp|%p|%)?"', glow_xml)
m_box = re.search(r'android:id="@\+id/doneGlow"\s+android:layout_width="(\d+)dp"', layout)
radius_ok = False
radius_detail = "gradientRadius not found"
if m_radius and m_box:
    value, unit = float(m_radius.group(1)), m_radius.group(2)
    box = float(m_box.group(1))
    if unit == "dp":
        radius_ok = value * 2 >= box
    elif unit in ("%", "%p"):
        radius_ok = value >= 50
    radius_detail = "gradientRadius=%s%s vs %sdp box (bare number = px)" % (
        m_radius.group(1), unit or "", m_box.group(1))
check("done glow radius has a unit and fills its box", radius_ok, radius_detail)
# 呼吸放大到 1.12 倍会超出 200dp 的 FrameLayout；父级裁剪会把光晕切出方边。
m_done = re.search(r'<FrameLayout\b[^>]*android:clipChildren="false"[^>]*>\s*<View\s+android:id="@\+id/doneGlow"', layout, re.S)
check("done page does not clip the breathing glow", bool(m_done))

# (b) 能力开关行：文字列紧贴开关，长副标题会一直顶到开关边上。
cap_row = fn_body(act, "private fun capRow(")
m_gap = re.search(r'marginEnd = dp\((\d+)\)', cap_row)
check("capability text keeps a gap before the switch",
      bool(m_gap) and int(m_gap.group(1)) >= 12,
      "found: %r" % (m_gap.group(0) if m_gap else None))

# (c) 滚动页：内容滚到页头下方被硬切，圆角卡片会留下一条残边。
scrolls = re.findall(r'<ScrollView\b[^>]*>', layout)
faded = [s for s in scrolls if 'android:requiresFadingEdge="vertical"' in s
         and 'android:fadingEdgeLength="' in s]
check("nine scrolling pages exist", len(scrolls) == 9, "found %d" % len(scrolls))
check("every scrolling page fades at its edges",
      len(scrolls) > 0 and len(faded) == len(scrolls),
      "%d/%d ScrollViews fade" % (len(faded), len(scrolls)))

failed = [c for c in checks if not c[1]]
print()
if failed:
    print("%d/%d checks FAILED" % (len(failed), len(checks)))
    sys.exit(1)
print("All onboarding v2 checks passed (%d checks)." % len(checks))
