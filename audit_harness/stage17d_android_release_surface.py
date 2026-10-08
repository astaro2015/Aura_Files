from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
manifest_path = ROOT / 'app/src/main/AndroidManifest.xml'
manifest = ET.parse(manifest_path).getroot()
app = manifest.find('application')
assert app is not None

# Parse every project XML resource: malformed release XML is a hard failure.
xml_files = [manifest_path] + sorted((ROOT / 'app/src/main/res').rglob('*.xml'))
for path in xml_files:
    ET.parse(path)

components = []
for tag in ('activity', 'service', 'receiver', 'provider'):
    for node in app.findall(tag):
        components.append((tag, node.get(ANDROID+'name'), node.get(ANDROID+'exported')))

exported = {(tag, name) for tag, name, value in components if value == 'true'}
expected_exported = {
    ('activity', '.MainActivity'),
    ('activity', '.ui.SplitPackageInstallerActivity'),
}
assert exported == expected_exported, f'unexpected exported components: {exported ^ expected_exported}'

# All components except the two intentional entry points must be explicitly non-exported.
for tag, name, value in components:
    if (tag, name) not in expected_exported:
        assert value == 'false', f'{tag} {name} is not explicitly exported=false'

providers = [n for n in app.findall('provider') if n.get(ANDROID+'name') in {'.AuraFileProvider', 'com.aurafiles.app.AuraFileProvider'}]
assert len(providers) == 1
fp = providers[0]
assert fp.get(ANDROID+'exported') == 'false'
assert fp.get(ANDROID+'grantUriPermissions') == 'true'
assert fp.get(ANDROID+'authorities') == '${applicationId}.fileprovider'

# Backup/device-transfer exclusions for secrets and trusted installer callback state.
sensitive = {
    'aura_credentials.xml',
    'aura_network_profiles.xml',
    'aura_ftp.xml',
    'aura_cloud_tokens.xml',
    'aura_cloud_profiles.xml',
    'aura_yandex_oauth.xml',
    'aura_yandex_oauth_secrets.xml',
    'aura_split_install_callbacks.xml',
}
for rel in ('app/src/main/res/xml/backup_rules.xml', 'app/src/main/res/xml/data_extraction_rules.xml'):
    tree = ET.parse(ROOT / rel)
    paths = {n.get('path') for n in tree.getroot().iter('exclude') if n.get('domain') == 'sharedpref'}
    missing = sensitive - paths
    assert not missing, f'{rel} misses sensitive prefs: {sorted(missing)}'

# Aura's native share provider exposes only the intended external/share-cache roots.
provider_source = (ROOT / 'app/src/main/java/com/aurafiles/app/AuraFileProvider.kt').read_text(encoding='utf-8')
assert 'class AuraFileProvider : ContentProvider()' in provider_source
assert 'androidx.core.content.FileProvider' not in provider_source
for root_name, folder in {
    'shared_storage': 'Environment.getExternalStorageDirectory()',
    'temporary_shares': 'File(context.cacheDir, "shares")',
    'archive_preview': 'File(context.cacheDir, "archive-preview")',
    'backend_open': 'File(context.cacheDir, "backend-open")',
    'vault_open': 'File(context.cacheDir, "vault-open")',
}.items():
    assert f'"{root_name}"' in provider_source and folder in provider_source, f'missing provider root {root_name}'

# JNI/R8 linkage must survive minification.
kt = (ROOT / 'app/src/main/java/com/aurafiles/app/data/NativeFileTime.kt').read_text(encoding='utf-8')
c = (ROOT / 'app/src/main/cpp/file_time.c').read_text(encoding='utf-8')
proguard = (ROOT / 'app/proguard-rules.pro').read_text(encoding='utf-8')
assert 'external fun setModifiedNative' in kt
assert 'Java_com_aurafiles_app_data_NativeFileTime_setModifiedNative' in c
assert '-keep class com.aurafiles.app.data.NativeFileTime' in proguard
assert 'native <methods>;' in proguard

# High-impact permissions are intentional and have reachable product features.
manifest_text = manifest_path.read_text(encoding='utf-8')
ui = (ROOT / 'app/src/main/java/com/aurafiles/app/ui/AuraFileManagerApp.kt').read_text(encoding='utf-8')
installed = (ROOT / 'app/src/main/java/com/aurafiles/app/ui/InstalledAppsActivity.kt').read_text(encoding='utf-8')
installer = (ROOT / 'app/src/main/java/com/aurafiles/app/ui/SplitPackageInstallerActivity.kt').read_text(encoding='utf-8')
assert 'android.permission.MANAGE_EXTERNAL_STORAGE' in manifest_text and 'isExternalStorageManager()' in ui
assert 'android.permission.WRITE_SETTINGS' in manifest_text and 'Settings.System.canWrite' in ui
assert 'android.permission.QUERY_ALL_PACKAGES' in manifest_text and 'getInstalledApplications' in installed
assert 'android.permission.REQUEST_INSTALL_PACKAGES' in manifest_text and 'canRequestPackageInstalls()' in installer

print(f'STAGE17D_ANDROID_RELEASE_SURFACE_PASS xml={len(xml_files)} components={len(components)}')
