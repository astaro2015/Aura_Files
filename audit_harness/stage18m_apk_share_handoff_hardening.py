#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
activity = (ROOT / 'app/src/main/java/com/aurafiles/app/ui/InstalledAppsActivity.kt').read_text(encoding='utf-8')
publisher = (ROOT / 'app/src/main/java/com/aurafiles/app/tools/ApkSharePublisher.kt').read_text(encoding='utf-8')
policy = (ROOT / 'app/src/main/java/com/aurafiles/app/tools/ApkShareDeliveryPolicy.kt').read_text(encoding='utf-8')
provider = (ROOT / 'app/src/main/java/com/aurafiles/app/AuraFileProvider.kt').read_text(encoding='utf-8')

share = activity.split('private fun shareApkFile(', 1)[1].split('private fun requireShareCacheSpace', 1)[0]

# The final handoff must be the plain Android Sharesheet: no exact package/component routing.
assert 'Intent.createChooser(send, chooserTitle)' in share
assert 'setPackage(' not in share
assert 'setComponent(' not in share
assert 'queryIntentActivities' not in share

# Messenger-facing type is deliberately generic. Do not let ClipData re-infer APK MIME from the URI.
assert 'SHARE_MIME = "application/octet-stream"' in policy
assert 'type = ApkShareDeliveryPolicy.SHARE_MIME' in share
assert 'ClipData.newRawUri' in share, 'APK share must use raw ClipData so MIME is not re-inferred by provider'
assert 'ClipData.newUri' not in share, 'APK share must not re-infer provider MIME into ClipDescription'
assert 'Intent.EXTRA_STREAM' in share
assert 'Intent.FLAG_GRANT_READ_URI_PERMISSION' in share

# Android 10+ should hand messengers a system MediaStore URI, not Aura private-cache URI.
for token in [
    'MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)',
    'MediaStore.MediaColumns.RELATIVE_PATH',
    'MediaStore.MediaColumns.IS_PENDING',
    'ApkShareDeliveryPolicy.SHARE_MIME',
    'openOutputStream',
    'openFileDescriptor',
    'openInputStream',
]:
    assert token in publisher, f'missing MediaStore handoff invariant: {token}'
assert 'MediaStore.Downloads.EXTERNAL_CONTENT_URI' not in publisher, 'modern share must target the explicit primary Downloads volume'

# MediaStore is primary on modern Android, but a verified native Aura provider remains a last-resort
# fallback if an OEM MediaStore insert/publish path fails. Both routes must be preflighted.
assert 'publishWithAuraProvider' in publisher, 'missing OEM fallback to native Aura ContentProvider'
assert 'publishToDownloads' in publisher
assert 'verifyPublished' in publisher
assert 'AuraFileProvider.uriForFile' in publisher
assert 'MediaStore publish failed' in publisher, 'combined failure should preserve MediaStore root cause'
assert 'CancellationException' in publisher, 'publisher must preserve coroutine cancellation'
assert 'runCatching {\n                publishToDownloads' not in publisher, 'MediaStore fallback must not swallow CancellationException'
assert 'resolver.getType(published.uri)' in publisher, 'preflight must verify recipient-visible MIME'
media_fn = publisher.split('private suspend fun publishToDownloads(', 1)[1].split('private fun verifyPublished', 1)[0]
assert 'verifyPublished(context, candidate)' in media_fn, 'MediaStore item must be fully preflighted before it is committed as successful'
assert media_fn.index('verifyPublished(context, candidate)') < media_fn.index('published = true'), 'verification must happen before success flag so failed items are deleted'

# The private fallback itself must expose APK as generic binary, never package-archive MIME.
assert '"apk" -> "application/octet-stream"' in provider
assert 'application/vnd.android.package-archive' not in share

print('STAGE18M_APK_SHARE_HANDOFF_HARDENING_PASS')
