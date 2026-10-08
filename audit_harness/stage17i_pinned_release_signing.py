from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
bootstrap = (ROOT / 'tools/bootstrap_windows.ps1').read_text(encoding='utf-8-sig')
release_bat = (ROOT / 'BUILD_RELEASE_WINDOWS.bat').read_text(encoding='ascii')
gradle = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')

PIN = '90:2F:F6:17:0C:61:EB:8C:DA:08:EE:60:8E:87:12:76:35:51:4B:B5'

assert f"$ExpectedAuraSigningSha1 = '{PIN}'" in bootstrap
for marker in (
    'function Ensure-AuraSigningKeystore',
    'Do NOT silently create a new signing key here',
    '$env:AURA_KEYSTORE_FILE = $SigningKeystore',
    '$env:AURA_KEYSTORE_PASSWORD = $AuraSigningStorePassword',
    '$env:AURA_KEY_ALIAS = $AuraSigningAlias',
    '$env:AURA_KEY_PASSWORD = $AuraSigningKeyPassword',
    'clean testDebugUnitTest $AssembleTask',
    "'assembleRelease'",
    'function Assert-ApkSigningIdentity',
    'apksigner.bat',
    'Signer #1 certificate SHA-1 digest',
    'function Report-UnitTestResults',
    'UNIT_TEST_SUMMARY.txt',
):
    assert marker in bootstrap, f'bootstrap missing {marker!r}'

for marker in (
    'set \"AURA_BUILD_TYPE=Release\"',
    'testDebugUnitTest',
    'assembleRelease',
    'Aura_Files_1.3.6-release.apk',
    'APK_SIGNING_IDENTITY.txt',
):
    assert marker in release_bat, f'BUILD_RELEASE_WINDOWS.bat missing {marker!r}'

# The Gradle release variant must consume the external AURA_KEYSTORE_* settings
# passed by the Windows builder, rather than embedding any private key in source.
for marker in (
    'AURA_KEYSTORE_FILE',
    'AURA_KEYSTORE_PASSWORD',
    'AURA_KEY_ALIAS',
    'AURA_KEY_PASSWORD',
    'signingConfigs.create("externalRelease")',
    'signingConfigs.findByName("externalRelease")',
):
    assert marker in gradle, f'Gradle release signing missing {marker!r}'

for forbidden in ('debug.keystore', '.jks', '.p12', '.pfx'):
    # build.gradle must never point at a bundled private key.
    assert forbidden not in gradle, f'build.gradle embeds signing material reference: {forbidden}'


show_oauth = (ROOT / 'SHOW_GOOGLE_OAUTH_SHA1.bat').read_text(encoding='utf-8', errors='replace')
assert '-genkeypair' not in show_oauth, 'SHOW_GOOGLE_OAUTH_SHA1.bat must never generate a replacement signing key'
assert PIN in show_oauth, 'SHOW_GOOGLE_OAUTH_SHA1.bat must display the pinned Aura SHA-1'
assert 'Restore the ORIGINAL debug.keystore from backup' in show_oauth

print(f'STAGE17I_PINNED_RELEASE_SIGNING_PASS sha1={PIN}')
