# 门禁：确保没有任何「批量弹权限窗」的代码路径。
import io, re, sys, os

BAD_PATTERNS = [
    # 一次性 requestPermissions 多个权限（数组里超过 1 个元素）
    (r'requestPermissions\s*\(\s*arrayOf\s*\(([^)]*)\)', 'batch requestPermissions'),
]
FORBID_WORDS = [
    'PermissionRequester',
    'requestAllPermissions',
    'requestRemainingPermissions',
]

root = 'app/src/main/java'
issues = []
for dirpath, _, files in os.walk(root):
    for f in files:
        if not f.endswith('.kt'):
            continue
        p = os.path.join(dirpath, f)
        s = io.open(p, encoding='utf-8', errors='ignore').read()
        for pat, label in BAD_PATTERNS:
            for m in re.finditer(pat, s):
                inner = m.group(1)
                # 数逗号：0 个逗号=1 个元素，允许；>=1 个逗号=多元素，禁止
                if inner.count(',') >= 1 and 'Intent' not in inner:
                    line = s[:m.start()].count('\n') + 1
                    issues.append(f'{p}:{line}: {label} -> arrayOf({inner.strip()[:80]})')
        for w in FORBID_WORDS:
            if w in s:
                line = s[:s.index(w)].count('\n') + 1
                issues.append(f'{p}:{line}: forbidden helper "{w}" (bulk permission request)')

if issues:
    print('FAIL: bulk permission request detected (users get a flood of system dialogs)')
    for i in issues:
        print('  ' + i)
    print()
    print('Rule: MIUI/ColorOS grant dangerous permissions one at a time, when the user')
    print('      taps that specific switch. Never request several in one call.')
    sys.exit(1)
print('OK: no bulk permission request (all grants are per-user-action or privileged-silent)')
