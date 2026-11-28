#!/usr/bin/env python3
"""Guard that semantic Material icons follow theme roles instead of static accent colors."""
from pathlib import Path
import re, sys
ROOT=Path(__file__).resolve().parents[2]
RES=ROOT/'app/src/main/res'
fail=[]
for p in (RES/'layout').glob('*.xml'):
    text=p.read_text(encoding='utf-8')
    for m in re.finditer(r'<(?:ImageView|ImageButton|com\.google\.android\.material\.imageview\.ShapeableImageView)\b[^>]*android:tint="@color/(accent|success|warning|danger)"', text):
        fail.append(f'{p.name}: static semantic icon tint @{m.group(1)}')

theme=(RES/'values/themes.xml').read_text(encoding='utf-8')
for role in ('colorPrimary','colorSecondary','colorTertiary','colorError'):
    if f'name="{role}"' not in theme:
        fail.append(f'theme missing M3 role {role}')

for item in fail: print('FAIL',item)
if fail: sys.exit(1)
print('PASS semantic icons use Material theme roles')
