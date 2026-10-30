#!/usr/bin/env python3
"""设置页 UX 改版回归探针（v1.4.45）。

四条主线（宝宝 2026-10-29 验收的「深改版」）：
  1. 自检/启动详情弹窗结构化 —— ReportDialog 徽章列表，不再 setMessage 纯文本堆砌
  2. 行尾 affordance 统一 —— 动作行用按钮、rowLanguage 补 chevron
  3. 权限状态徽章化 —— 状态值用 chip 底、缺权限行给「去开启」按钮
  4. 维护项收进 MaintenanceActivity 子页，设置主页只留入口

Run: python scripts/probes/_test_settings_ux.py
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


def exists(path):
    return os.path.isfile(path)


settings_kt = read(os.path.join(JAVA, "SettingsActivity.kt"))
maint_kt = read(os.path.join(JAVA, "MaintenanceActivity.kt")) if exists(os.path.join(JAVA, "MaintenanceActivity.kt")) else ""
report_kt = read(os.path.join(JAVA, "ReportDialog.kt")) if exists(os.path.join(JAVA, "ReportDialog.kt")) else ""
layout = read(os.path.join(RES, "layout", "activity_settings.xml"))
maint_layout = read(os.path.join(RES, "layout", "activity_maintenance.xml")) if exists(os.path.join(RES, "layout", "activity_maintenance.xml")) else ""
manifest = read(os.path.join(APP, "AndroidManifest.xml"))

# ------------------------------------------------------- 1. 结构化报告弹窗
check("ReportDialog.kt exists", bool(report_kt))
check("ReportDialog: exposes show()", "fun show(" in report_kt)
check("ReportDialog: no hardcoded colours", not re.findall(r"#[0-9A-Fa-f]{6,8}\b", report_kt))
check("ReportDialog: theme colour tokens", "R.color." in report_kt)
check("self-check uses ReportDialog", maint_kt.count("ReportDialog.show") >= 1)
check("boot-trace uses ReportDialog", maint_kt.count("ReportDialog.show") >= 2)
check("no raw text dump left", 'joinToString("\\n\\n")' not in maint_kt)

# ------------------------------------------------------- 2. 维护子页
check("MaintenanceActivity.kt exists", bool(maint_kt))
check("MaintenanceActivity: extends ThemedActivity", "ThemedActivity()" in maint_kt)
check("MaintenanceActivity: follows OrientationPrefs",
      "OrientationPrefs.applyTo(this)" in maint_kt or "OrientationPrefs.forcePortrait(this)" in maint_kt)
check("MaintenanceActivity: locale wrap", "LocaleHelper.wrap" in maint_kt)
check("manifest: MaintenanceActivity declared", ".MaintenanceActivity" in manifest)
check(
    "manifest: MaintenanceActivity not exported + configChanges",
    re.search(r'android:name="\.MaintenanceActivity"[\s\S]{0,220}?android:exported="false"', manifest) is not None
    and re.search(r'android:name="\.MaintenanceActivity"[\s\S]{0,260}?android:configChanges', manifest) is not None,
)
check("subpage layout exists", bool(maint_layout))
check("subpage layout: MaxWidthLinearLayout", "MaxWidthLinearLayout" in maint_layout)
for row in ("rowPluginKill", "rowSelfCheck", "rowBootTrace", "rowRestart"):
    check("subpage layout: row " + row, row in maint_layout)
    check("main layout: row " + row + " moved out", row not in layout)
check("main layout: rowMaintenance entry exists", "rowMaintenance" in layout)
check("settings code: entry wired", "rowMaintenance" in settings_kt and "MaintenanceActivity" in settings_kt)

# ------------------------------------------------- 2b. 第二轮整理（v1.4.46）
backup_layout = read(os.path.join(RES, "layout", "activity_backup.xml")) if exists(os.path.join(RES, "layout", "activity_backup.xml")) else ""
update_layout = read(os.path.join(RES, "layout", "activity_update_settings.xml")) if exists(os.path.join(RES, "layout", "activity_update_settings.xml")) else ""
backup_kt = read(os.path.join(JAVA, "BackupActivity.kt")) if exists(os.path.join(JAVA, "BackupActivity.kt")) else ""
update_kt = read(os.path.join(JAVA, "UpdateSettingsActivity.kt")) if exists(os.path.join(JAVA, "UpdateSettingsActivity.kt")) else ""

check("BackupActivity.kt exists", bool(backup_kt))
check("UpdateSettingsActivity.kt exists", bool(update_kt))
for name, src in (("BackupActivity", backup_kt), ("UpdateSettingsActivity", update_kt)):
    check(name + ": extends ThemedActivity", "ThemedActivity()" in src)
    check(name + ": follows OrientationPrefs",
          "OrientationPrefs.applyTo(this)" in src or "OrientationPrefs.forcePortrait(this)" in src)
    check(name + ": locale wrap", "LocaleHelper.wrap" in src)
    check("manifest: " + name + " declared", "." + name in manifest)
    check(
        "manifest: " + name + " not exported + configChanges",
        re.search(r'android:name="\.' + name + r'"[\s\S]{0,220}?android:exported="false"', manifest) is not None
        and re.search(r'android:name="\.' + name + r'"[\s\S]{0,260}?android:configChanges', manifest) is not None,
    )

for row in ("rowExportData", "rowImportData", "rowRecoverChats", "rowConfigFiles"):
    check("main layout: " + row + " moved out (backup)", row not in layout)
    check("backup layout: row " + row, row in backup_layout)
for row in ("rowUpdateAutoCheck", "rowUpdateWifiOnly", "rowUpdateIndexProxy",
            "rowUpdateStrategy", "rowUpdateIgnored", "rowUpdateCheckNow"):
    check("main layout: " + row + " moved out (update)", row not in layout)
    check("update layout: row " + row, row in update_layout)

check("main layout: rowBackup entry", "rowBackup" in layout)
check("main layout: rowUpdate entry", "rowUpdate" in layout)
check("settings code: backup entry wired", "BackupActivity" in settings_kt and "rowBackup" in settings_kt)
check("settings code: update entry wired", "UpdateSettingsActivity" in settings_kt and "rowUpdate" in settings_kt)
check("maintenance layout: rowViewLog moved in", "rowViewLog" in maint_layout)
check("maintenance code: rowViewLog wired", "rowViewLog" in maint_kt)
check("main layout: rowViewLog moved out", "rowViewLog" not in layout)
# 注意用 `@string/settings_section_voice"` 精确匹配：主页里还有一个
# `settings_section_voice_plugins`（扩展与语音入口），纯前缀匹配会误报。
check("main layout: voice section removed",
      '@string/settings_section_voice"' not in layout)
# v1.4.58：权限 7 行收进权限中心子页（两个分类入口），主页只留 rowPermEngine/rowPermCaps
check("main layout: rowMic moved out (permission center)", "rowMic" not in layout)
perm_layout = read(os.path.join(RES, "layout", "activity_permission_center.xml")) if exists(os.path.join(RES, "layout", "activity_permission_center.xml")) else ""
check("permission center layout: rows moved in", all(r in perm_layout for r in
      ("rowPriv", "rowRootStatus", "rowShizukuStatus", "rowAccess", "rowStorage", "rowNotif", "rowMic")))
check("main layout: perm entries wired", "rowPermEngine" in layout and "rowPermCaps" in layout)
check("settings code: perm center wired", "PermissionCenterActivity" in settings_kt and "rowPermEngine" in settings_kt)

# ------------------------------------------------- 2c. 第三轮压缩（v1.4.56）
appearance_layout = read(os.path.join(RES, "layout", "activity_appearance.xml")) if exists(os.path.join(RES, "layout", "activity_appearance.xml")) else ""
ext_voice_layout = read(os.path.join(RES, "layout", "activity_extension.xml")) if exists(os.path.join(RES, "layout", "activity_extension.xml")) else ""
appearance_kt = read(os.path.join(JAVA, "AppearanceActivity.kt")) if exists(os.path.join(JAVA, "AppearanceActivity.kt")) else ""
ext_voice_kt = read(os.path.join(JAVA, "ExtensionVoiceActivity.kt")) if exists(os.path.join(JAVA, "ExtensionVoiceActivity.kt")) else ""

check("AppearanceActivity.kt exists", bool(appearance_kt))
check("ExtensionVoiceActivity.kt exists", bool(ext_voice_kt))
for name, src in (("AppearanceActivity", appearance_kt), ("ExtensionVoiceActivity", ext_voice_kt)):
    check(name + ": extends ThemedActivity", "ThemedActivity()" in src)
    check(name + ": follows OrientationPrefs",
          "OrientationPrefs.applyTo(this)" in src or "OrientationPrefs.forcePortrait(this)" in src)
    check(name + ": locale wrap", "LocaleHelper.wrap" in src)
    check("manifest: " + name + " declared", "." + name in manifest)

def has_id(text, rid):
    return re.search(r'android:id="@\+id/' + rid + r'"', text) is not None


for row in ("rowTheme", "rowLandscape", "rowScale", "rowImmersive", "rowFilesDir", "rowAccent", "rowLayoutMode"):
    check("main layout: " + row + " moved out (appearance)", not has_id(layout, row))
    check("appearance layout: row " + row, has_id(appearance_layout, row))
# v1.4.58：扩展中心回主页第一屏，其余语音/插件行留在子页
check("main layout: rowExt on first screen", has_id(layout, "rowExt"))
check("extension layout: rowExt moved out", not has_id(ext_voice_layout, "rowExt"))
for row in ("rowPlugin", "rowAsrApps", "rowVoiceService", "rowTts", "rowPet"):
    check("main layout: " + row + " moved out (extension)", not has_id(layout, row))
    check("extension layout: row " + row, has_id(ext_voice_layout, row))

check("main layout: personalization section", "settings_section_personalization" in layout)
check("main layout: rowAppearance entry", "rowAppearance" in layout)
check("main layout: rowExtensionVoice entry", "rowExtensionVoice" in layout)
check("settings code: appearance entry wired", "AppearanceActivity" in settings_kt and "rowAppearance" in settings_kt)
check("settings code: extension entry wired", "ExtensionVoiceActivity" in settings_kt and "rowExtensionVoice" in settings_kt)

# ------------------------------------------- 2d. 子页 id 引用必须存在于自身布局
# v1.4.58 事故：valExt 搬回主页后，扩展子页 refreshExt() 仍 findViewById(valExt)
# → onResume NPE，点「语音与插件」应用重启。这条检查把同类悬空引用挡在提交前。
SUB_PAGES = (
    ("AppearanceActivity.kt", "activity_appearance.xml"),
    ("ExtensionVoiceActivity.kt", "activity_extension.xml"),
    ("BackupActivity.kt", "activity_backup.xml"),
    ("UpdateSettingsActivity.kt", "activity_update_settings.xml"),
    ("MaintenanceActivity.kt", "activity_maintenance.xml"),
)
for kt_name, xml_name in SUB_PAGES:
    kt_path = os.path.join(JAVA, kt_name)
    xml_path = os.path.join(RES, "layout", xml_name)
    if not (exists(kt_path) and exists(xml_path)):
        continue
    kt_ids = set(re.findall(r"R\.id\.(\w+)", read(kt_path)))
    xml_ids = set(re.findall(r'android:id="@\+id/(\w+)"', read(xml_path)))
    missing = sorted(kt_ids - xml_ids)
    check("%s: R.id 全部存在于 %s" % (kt_name, xml_name), not missing,
          "悬空引用 " + ", ".join(missing))

# ------------------------------------------------------- 3. affordance
row_blocks = {}
for m in re.finditer(r'android:id="@\+id/(row\w+)"', layout):
    row_blocks.setdefault(m.group(1), [m.start(), len(layout)])
starts = sorted((v[0], k) for k, v in row_blocks.items())
for i, (pos, name) in enumerate(starts):
    end = starts[i + 1][0] if i + 1 < len(starts) else len(layout)
    row_blocks[name][1] = end

rl = row_blocks.get("rowLanguage")
check("rowLanguage: opens dialog affordance (chevron)", bool(rl) and "ic_chevron" in layout[rl[0]:rl[1]])

# 动作行（导出/导入/恢复）已迁入备份子页，按钮 affordance 在子页布局里校验
bak_blocks = {}
for m in re.finditer(r'android:id="@\+id/(row\w+)"', backup_layout):
    bak_blocks.setdefault(m.group(1), [m.start(), len(backup_layout)])
bak_starts = sorted((v[0], k) for k, v in bak_blocks.items())
for i, (pos, name) in enumerate(bak_starts):
    end = bak_starts[i + 1][0] if i + 1 < len(bak_starts) else len(backup_layout)
    bak_blocks[name][1] = end
for btn, row in (("btnExport", "rowExportData"), ("btnImport", "rowImportData"), ("btnRecover", "rowRecoverChats")):
    blk = bak_blocks.get(row)
    check("backup action row %s: pill button %s" % (row, btn), bool(blk) and btn in backup_layout[blk[0]:blk[1]])
    check("backup action row %s: chevron removed" % row, bool(blk) and "ic_chevron" not in backup_layout[blk[0]:blk[1]])

# ------------------------------------------------------- 4. 权限徽章（v1.4.58：按钮删除，整行即入口）
perm_kt = read(os.path.join(JAVA, "PermissionCenterActivity.kt")) if exists(os.path.join(JAVA, "PermissionCenterActivity.kt")) else ""
check("permission rows: status chip drawable (center)", perm_layout.count("bg_status_chip") >= 4)
for btn in ("btnEnableAccess", "btnEnableStorage", "btnEnableNotif", "btnEnableMic"):
    check("permission row: %s removed" % btn, btn not in layout and btn not in perm_layout)
check("perm center code: chip helper used", "asStatusChip" in perm_kt and "bg_status_chip" in perm_kt)
check("settings code: enable buttons unwired", "btnEnableAccess" not in settings_kt and "setEnableActionVisible" not in settings_kt)
# 两段式结构：说明行全宽，不再有按钮挤压（副标题带行动指引）
check("permission rows: two-segment rows (header+detail)", perm_layout.count("两段式") >= 1)
check("permission rows: no enable buttons labelled", "@string/action_go_enable" not in perm_layout)
check("perm center: group extra switch", "EXTRA_GROUP" in perm_kt and "GROUP_ENGINE" in perm_kt and "GROUP_CAPS" in perm_kt)

# ------------------------------------------------------- 5. 主题纪律
for name, text in (("activity_settings.xml", layout), ("activity_maintenance.xml", maint_layout)):
    hard = sorted(set(re.findall(r"#[0-9A-Fa-f]{6,8}", text)))
    check("layout: %s uses theme colours only" % name, not hard, "hardcoded " + ", ".join(hard))

day = set(re.findall(r'<color name="([^"]+)"', read(os.path.join(RES, "values", "colors.xml"))))
night = set(re.findall(r'<color name="([^"]+)"', read(os.path.join(RES, "values-night", "colors.xml"))))
check("palette: values-night defines every day colour", not (day - night), "missing " + ", ".join(sorted(day - night)))
check("palette: soft status tokens in both", {"success_soft", "warning_soft", "danger_soft"} <= (day & night))

# ------------------------------------------------------- 6. i18n
NEW_KEYS = (
    "settings_section_system", "setting_maintenance", "setting_maintenance_sub",
    "setting_backup", "setting_backup_sub", "setting_update_entry_sub",
    "settings_section_personalization", "setting_appearance_sub", "setting_extension_entry_sub",
    "settings_section_voice_plugins", "setting_voice_plugins_sub",
    "action_go_enable", "action_export", "action_import", "action_recover",
    "action_check", "action_disable", "action_restore", "action_restart",
    "report_passed", "report_fixed", "report_warned", "report_failed",
    "report_copy", "report_error",
    "restart_confirm_title", "restart_confirm_body",
)
for loc in ("values", "values-de", "values-zh-rCN", "values-pt-rBR"):
    strings = read(os.path.join(RES, loc, "strings.xml"))
    missing = [k for k in NEW_KEYS if ('name="%s"' % k) not in strings]
    check("i18n: %s has all %d new keys" % (loc, len(NEW_KEYS)), not missing, "missing " + ", ".join(missing))

# ------------------------------------------------------- 7. 既有契约不破坏
check("backup: import error mapping retained (pet probe contract)",
      "not_a_zip" in backup_kt and "import_err_not_zip" in backup_kt)
check("settings: responsive layout retained", "applyResponsiveLayout()" in settings_kt)

print()
if FAILS:
    print("FAILED: %d check(s)" % len(FAILS))
    sys.exit(1)
print("OK: settings UX redesign checks passed")
