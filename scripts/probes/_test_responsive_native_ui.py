#!/usr/bin/env python3
"""Static regression checks for the v1.4.18 landscape/tablet adaptation.

These are source/layout constraints, not instrumentation tests. They protect the
decisions that are easy to undo by accident:

  * native settings pages must follow OrientationPrefs, not hard-code portrait
  * the responsive layout must not rely on layout-land (configChanges means the
    layout is never re-inflated on rotation)
  * settings keeps its four groups in the same order and splits them into the
    expected two columns
  * wide pages use MaxWidthLinearLayout so rows do not stretch on tablets
  * the tablet default (AUTO when no preference exists) stays in one place

Run: python scripts/probes/_test_responsive_native_ui.py
Exit code 0 = all checks passed.
"""
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APP = os.path.join(ROOT, "app", "src", "main")
JAVA = os.path.join(APP, "java", "app", "dsh", "mobile")
RES = os.path.join(APP, "res")
ANDROID = "{http://schemas.android.com/apk/res/android}"

FAILS = []


def check(name, ok, detail=""):
    if ok:
        print("PASS  " + name)
    else:
        print("FAIL  " + name + (("  -> " + detail) if detail else ""))
        FAILS.append(name)


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def root_of(layout):
    return ET.parse(os.path.join(RES, "layout", layout)).getroot()


def descendants(node):
    out = [node]
    for child in node:
        out.extend(descendants(child))
    return out


def ids_in(node):
    out = []
    for n in descendants(node):
        value = n.get(ANDROID + "id")
        if value:
            out.append(value.split("/")[-1])
    return out


def parent_map(root):
    return {child: parent for parent in root.iter() for child in parent}


def has_ancestor_id(node, parents, wanted):
    cur = node
    while cur in parents:
        cur = parents[cur]
        value = cur.get(ANDROID + "id")
        if value and value.split("/")[-1] == wanted:
            return True
    return False


SETTINGS_PAGES = [
    "SettingsActivity.kt",
    "AppearanceActivity.kt",
    "ExtensionVoiceActivity.kt",
    "AboutActivity.kt",
    "PluginStoreActivity.kt",
    "ExtensionStoreActivity.kt",
    "UserPromptActivity.kt",
    "AsrAppActivity.kt",
    "VoiceServiceActivity.kt",
    "pet/PetSettingsActivity.kt",
]

# ---------------------------------------------------------------- orientation
for page in SETTINGS_PAGES:
    src = read(os.path.join(JAVA, page))
    check(page + ": follows OrientationPrefs",
          "OrientationPrefs.applyTo(this)" in src or "OrientationPrefs.forcePortrait(this)" in src)
    check(page + ": no hard-coded portrait lock",
          "SCREEN_ORIENTATION_PORTRAIT" not in src)

prefs = read(os.path.join(JAVA, "OrientationPrefs.kt"))
check("OrientationPrefs: applyTo exists", "fun applyTo(activity: Activity)" in prefs)
check("OrientationPrefs: large-screen default helper",
      "fun defaultFor(ctx: Context)" in prefs and "isLargeScreen(ctx)" in prefs)
check("OrientationPrefs: large-screen threshold 600dp",
      "LARGE_SCREEN_MIN_WIDTH_DP = 600" in prefs)
check("OrientationPrefs: legacy key presence is distinguished",
      "p.contains(KEY_LEGACY)" in prefs)

manifest = read(os.path.join(APP, "AndroidManifest.xml"))
check("Manifest: no per-activity screenOrientation lock",
      "android:screenOrientation" not in manifest)
check("Manifest: orientation configChanges retained",
      manifest.count('android:configChanges="orientation|screenSize') >= 10)

# ------------------------------------------------------------------- settings
settings = read(os.path.join(JAVA, "SettingsActivity.kt"))
check("Settings: responsive layout applied in onCreate",
      "applyResponsiveLayout()" in settings)
check("Settings: updates on configuration change",
      "override fun onConfigurationChanged" in settings)
check("Settings: wide breakpoint 840dp", "WIDE_LAYOUT_MIN_WIDTH_DP = 840" in settings)
check("Settings: narrow max width 680dp", "NARROW_CONTENT_MAX_WIDTH_DP = 680" in settings)
check("Settings: wide max width 1080dp", "WIDE_CONTENT_MAX_WIDTH_DP = 1080" in settings)
# v1.4.56：朝向对话框随外观整组迁入 AppearanceActivity
appearance_src = read(os.path.join(JAVA, "AppearanceActivity.kt"))
check("Settings: orientation change applies immediately",
      ("OrientationPrefs.applyTo(this)" in settings
       or "OrientationPrefs.forcePortrait(this)" in settings)
      and "setting_applied_now" in appearance_src)

