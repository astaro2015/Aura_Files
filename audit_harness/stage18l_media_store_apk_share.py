#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
publisher_path = ROOT / 'app/src/main/java/com/aurafiles/app/tools/ApkSharePublisher.kt'
policy_path = ROOT / 'app/src/main/java/com/aurafiles/app/tools/ApkShareDeliveryPolicy.kt'
activity_path = ROOT / 'app/src/main/java/com/aurafiles/app/ui/InstalledAppsActivity.kt'
assert publisher_path.exists()
assert policy_path.exists()
publisher = publisher_path.read_text(encoding='utf-8')
policy = policy_path.read_text(encoding='utf-8')
activity = activity_path.read_text(encoding='utf-8')

for token in [
    'MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)',
    'MediaStore.MediaColumns.DISPLAY_NAME',
    'MediaStore.MediaColumns.MIME_TYPE',
    'MediaStore.MediaColumns.RELATIVE_PATH',
    'MediaStore.MediaColumns.IS_PENDING',
    'Environment.DIRECTORY_DOWNLOADS',
    'openOutputStream',
    'openFileDescriptor',
    'openInputStream',
    'OpenableColumns.DISPLAY_NAME',
    'OpenableColumns.SIZE',
    'resolver.delete(uri, null, null)',
]:
    assert token in publisher, f'MediaStore publisher missing {token!r}'
assert 'MediaStore.Downloads.EXTERNAL_CONTENT_URI' not in publisher
assert 'SHARE_MIME = "application/octet-stream"' in policy
assert 'MEDIASTORE_MIN_SDK = 29' in policy
assert 'ApkSharePublisher.publish' in activity
assert 'Intent.createChooser(send, chooserTitle)' in activity
print('STAGE18L_MEDIASTORE_APK_SHARE_PASS')
