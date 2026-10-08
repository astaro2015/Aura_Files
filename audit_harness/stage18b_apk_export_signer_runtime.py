from pathlib import Path
import sys
root = Path(__file__).resolve().parents[1]
signer = (root/'app/src/main/java/com/aurafiles/app/tools/ApkExportSigner.kt').read_text('utf-8')
exporter = (root/'app/src/main/java/com/aurafiles/app/tools/UniversalApkExporter.kt').read_text('utf-8')
checks = {
    'no AndroidKeyStore private key': 'import android.security.keystore' not in signer and 'KeyStore.getInstance("AndroidKeyStore")' not in signer and 'KeyGenParameterSpec' not in signer,
    'sign receives Context': 'fun sign(context: Context' in signer,
    'private export key storage': 'noBackupFilesDir' in signer,
    'PKCS8 persisted private key': 'PKCS8EncodedKeySpec' in signer,
    'x509 certificate persisted': 'CertificateFactory' in signer,
    'key/cert pair validation': 'SHA256withRSA' in signer and 'initSign' in signer and 'initVerify' in signer,
    'exporter passes context': 'ApkExportSigner.sign(context, unsigned, signedTemp)' in exporter,
    'legacy v1 signing disabled': 'setV1SigningEnabled(false)' in signer and 'setV1SigningEnabled(true)' not in signer,
    'API 26 signing floor': 'setMinSdkVersion(Build.VERSION_CODES.O)' in signer and 'setMinCheckedPlatformVersion(Build.VERSION_CODES.O)' in signer,
    'do not silently regenerate partial/corrupt identity': 'неполный или повреждён' in signer,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    print('STAGE18B_APK_EXPORT_SIGNER_RUNTIME_FAIL')
    for f in failed: print(' -', f)
    sys.exit(1)
print('STAGE18B_APK_EXPORT_SIGNER_RUNTIME_PASS checks=%d' % len(checks))
