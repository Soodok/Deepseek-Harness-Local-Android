#!/usr/bin/env python3
"""Verify static Material palette resources and the selection wiring.

These checks do not replace Android runtime tests for theme precedence.
"""
from pathlib import Path
import xml.etree.ElementTree as ET
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'app/src/main/res'
JAVA = ROOT / 'app/src/main/java/app/dsh/mobile'
errors = []
names = ('Blue', 'Teal', 'Violet', 'Amber')
required = {
    'colorPrimary', 'colorOnPrimary', 'colorPrimaryContainer', 'colorOnPrimaryContainer',
    'colorSecondary', 'colorOnSecondary', 'colorSecondaryContainer', 'colorOnSecondaryContainer',
    'colorTertiary', 'colorOnTertiary', 'colorTertiaryContainer', 'colorOnTertiaryContainer',
    'colorSurface', 'colorOnSurface', 'colorSurfaceVariant', 'colorOnSurfaceVariant',
    'colorSurfaceContainerLowest', 'colorSurfaceContainerLow', 'colorSurfaceContainer',
    'colorSurfaceContainerHigh', 'colorSurfaceContainerHighest', 'colorOutline', 'colorOutlineVariant',
    'colorError', 'colorOnError', 'colorErrorContainer', 'colorOnErrorContainer',
}
palettes = {}
for qualifier in ('values', 'values-night'):
    path = RES / qualifier / 'md3_palettes.xml'
    root = ET.parse(path).getroot()
    styles = {node.get('name'): node for node in root.findall('style')}
    for name in names:
        key = 'ThemeOverlay.Dsh.Palette.' + name
        node = styles.get(key)
        if node is None:
            errors.append(f'{qualifier}: missing {key}')
            continue
        roles = {item.get('name'): (item.text or '').strip() for item in node.findall('item')}
        missing = required - roles.keys()
        if missing:
            errors.append(f'{qualifier} {name}: missing roles {sorted(missing)}')
        for role, color in roles.items():
            if not re.fullmatch(r'#[0-9A-Fa-f]{6,8}', color):
                errors.append(f'{qualifier} {name}: invalid {role}={color}')
        palettes[(qualifier, name)] = roles
for name in names:
    day, night = palettes.get(('values', name), {}), palettes.get(('values-night', name), {})
    if day.get('colorPrimary') == night.get('colorPrimary') or day.get('colorSurface') == night.get('colorSurface'):
        errors.append(f'{name}: day/night roles unexpectedly identical')

activity = (JAVA / 'AppearanceActivity.kt').read_text(encoding='utf-8')
settings = (JAVA / 'SettingsActivity.kt').read_text(encoding='utf-8')
theme = (JAVA / 'ThemeActivity.kt').read_text(encoding='utf-8')
onboarding = (JAVA / 'OnboardingActivity.kt').read_text(encoding='utf-8')
base = (JAVA / 'ThemedActivity.kt').read_text(encoding='utf-8')
if 'ThemeActivity::class.java' not in activity:
    errors.append('Appearance screen does not open theme settings')
if 'ThemeAccent.labelRes(this)' not in settings:
    errors.append('Settings summary does not reflect selected palette')
if not all('ThemePaletteSelector(' in source and 'ThemeAccent.setSelected' in source for source in (theme, onboarding)):
    errors.append('Theme and onboarding do not share palette selector and preference')
if 'ThemeAccent.overlayRes(this)' not in base or 'theme.applyStyle(paletteOverlay, true)' not in base:
    errors.append('Activity does not apply its static palette overlay')
for item in errors:
    print('FAIL', item)
if errors:
    sys.exit(1)
print('PASS four light/dark Material palettes, theme entry and shared selection wiring')