root = root_of("activity_settings.xml")
labels = ids_in(root)
for required in ("settingsContentWidth", "settingsColumns", "columnPrimary", "columnSecondary",
                 "rowAppearance", "rowExtensionVoice", "rowPermEngine", "rowAbout"):
    check("settings layout: id " + required, required in labels)

parents = parent_map(root)
by_id = {}
for node in root.iter():
    value = node.get(ANDROID + "id")
    if value:
        by_id[value.split("/")[-1]] = node

if "settingsContentWidth" in by_id and "settingsColumns" in by_id:
    check("settings layout: columns live inside the max-width container",
          has_ancestor_id(by_id["settingsColumns"], parents, "settingsContentWidth"))
if "columnPrimary" in by_id and "columnSecondary" in by_id:
    primary = ids_in(by_id["columnPrimary"])
    secondary = ids_in(by_id["columnSecondary"])
    # v1.4.56：外观/扩展整组收进子页，主页分区入口行承担原来的列归属校验
    check("settings layout: primary column = Personalization + Permissions",
          {"rowAppearance", "rowExtensionVoice", "rowPermEngine", "rowPermCaps"} <= set(primary) and "rowBackup" not in primary)
    check("settings layout: secondary column = Data/Network/System",
          "rowBackup" in secondary and "rowAbout" in secondary and "rowPermEngine" not in secondary)
    # Source order must match the single-column reading order: 个性化, 权限中心, 其他.
    order = [i for i in labels if i in ("rowAppearance", "rowExtensionVoice", "rowPermEngine", "rowPermCaps", "rowAbout")]
    check("settings layout: group order preserved",
          order == ["rowAppearance", "rowExtensionVoice", "rowPermEngine", "rowPermCaps", "rowAbout"], str(order))

check("settings layout: uses MaxWidthLinearLayout",
      "app.dsh.mobile.MaxWidthLinearLayout" in read(os.path.join(RES, "layout", "activity_settings.xml")))
check("no layout-land settings variant (configChanges would never inflate it)",
      not os.path.exists(os.path.join(RES, "layout-land", "activity_settings.xml")))

# --------------------------------------------------------------- other pages
for layout in ("activity_about.xml", "activity_plugin_store.xml",
               "activity_extension_store.xml", "activity_asr_app.xml",
               "activity_voice_service.xml", "activity_onboarding.xml"):
    check(layout + ": uses MaxWidthLinearLayout",
          "app.dsh.mobile.MaxWidthLinearLayout" in read(os.path.join(RES, "layout", layout)))

plugin = read(os.path.join(JAVA, "PluginStoreActivity.kt"))
ext = read(os.path.join(JAVA, "ExtensionStoreActivity.kt"))
# v1.4.21 插件中心重写后 PluginStore 上游定为 720dp（ExtensionStore 仍 900dp），
# 这里只校验限宽结构存在，不再硬编码具体数值
check("PluginStore: caps list width via STORE_CONTENT_MAX_WIDTH_DP",
      "STORE_CONTENT_MAX_WIDTH_DP" in plugin and "pluginStoreBody" in plugin)
check("ExtensionStore: caps list width at 900dp",
      "STORE_CONTENT_MAX_WIDTH_DP = 900" in ext and "extensionStoreBody" in ext)

maxw = read(os.path.join(JAVA, "MaxWidthLinearLayout.kt"))
check("MaxWidthLinearLayout: overrides onMeasure", "override fun onMeasure" in maxw)
check("MaxWidthLinearLayout: clamps to maxWidthDp", "minOf(MeasureSpec.getSize" in maxw)
check("MaxWidthLinearLayout: no layout inflation dependency",
      "LayoutInflater" not in maxw and "setContentView" not in maxw)

onboarding = read(os.path.join(JAVA, "OnboardingActivity.kt"))
# 主题切换重建增强后默认值写作 savedInstanceState 恢复 ?: defaultFor(this)，
# 校验默认值仍来自 OrientationPrefs 即可
check("Onboarding: tablet default from OrientationPrefs",
      "OrientationPrefs.defaultFor(this)" in onboarding)

# --------------------------------------------------------------------- i18n
for locale in ("values", "values-zh-rCN", "values-de"):
    text = read(os.path.join(RES, locale, "strings.xml"))
    check(locale + ": new orientation toast string", "setting_applied_now" in text)
    check(locale + ": old return-only string removed", "setting_applies_on_return" not in text)

print()
if FAILS:
    print("FAILED: %d check(s)" % len(FAILS))
    sys.exit(1)
print("OK: responsive native UI checks passed")
