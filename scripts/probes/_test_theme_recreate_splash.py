#!/usr/bin/env python3
"""Guard the splash path when MainActivity is recreated after changing theme.

These source checks are not a substitute for Android lifecycle and WebView tests.
"""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[2]
text = (root / 'app/src/main/java/app/dsh/mobile/MainActivity.kt').read_text(encoding='utf-8')
checks = {
    'only retain state for an already ready page':
        'override fun onRetainNonConfigurationInstance()' in text
        and 'webPageReady && isUsableEngineState(state)' in text
        and 'snapshot.stage == BootTrace.Stage.READY' in text,
    'recreation checks engine session and boot identity':
        'sameEngineSession(retained.engineState, state)' in text
        and 'BootTrace.snapshot.value === retained.bootSnapshot' in text,
    'runtime download still shows startup':
        '!RuntimeInstaller(this).needsRuntimeDownload()' in text,
    'recreation keeps the WebView covered while hiding startup content':
        'root.findViewById<View>(R.id.splashScroll).visibility = View.INVISIBLE' in text
        and 'splashController?.dismiss()' in text,
    'engine state changes restore startup content':
        'private fun restoreStartupSplash()' in text
        and 'restoreStartupSplash()' in text.split('private fun render(state:', 1)[-1],
}
for name, ok in checks.items():
    print(('PASS ' if ok else 'FAIL ') + name)
if not all(checks.values()):
    sys.exit(1)
