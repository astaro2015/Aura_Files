from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
provider = ROOT / 'app/src/main/java/com/aurafiles/app/AuraFileProvider.kt'

assert provider.is_file(), 'AuraFileProvider is missing'
text = provider.read_text(encoding='utf-8')
assert 'class AuraFileProvider : ContentProvider()' in text, 'AuraFileProvider must be an Aura-owned ContentProvider'
assert 'androidx.core.content.FileProvider' not in text, 'AndroidX FileProvider must not be used'
assert 'FileProvider(' not in text, 'Aura provider must not inherit AndroidX FileProvider'
assert 'android:name=".AuraFileProvider"' in manifest or \
       'android:name="com.aurafiles.app.AuraFileProvider"' in manifest, \
       'Manifest must register AuraFileProvider'
assert 'android:name="androidx.core.content.FileProvider"' not in manifest, \
       'Raw androidx FileProvider must not be registered'
assert 'android.support.FILE_PROVIDER_PATHS' not in manifest, \
       'Manifest must not depend on FILE_PROVIDER_PATHS meta-data'
assert '@xml/file_paths' not in manifest, 'Manifest must not reference FileProvider paths XML'
assert 'android:grantUriPermissions="true"' in manifest
assert 'android:exported="false"' in manifest
print('PASS: Aura-owned ContentProvider has no AndroidX/FileProvider meta-data dependency')
