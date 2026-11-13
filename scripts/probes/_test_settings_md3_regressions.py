#!/usr/bin/env python3
"""针对设置页改造中发现的具体回归做结构检查（不代替编译或真机测试）。"""
from pathlib import Path
import xml.etree.ElementTree as ET
import sys

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'app/src/main/res/layout'
JAVA = ROOT / 'app/src/main/java/app/dsh/mobile'
issues = []

# XML 中裸露属性是合法文本节点，普通 XML 解析检查无法识别。
for path in RES.glob('*.xml'):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        issues.append(f'{path.name}: XML parse error: {exc}')
        continue
    for node in root.iter():
        for text in (node.text, node.tail):
            if text and ('app:strokeColor=' in text or 'android:layout_' in text):
                issues.append(f'{path.name}: attribute-like text outside a tag: {text.strip()[:100]}')

# 权限中心的动态标题必须写入实际存在的 toolbar。
permission = (JAVA / 'PermissionCenterActivity.kt').read_text(encoding='utf-8')
layout = (RES / 'activity_permission_center.xml').read_text(encoding='utf-8')
if 'R.id.txtTitle' in permission and '@+id/txtTitle' not in layout:
    issues.append('PermissionCenterActivity: references missing txtTitle')

# 已删除的 ThemeAccent 模式 API 不得残留在编译路径。
for path in JAVA.rglob('*.kt'):
    text = path.read_text(encoding='utf-8')
    for stale in ('ThemeAccent.Mode', 'ThemeAccent.setMode(', 'ThemeAccent.mode(', 'ThemeAccent.customColor('):
        if stale in text:
            issues.append(f'{path.name}: stale theme API {stale}')

for issue in issues:
    print('FAIL ', issue)
if issues:
    sys.exit(1)
print('PASS  no dangling attribute text, missing permission title, or stale theme APIs')
