#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
activity = (ROOT / 'app/src/main/java/com/aurafiles/app/ui/InstalledAppsActivity.kt').read_text(encoding='utf-8')
publisher = (ROOT / 'app/src/main/java/com/aurafiles/app/tools/ApkSharePublisher.kt').read_text(encoding='utf-8')
provider = (ROOT / 'app/src/main/java/com/aurafiles/app/AuraFileProvider.kt').read_text(encoding='utf-8')

# Real-device regressions: Telegram rejected APK MIME and MAX opened an empty compose screen.
share = activity.split('private fun shareApkFile(', 1)[1].split('private fun requireShareCacheSpace', 1)[0]
assert 'Intent.createChooser(send, chooserTitle)' in share
assert 'Intent.EXTRA_STREAM' in share and 'ClipData.newRawUri' in share
assert 'ApkShareDeliveryPolicy.SHARE_MIME' in share
assert 'setPackage(' not in share and 'setComponent(' not in share
assert 'queryIntentActivities' not in share
assert 'application/vnd.android.package-archive' not in share

# On modern Android, do not expose Aura's private cache provider to messengers at all.
assert 'MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)' in publisher
assert 'MediaStore.Downloads.EXTERNAL_CONTENT_URI' not in publisher
assert 'MediaStore.MediaColumns.RELATIVE_PATH' in publisher
assert 'MediaStore.MediaColumns.IS_PENDING' in publisher
assert 'ApkShareDeliveryPolicy.SHARE_MIME' in publisher
assert '"apk" -> "application/octet-stream"' in provider
print('STAGE18H_APK_SHARE_MEDIASTORE_DISPATCH_PASS')
