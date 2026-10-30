#!/usr/bin/env python3
"""Static regression checks for the native light/dark appearance switch.

Background: the theme engine (ThemePref / ThemeManager + values-night) survived the
v1.4.2x rebases, but the *entry points* did not. The onboarding cards stayed in the
layout while `ThemePref.set()` lost every caller, so the preference was pinned to
DARK forever - the menu looked present but did nothing. These checks keep that from
happening again:

  * every native activity that shows themed UI extends ThemedActivity
  * ThemePref has a real writer, reachable from onboarding AND settings
  * the settings row exists in the layout and is wired in code
  * the onboarding cards are bound (not just present in XML)
  * layouts carry no hardcoded dark-only colours (they would fight values-night)
  * day/night palettes stay in sync, and the three labels exist in all languages

Run: python scripts/probes/_test_theme_menu.py
Exit code 0 = all checks passed.
"""
import os
import re
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


def kt(name):
    return read(os.path.join(JAVA, name))


def layout(name):
    return read(os.path.join(RES, "layout", name))


def ids_in(xml_text):
    root = ET.fromstring(xml_text)
    out = []

    def walk(node):
        for child in node:
            value = child.get(ANDROID + "id")
            if value:
                out.append(value.split("/")[-1])
            walk(child)

    walk(root)
    return out


# ---------------------------------------------------------------- theme engine
themed = kt("ThemedActivity.kt")
check(
    "engine: ThemeMode exposes dark/light/system",
    all(('"' + k + '"') in themed for k in ("dark", "light", "system")),
)
check(
    "engine: ThemeMode carries a label resource per mode",
    "val labelRes: Int" in themed and "R.string.theme_dark" in themed,
)
check(
    "engine: ThemePref reads the uiMode override from values-night",
    "values-night" in themed and "UI_MODE_NIGHT_YES" in themed and "UI_MODE_NIGHT_NO" in themed,
)
check(
    "engine: ThemedActivity applies the preference before super.attachBaseContext",
    "ThemeManager.wrap" in themed and "attachBaseContext" in themed,
)

# --------------------------------------------- writers (the part that was lost)
writers = {}
for name in ("OnboardingActivity.kt", "AppearanceActivity.kt"):
    writers[name] = kt(name).count("ThemePref.set(")
check(
    "writer: appearance page writes the preference",
    writers.get("AppearanceActivity.kt", 0) >= 1,
    "ThemePref.set() not found",
)
check(
    "writer: onboarding writes the preference",
    writers.get("OnboardingActivity.kt", 0) >= 1,
    "ThemePref.set() not found",
)

# ---------------------------------------------------------------- onboarding
onboarding = kt("OnboardingActivity.kt")
check("onboarding: applies the theme (extends ThemedActivity)", "ThemedActivity()" in onboarding)
for card, mode in (("cardThemeDark", "DARK"), ("cardThemeLight", "LIGHT"), ("cardThemeSystem", "SYSTEM")):
    check(
        "onboarding: %s is bound to click + %s" % (card, mode),
        card in onboarding and ("ThemeMode." + mode) in onboarding,
    )
check(
    "onboarding: selection survives recreate (onSaveInstanceState)",
    "KEY_THEME" in onboarding and "putString(KEY_THEME" in onboarding,
)
check(
    "onboarding: selected card is highlighted",
    "txtThemeDark" in onboarding and "txtThemeLight" in onboarding and "txtThemeSystem" in onboarding,
)
check(
    "onboarding: cards exist in the layout",
    {"cardThemeDark", "cardThemeLight", "cardThemeSystem", "txtThemeDark"} <= set(
        ids_in(layout("activity_onboarding.xml"))
    ),
)

# ------------------------------------------------------------------ settings
settings = kt("SettingsActivity.kt")
check("settings: applies the theme (extends ThemedActivity)", "ThemedActivity()" in settings)
# v1.4.56：主题行随「外观与显示」整组收进 AppearanceActivity
appearance = kt("AppearanceActivity.kt")
appearance_ids = set(ids_in(layout("activity_appearance.xml")))
check("appearance: row exists in the layout", "rowTheme" in appearance_ids)
check("appearance: row shows the current value", "valTheme" in appearance_ids)
check(
    "appearance: row is wired to a dialog",
    "R.id.rowTheme" in appearance and "showThemeDialog()" in appearance,
)
check(
    "appearance: dialog persists + recreates",
    "ThemePref.set(this, modes[which])" in appearance and "recreate()" in appearance,
)
check(
    "appearance: value label is resolved from the mode",
    "valTheme" in appearance and "labelRes" in appearance,
)
check("about: applies the theme (extends ThemedActivity)", "ThemedActivity()" in kt("AboutActivity.kt"))

# --------------------------------- code-drawn colours must follow the theme too
# These pages draw status text in code; the literals were tuned for the dark
# palette only (plugin names were literally 0xFFFFFFFF -> invisible in light).
THEMED_PAGES = [
    "AppearanceActivity.kt",
    "ExtensionVoiceActivity.kt",
    "PluginStoreActivity.kt",
    "AboutActivity.kt",
    "ExtensionStoreActivity.kt",
    "SettingsActivity.kt",
    os.path.join("pet", "PetSettingsActivity.kt"),
]
DARK_ONLY = ("0xFF6EE7B7", "0xFF81C995", "0xFFFFB74D", "0xFFFBBF24",
             "0xFFF87171", "0xFF8A94A3", "0xFF7DD3FC", "0xFFB8C0CC",
             "0xFFB0BAC7", "0xFF5A626E", "0xFF4ADE80")
for name in THEMED_PAGES:
    text = kt(name)
    found = sorted({lit for lit in DARK_ONLY if lit in text})
    check("code: %s has no dark-only colour literals" % name, not found, ", ".join(found))
check(
    "code: plugin name no longer hardcodes white",
    "getColor(R.color.text_primary)" in kt("PluginStoreActivity.kt"),
)

# --------------------------------------------------- palettes must be themeable
for name in ("activity_settings.xml", "activity_plugin_store.xml"):
    text = layout(name)
    hard = sorted(set(re.findall(r"#[0-9A-Fa-f]{6,8}", text)))
    check("layout: %s uses theme colours only" % name, not hard, "hardcoded " + ", ".join(hard))

day = set(re.findall(r'<color name="([^"]+)"', read(os.path.join(RES, "values", "colors.xml"))))
night = set(re.findall(r'<color name="([^"]+)"', read(os.path.join(RES, "values-night", "colors.xml"))))
check("palette: values-night defines every day colour", not (day - night), "missing " + ", ".join(sorted(day - night)))
check("palette: accent/text tokens exist in both", {"accent", "text_primary", "text_secondary", "divider", "bg"} <= (day & night))

# ------------------------------------------------------------------- labels
for loc in ("values", "values-zh-rCN", "values-de"):
    strings = read(os.path.join(RES, loc, "strings.xml"))
    missing = [k for k in ("setting_theme", "setting_theme_sub", "theme_dark", "theme_light", "theme_system") if ('name="%s"' % k) not in strings]
    check("i18n: %s has all five theme strings" % loc, not missing, "missing " + ", ".join(missing))

check(
    "asset: theme icon exists",
    os.path.isfile(os.path.join(RES, "drawable", "ic_theme.xml")),
)

print()
if FAILS:
    print("FAILED: %d check(s)" % len(FAILS))
    sys.exit(1)
print("OK: theme menu checks passed")
