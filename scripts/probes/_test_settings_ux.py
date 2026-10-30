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
check("MaintenanceActivity: follows OrientationPrefs", "OrientationPrefs.applyTo(this)" in maint_kt)
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
    check(name + ": follows OrientationPrefs", "OrientationPrefs.applyTo(this)" in src)
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
check("main layout: voice section removed", "settings_section_voice" not in layout)
check("main layout: rowMic kept (permission)", "rowMic" in layout)

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

# ------------------------------------------------------- 4. 权限徽章 + 去开启
check("permission rows: status chip drawable", layout.count("bg_status_chip") >= 4)
for btn in ("btnEnableAccess", "btnEnableStorage", "btnEnableNotif", "btnEnableMic"):
    check("permission row: %s exists" % btn, btn in layout)
check("settings code: chip helper used", "asStatusChip" in settings_kt and "bg_status_chip" in settings_kt)
check("settings code: enable visibility wired", "btnEnableAccess" in settings_kt and "btnEnableStorage" in settings_kt)
check("layout: enable buttons labelled", layout.count("@string/action_go_enable") >= 4)

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
