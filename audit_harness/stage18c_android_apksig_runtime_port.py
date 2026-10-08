#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
settings = (ROOT / 'settings.gradle.kts').read_text(encoding='utf-8')
build = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
signer = (ROOT / 'app/src/main/java/com/aurafiles/app/tools/ApkExportSigner.kt').read_text(encoding='utf-8')
notice = (ROOT / 'THIRD_PARTY_NOTICES.md').read_text(encoding='utf-8')

checks = {
    'JitPack repository is available': 'https://jitpack.io' in settings,
    'JitPack is scoped to apksig-android publisher': 'includeGroup("com.github.MuntashirAkon")' in settings,
    'Android apksig port dependency is pinned': 'com.github.MuntashirAkon:apksig-android:4.4.0' in build,
    'desktop/host apksig dependency is absent': 'com.android.tools.build:apksig:' not in build,
    'public apksig API remains in use': 'import com.android.apksig.ApkSigner' in signer and 'import com.android.apksig.ApkVerifier' in signer,
    'v1 remains disabled': 'setV1SigningEnabled(false)' in signer and 'setV1SigningEnabled(true)' not in signer,
    'v2 remains enabled': 'setV2SigningEnabled(true)' in signer,
    'v3 remains enabled': 'setV3SigningEnabled(true)' in signer,
    'v4 sidecar signing is explicitly disabled': 'setV4SigningEnabled(false)' in signer and 'setV4SigningEnabled(true)' not in signer,
    'API 26 signing floor remains explicit': 'setMinSdkVersion(Build.VERSION_CODES.O)' in signer,
    'Android port attribution exists': 'MuntashirAkon/apksig-android' in notice and 'Apache License, Version 2.0' in notice,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    print('STAGE18C_ANDROID_APKSIG_RUNTIME_PORT_FAIL')
    for item in failed:
        print(' -', item)
    sys.exit(1)
print('STAGE18C_ANDROID_APKSIG_RUNTIME_PORT_PASS checks=%d' % len(checks))
