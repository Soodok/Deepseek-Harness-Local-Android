#!/usr/bin/env python3
"""引导页 v2（七步 + 双形态）回归门禁。

## 这份探针守什么

引导页每次改版都会丢东西 —— v1.5.x 那次丢了「返回键能出去」，
再往前丢过 Root 二次确认。所以这里不测「页面长什么样」，
只测**改了会静默坏掉的行为**：

1. 七步顺序与页面 View 一一对应（少一个就会漏页且不报错）；
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

checks = []


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def check(name, ok, detail=""):
    checks.append((name, ok, detail))
    print(("  ok    " if ok else "  FAIL  ") + name + (("\n         -> " + detail) if not ok and detail else ""))


act = read(ACT)
layout = read(LAYOUT)

print("\n=== 1. 七步结构 ===")
# 页面 View 必须与 steps 数量一致，否则 progress.max 与翻页边界会错位
page_ids = re.findall(r'findViewById\(R\.id\.(page[A-Z]\w+)\)', act)
check("seven pages are bound", len(page_ids) == 7, "found: %r" % (page_ids,))
for pid in ("pageLanguage", "pageOrientation", "pageTheme", "pageAccent", "pageScreen", "pagePriv", "pageDone"):
    check("layout declares %s" % pid, '@+id/%s"' % pid in layout)
    check("activity binds %s" % pid, 'R.id.%s' % pid in act)
check("progress max derives from pages.size", "progress.max = pages.size" in act)
check("seven steps declared", act.count("Step(R.string.") == 7,
      "got %d" % act.count("Step(R.string."))

print("\n=== 2. 双形态（平板侧栏 / 手机抽屉）===")
check("wide threshold constant exists", "WIDE_DP = 600" in act)
check("form factor keys off screenWidthDp", "screenWidthDp >= WIDE_DP" in act,)
check("side rail is shown on wide screens", "sideRail.visibility = if (wide) View.VISIBLE" in act)
check("steps pill is hidden on wide screens", "btnSteps.visibility = if (wide) View.GONE" in act)
check("config change re-evaluates form factor", "override fun onConfigurationChanged" in act)
check("rail and drawer share one step builder", "railItems.add(stepRow(" in act and "drawerItems.add(stepRow(" in act)
check("step rows are clickable (jumpable)", "row.setOnClickListener" in act)
for vid in ("sideRail", "railSteps", "stepsDrawer", "drawerSteps", "drawerScrim", "drawerPanel", "btnSteps"):
    check("layout declares %s" % vid, '@+id/%s"' % vid in layout)

print("\n=== 3. 配色真的重涂按钮与进度条（v1.5.x 真 bug 回归）===")
check("accent preview block exists", "private fun updateAccentPreview()" in act)
check("progress bar is retinted from the live accent",
      "progress.progressTintList = ColorStateList.valueOf(accent)" in act)
check("primary button is retinted from the live accent",
      "btnNext.background?.mutate()?.setTint(accent)" in act)
check("accent change repaints immediately",
      "Accent.set(this, acc)" in act and "updateAccentPreview()" in act)
check("layout declares the accent preview card", '@+id/accentPreview"' in layout)

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
check("back key still commits from the first page",
      "override fun onBackPressed()" in act and "commit()" in act)
check("back key closes the drawer first", "stepsDrawer.visibility == View.VISIBLE" in act)
check("root double warning preserved", "warnRoot { warnRootAgain { commit() } }" in act)
check("root warning text preserved", "ob_priv_root_paths_warn" in act)
check("root probe stays off the main thread",
      'Thread({' in act and '"probe-root"' in act)
check("shizuku listener is paired",
      "addRequestPermissionResultListener" in act and "removeRequestPermissionResultListener" in act)
# 只否认可执行语句，不否认可注释：文件里确实写着「不能设 requestedOrientation」
# 的说明，用 in 判会把这个说明本身当成违规。
check("orientation is not applied to the dying window",
      "setRequestedOrientation(" not in act and "requestedOrientation =" not in act)
check("six original state keys still saved",
      all(k in act for k in ("KEY_PAGE", "KEY_LANG", "KEY_ORIENTATION", "KEY_THEME", "KEY_ACCENT", "KEY_PRIV")))

print("\n=== 9. 动效存在且不阻塞 ===")
check("page transition animates", "private fun animateStep(" in act)
check("transition fades and shifts",
      ".alpha(0f)" in act and "translationX" in act)
check("done page animation preserved", "private fun playDoneAnimation()" in act)
check("done glow breathes", "scaleX(1.12f)" in act)
check("selected cards get a stroke, not just alpha",
      "setStroke(" in act,
      "v1.5.x only changed alpha 0.62 -> 1.0, which is invisible on dark backgrounds")
check("drawer slides in", "translationY(0f)" in act)

failed = [c for c in checks if not c[1]]
print()
if failed:
    print("%d/%d checks FAILED" % (len(failed), len(checks)))
    sys.exit(1)
print("All onboarding v2 checks passed (%d checks)." % len(checks))
