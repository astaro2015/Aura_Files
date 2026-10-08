#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
provider = (ROOT / 'app/src/main/java/com/aurafiles/app/AuraFileProvider.kt').read_text(encoding='utf-8')

# Real-device regression: FileProvider meta-data lookup failed on Xiaomi/MIUI even after
# URI generation was moved out of the static helper. The provider itself must therefore be
# Aura-owned and must not use AndroidX FileProvider at all.
assert 'import android.content.ContentProvider' in provider, 'Aura provider must derive from ContentProvider'
assert 'class AuraFileProvider : ContentProvider()' in provider, 'Aura provider must be a native ContentProvider'
assert 'androidx.core.content.FileProvider' not in provider, 'AndroidX FileProvider must be absent from provider implementation'
assert 'FileProvider(' not in provider, 'Provider must not inherit AndroidX FileProvider'
assert 'android.support.FILE_PROVIDER_PATHS' not in manifest, 'Manifest must not depend on FILE_PROVIDER_PATHS meta-data'
assert '@xml/file_paths' not in manifest, 'Manifest must not reference FileProvider path XML'
for token in [
    'override fun openFile(',
    'override fun query(',
    'override fun getType(',
    'ParcelFileDescriptor.MODE_READ_ONLY',
    'OpenableColumns.DISPLAY_NAME',
    'OpenableColumns.SIZE',
    'canonicalFile',
    'AuraFileProviderPathPolicy.resolve(',
    'AuraFileProviderPathPolicy.resolveIncoming(',
]:
    assert token in provider, f'native provider missing {token!r}'
assert 'android:grantUriPermissions="true"' in manifest
assert 'android:exported="false"' in manifest
print('STAGE18I_NATIVE_SHARE_PROVIDER_PASS')
