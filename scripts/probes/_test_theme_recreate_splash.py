#!/usr/bin/env python3
"""Guard the splash path when MainActivity is recreated after changing theme.

These source checks are not a substitute for Android lifecycle and WebView tests.
"""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[2]
text = (root / 'app/src/main/java/app/dsh/mobile/MainActivity.kt').read_text(encoding='utf-8')
controller = (root / 'app/src/main/java/app/dsh/mobile/ui/SplashController.kt').read_text(encoding='utf-8')
dialog = (root / 'app/src/main/java/app/dsh/mobile/FailureDialog.kt').read_text(encoding='utf-8')
themed = (root / 'app/src/main/java/app/dsh/mobile/ThemedActivity.kt').read_text(encoding='utf-8')
main_layout = (root / 'app/src/main/res/layout/activity_main.xml').read_text(encoding='utf-8')
checks = {
    'only retain state for an already ready page':
        'override fun onRetainNonConfigurationInstance()' in text
        and 'webPageReady && isUsableEngineState(state)' in text
        and '!snapshot.running' in text,
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
    'stale per-attempt failure is not presented as a terminal failure':
        'private fun splashSnapshotForDisplay(' in text
        and 'is EngineSupervisor.State.Failed -> snapshot' in text
        and 'BootTrace.Snapshot(stage = BootTrace.Stage.LOADING)' in text
        and 'snapshotForDisplay = { snapshot -> splashSnapshotForDisplay(snapshot) }' in text
        and 'val snapshot = snapshotForDisplay(rawSnapshot)' in controller
        and 'render(BootTrace.snapshot.value)' in controller,
    'failure dialog uses Material components and semantic colors':
        'MaterialAlertDialogBuilder(activity)' in dialog
        and 'MaterialButton(' in dialog
        and 'bg_dialog_rounded' not in dialog
        and 'R.color.accent' not in dialog,
    'system and app status bars use theme colors':
        'isAppearanceLightStatusBars = lightSurface' in themed
        and 'IslandPalette.themed' in text
        and '?attr/dshIslandText' in main_layout
        and 'w2.decorView.systemUiVisibility = w2.decorView.systemUiVisibility' in text,
}
for name, ok in checks.items():
    print(('PASS ' if ok else 'FAIL ') + name)
if not all(checks.values()):
    sys.exit(1)
