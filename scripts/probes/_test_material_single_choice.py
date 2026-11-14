#!/usr/bin/env python3
"""Guard Material single-choice rows and the startup indicator's measured bounds.

These checks inspect resource wiring and do not replace Android device UI tests.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'app/src/main/res'
JAVA = ROOT / 'app/src/main/java/app/dsh/mobile'
ANDROID = '{http://schemas.android.com/apk/res/android}'
APP = '{http://schemas.android.com/apk/res-auto}'

row = ET.parse(RES / 'layout/dialog_single_choice.xml').getroot()
themes = ET.parse(RES / 'values/themes.xml').getroot()
styles = {x.get('name'): x for x in themes.findall('style')}
def items(name):
    return {x.get('name'): (x.text or '').strip() for x in styles[name].findall('item')}

splash = ET.parse(RES / 'layout/view_boot_splash.xml').getroot()
ring = next(x for x in splash.iter() if x.get(ANDROID + 'id') == '@+id/splashRing')
parent = next(x for x in splash.iter() if ring in list(x))
pet = (JAVA / 'pet/PetSettingsActivity.kt').read_text(encoding='utf-8')
priv = (JAVA / 'PermissionCenterActivity.kt').read_text(encoding='utf-8')
mode = (JAVA / 'RunModeOverlay.kt').read_text(encoding='utf-8')
checks = {
    'shared alert overlay selects the Material dialog style':
        items('ThemeOverlay.Dsh.MaterialAlertDialog').get('alertDialogStyle') == '@style/MaterialAlertDialog.Dsh',
    'shared dialog style supplies a single-choice row':
        styles['MaterialAlertDialog.Dsh'].get('parent') == 'MaterialAlertDialog.Material3'
        and items('MaterialAlertDialog.Dsh').get('singleChoiceItemLayout') == '@layout/dialog_single_choice',
    'single-choice row uses Material dialog metrics and a checkable compat view':
        row.tag == 'androidx.appcompat.widget.AppCompatCheckedTextView'
        and row.get(ANDROID + 'id') == '@android:id/text1'
        and row.get(ANDROID + 'paddingStart') == '@dimen/abc_select_dialog_padding_start_material'
        and row.get(ANDROID + 'paddingEnd') == '?attr/dialogPreferredPadding'
        and row.get(ANDROID + 'drawablePadding') == '20dp'
        and row.get(APP + 'drawableStartCompat') == '?android:attr/listChoiceIndicatorSingle',
    'progress ring and parent wrap actual measured size':
        all(node.get(ANDROID + dim) == 'wrap_content' for node in (ring, parent)
            for dim in ('layout_width', 'layout_height'))
        and ring.get(APP + 'indicatorSize') == '144dp'
        and ring.get(APP + 'indicatorInset') == '4dp',
    'custom pet size remains a checked option':
        '(PetPrefs.SIZE_LEVELS.toList() + size).distinct().sorted()' in pet
        and 'values.indexOf(size)' in pet,
    'root confirmation does not preselect an unapplied mode':
        '(row.getChildAt(0) as android.widget.RadioButton).isChecked = row === this' not in priv,
    'runtime mode choices expose radio selection and unavailable current selection':
        'MaterialRadioButton(act)' in mode and 'info.isCheckable = true' in mode
        and 'linuxOn && !currentAvailable' in mode
        and 'android.widget.ScrollView(act)' in mode,
}
for name, ok in checks.items():
    print(('PASS ' if ok else 'FAIL ') + name)
if not all(checks.values()):
    sys.exit(1)
