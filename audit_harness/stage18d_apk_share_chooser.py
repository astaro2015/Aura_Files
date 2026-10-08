#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
activity = (ROOT / 'app/src/main/java/com/aurafiles/app/ui/InstalledAppsActivity.kt').read_text(encoding='utf-8')
policy = (ROOT / 'app/src/main/java/com/aurafiles/app/tools/ApkShareDeliveryPolicy.kt').read_text(encoding='utf-8')

assert 'SHARE_MIME = "application/octet-stream"' in policy
for token in [
    'ApkSharePublisher.publish',
    'Intent(Intent.ACTION_SEND)',
    'ApkShareDeliveryPolicy.SHARE_MIME',
    'Intent.EXTRA_STREAM',
    'ClipData.newUri',
    'Intent.FLAG_GRANT_READ_URI_PERMISSION',
    'Intent.createChooser(send, chooserTitle)',
]:
    assert token in activity, f'InstalledAppsActivity system share flow missing {token!r}'

share = activity.split('private fun shareApkFile(', 1)[1].split('private fun requireShareCacheSpace', 1)[0]
assert 'setPackage(' not in share, 'APK sharing must not pin a recipient package'
assert 'setComponent(' not in share, 'APK sharing must not pin a recipient Activity'
assert 'queryIntentActivities' not in share, 'APK sharing must use Android Sharesheet instead of a custom resolver'
assert 'application/vnd.android.package-archive' not in share, 'APK send intent must use generic binary MIME for messenger compatibility'
print('STAGE18D_APK_SYSTEM_SHARE_PASS')
