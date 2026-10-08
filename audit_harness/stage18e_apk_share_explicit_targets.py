#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
activity = (ROOT / 'app/src/main/java/com/aurafiles/app/ui/InstalledAppsActivity.kt').read_text(encoding='utf-8')

# Regression: sharing is scheduled after the "APK ready" dialog is dismissed, but the actual
# recipient selection is delegated back to Android's Sharesheet.
positive = activity.split('.setPositiveButton("Поделиться")', 1)[1].split('.show()', 1)[0]
assert 'dialog.dismiss()' in positive
assert 'window.decorView.post' in positive
assert 'shareApkFile(exported.file' in positive
assert 'showApkShareTargets' not in activity
assert 'targetsByPackage' not in activity
assert 'setPackage(target.packageName)' not in activity
print('STAGE18E_APK_SHARE_AFTER_DIALOG_PASS')
