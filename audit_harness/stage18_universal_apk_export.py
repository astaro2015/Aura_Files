#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def need(path: str, *tokens: str):
    text = (ROOT / path).read_text(encoding='utf-8')
    for token in tokens:
        assert token in text, f"{path}: missing {token!r}"
    return text

build = need('app/build.gradle.kts')
assert 'io.github.reandroid:ARSCLib:1.4.0' in build, 'ARSCLib 1.4.0 dependency missing'
assert 'com.github.MuntashirAkon:apksig-android:4.4.0' in build, 'apksig-android 4.4.0 dependency missing'
assert 'com.android.tools.build:apksig:' not in build, 'host-only AOSP apksig dependency must not be embedded on-device'
dep_lines = '\n'.join(line.strip() for line in build.splitlines() if line.strip().startswith(('implementation(', 'api(', 'compileOnly(', 'runtimeOnly(')))
assert 'APKEditor' not in dep_lines and 'smali' not in dep_lines.lower() and 'JCommand' not in dep_lines, 'full APKEditor CLI stack must not be embedded'

merger = need(
    'app/src/main/java/com/aurafiles/app/tools/UniversalApkMerger.java',
    'ApkBundle',
    'mergeModules(false)',
    'requiredSplitTypes',
    'splitTypes',
    'isSplitRequired',
    'com.android.vending.splits',
    'setApkSignatureBlock(null)',
    'writeApk',
)
assert 'APKEditor 1.4.9' in merger and 'Apache License' in merger, 'APKEditor attribution missing'
assert re.search(r'META-INF.*(?:SF|RSA|DSA|EC)', merger, re.S), 'stale v1 signature cleanup missing'

signer_path = ROOT / 'app/src/main/java/com/aurafiles/app/tools/ApkExportSigner.kt'
assert signer_path.exists(), 'ApkExportSigner.kt missing'
signer = signer_path.read_text(encoding='utf-8')
for token in ['noBackupFilesDir', 'aura-apk-export-v1.pk8', 'ApkSigner', 'ApkVerifier', 'PKCS8EncodedKeySpec', 'CertificateFactory', 'setMinSdkVersion(Build.VERSION_CODES.O)', 'setV1SigningEnabled(false)', 'setV2SigningEnabled(true)', 'setV3SigningEnabled(true)', 'setV4SigningEnabled(false)', 'setMinCheckedPlatformVersion(Build.VERSION_CODES.O)']:
    assert token in signer, f'ApkExportSigner.kt missing {token!r}'
assert 'import android.security.keystore' not in signer and 'KeyStore.getInstance("AndroidKeyStore")' not in signer, 'export signer must not use AndroidKeyStore private keys with apksig'
assert 'AURA_KEYSTORE_FILE' not in signer and 'debug.keystore' not in signer, 'export signer must not reuse Aura app signing key'
assert 'setV1SigningEnabled(true)' not in signer, 'legacy v1/JAR signing must stay disabled on-device'

exporter_path = ROOT / 'app/src/main/java/com/aurafiles/app/tools/UniversalApkExporter.kt'
assert exporter_path.exists(), 'UniversalApkExporter.kt missing'
exporter = exporter_path.read_text(encoding='utf-8')
for token in ['UniversalApkMerger.merge', 'ApkExportSigner.sign', 'ApkExportSigner.verify', 'packageName', 'versionCode', 'delete', 'ensureActive', 'renameTo']:
    assert token in exporter, f'UniversalApkExporter.kt missing {token!r}'
assert 'AuraApksBundle.exportInstalledPackage' not in exporter, 'universal exporter must not silently fall back to APKS'

policy = need(
    'app/src/main/java/com/aurafiles/app/tools/UniversalApkExportPolicy.kt',
    'Поделиться APK',
)

ui_path = ROOT / 'app/src/main/java/com/aurafiles/app/ui/InstalledAppsActivity.kt'
ui = ui_path.read_text(encoding='utf-8')
for token in ['UniversalApkExporter.export', 'UniversalApkExportPolicy.shareLabel', 'Text("APKS")', 'AuraApksBundle.exportInstalledPackage', 'ApkSharePublisher.publish', 'ApkShareDeliveryPolicy.SHARE_MIME']:
    assert token in ui, f'InstalledAppsActivity.kt missing {token!r}'

share_policy = need(
    'app/src/main/java/com/aurafiles/app/tools/ApkShareDeliveryPolicy.kt',
    'application/octet-stream',
    'useMediaStore',
)
publisher = need(
    'app/src/main/java/com/aurafiles/app/tools/ApkSharePublisher.kt',
    'MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)',
    'MediaStore.MediaColumns.IS_PENDING',
    'MediaStore.MediaColumns.RELATIVE_PATH',
)

notice_path = ROOT / 'THIRD_PARTY_NOTICES.md'
if notice_path.exists():
    notice = notice_path.read_text(encoding='utf-8')
    for token in ['APKEditor', 'ARSCLib', 'Apache License, Version 2.0', 'apksig']:
        assert token in notice

print('STAGE18_UNIVERSAL_APK_EXPORT_PASS')
