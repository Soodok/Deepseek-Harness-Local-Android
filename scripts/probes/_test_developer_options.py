#!/usr/bin/env python3
"""Regression checks for the hidden version-tap settings shortcut."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/dsh/mobile"
RES = ROOT / "app/src/main/res"
failures = []

def check(label, condition):
    print(("PASS  " if condition else "FAIL  ") + label)
    if not condition:
        failures.append(label)

about = (JAVA / "AboutActivity.kt").read_text(encoding="utf-8")
prefs = (JAVA / "DeveloperModePrefs.kt").read_text(encoding="utf-8")
activity = (JAVA / "DeveloperOptionsActivity.kt").read_text(encoding="utf-8")
settings = (JAVA / "SettingsActivity.kt").read_text(encoding="utf-8")
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
layout = ET.parse(RES / "layout/activity_settings.xml").getroot()
android_id = "{http://schemas.android.com/apk/res/android}id"

check("About: version label is the unlock target", 'findViewById<TextView>(R.id.txtVersion).setOnClickListener' in about)
check("About: requires seven taps", "versionTapCount >= 7" in about)
check("About: taps expire after five seconds", "now - firstVersionTapAt > 5_000L" in about)
check("Preferences: developer mode defaults to off", '.getBoolean(KEY_ENABLED, false)' in prefs)
check("Settings: developer row follows persisted mode", "DeveloperModePrefs.isEnabled(this)" in settings)
check("Settings: row is refreshed on resume", "refreshDeveloperOptionsEntry()" in settings.split("override fun onResume()", 1)[-1])
check("Developer page: turning master switch off disables the mode", "DeveloperModePrefs.setEnabled(this, false)" in activity)
check("Developer page: refuses launch while developer mode is disabled",
      "if (!DeveloperModePrefs.isEnabled(this))" in activity and
      "finish()" in activity.split("if (!DeveloperModePrefs.isEnabled(this))", 1)[-1].split("return", 1)[0])
check("Developer page: does not bypass capability authorization", "CapabilityGate" in activity and "authorization" in activity)
check("Manifest: developer page is not exported",
      'android:name=".DeveloperOptionsActivity"' in manifest and
      'android:exported="false"' in manifest.split('android:name=".DeveloperOptionsActivity"', 1)[-1].split("/>", 1)[0])
rows = [node for node in layout.iter() if node.get(android_id) == "@+id/rowDeveloperOptions"]
check("Settings layout: developer row exists and starts hidden",
      len(rows) == 1 and rows[0].get("{http://schemas.android.com/apk/res/android}visibility") == "gone")
about_rows = [node for node in layout.iter() if node.get(android_id) == "@+id/rowAbout"]
row_order = [node.get(android_id) for node in layout.iter()
             if node.get(android_id) in ("@+id/rowDeveloperOptions", "@+id/rowAbout")]
check("Settings layout: developer row precedes About and uses middle segment styling",
      len(rows) == 1 and len(about_rows) == 1 and
      row_order == ["@+id/rowDeveloperOptions", "@+id/rowAbout"] and
      rows[0].get("{http://schemas.android.com/apk/res/android}background") == "@drawable/bg_md3_seg_mid" and
      rows[0].get("{http://schemas.android.com/apk/res/android}clickable") == "true" and
      rows[0].get("{http://schemas.android.com/apk/res/android}focusable") == "true")

print()
if failures:
    print("FAILED: %d check(s)" % len(failures))
    raise SystemExit(1)
print("OK: hidden developer options checks passed")
