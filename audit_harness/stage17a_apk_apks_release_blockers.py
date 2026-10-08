#!/usr/bin/env python3
from pathlib import Path

bundle = Path('app/src/main/java/com/aurafiles/app/tools/AuraApksBundle.kt').read_text()
installer = Path('app/src/main/java/com/aurafiles/app/ui/SplitPackageInstallerActivity.kt').read_text()
apps = Path('app/src/main/java/com/aurafiles/app/ui/InstalledAppsActivity.kt').read_text()
inspector = Path('app/src/main/java/com/aurafiles/app/tools/ApkInspector.kt').read_text()
inspector_activity = Path('app/src/main/java/com/aurafiles/app/ui/ApkInspectorActivity.kt').read_text()
archive = Path('app/src/main/java/com/aurafiles/app/ui/ArchiveBrowserActivity.kt').read_text()
backup = Path('app/src/main/res/xml/backup_rules.xml').read_text()
extraction = Path('app/src/main/res/xml/data_extraction_rules.xml').read_text()

required = {
    'bundle bounded import': (bundle, 'MAX_CONTAINER_BYTES'),
    'bundle payload ceiling': (bundle, 'MAX_TOTAL_UNCOMPRESSED'),
    'bundle reserve': (bundle, 'CACHE_SPACE_RESERVE_BYTES'),
    'bundle cancellation': (bundle, 'currentCoroutineContext().ensureActive()'),
    'bundle extraction preflight': (bundle, 'requireCacheSpace(working, totalSize)'),
    'bundle safe commit': (bundle, 'require(temporary.renameTo(target))'),
    'installer nonce extra': (installer, 'EXTRA_CALLBACK_NONCE'),
    'installer persisted callback': (installer, 'saveInstallCallbackRecord'),
    'installer synchronous callback record': (installer, '.commit()'),
    'installer rejects spoof': (installer, 'Отклонён неподтверждённый ответ установщика'),
    'installer session cancellation': (installer, 'currentCoroutineContext().ensureActive()'),
    'apk share temp': (apps, 'UUID.randomUUID()'),
    'apk share fsync': (apps, 'output.fd.sync()'),
    'apk share reserve': (apps, 'SHARE_CACHE_RESERVE_BYTES'),
    'apk inspector lifecycle': (inspector_activity, 'lifecycleScope.launch'),
    'apk inspector max': (inspector, 'MAX_APK_BYTES'),
    'apk inspector cancellation': (inspector, 'currentCoroutineContext().ensureActive()'),
    'archive cancellation extract': (archive, 'catch (cancelled: CancellationException)'),
    'callback backup exclusion': (backup, 'aura_split_install_callbacks.xml'),
    'callback transfer exclusion': (extraction, 'aura_split_install_callbacks.xml'),
}
for label, (text, needle) in required.items():
    assert needle in text, f'{label}: {needle}'

forbidden = [
    (bundle, 'source.copyTo(target, BUFFER_SIZE)', 'unbounded APKS incoming copy'),
    (apps, 'source.copyTo(target, overwrite = true)', 'direct final-looking APK copy'),
    (inspector, 'input.copyTo(it, BUFFER_SIZE)', 'unbounded APK inspector copy'),
    (installer, 'putExtra(EXTRA_LABEL', 'trusting callback label from intent'),
    (installer, 'putExtra(EXTRA_PACKAGE', 'trusting callback package from intent'),
    (installer, 'putExtra(EXTRA_VERSION', 'trusting callback version from intent'),
]
for text, needle, label in forbidden:
    assert needle not in text, label

# Model the callback trust boundary: session id alone never authorizes a result.
def validate(stored, session, nonce):
    record = stored.get(session)
    return bool(record and nonce and record['nonce'] == nonce)

stored = {17: {'nonce': 'secret-unguessable'}}
assert not validate(stored, 17, '')
assert not validate(stored, 17, 'attacker')
assert not validate(stored, 18, 'secret-unguessable')
assert validate(stored, 17, 'secret-unguessable')

print('STAGE17A_APK_APKS_SOURCE_INVARIANTS_PASS')
print('STAGE17A_CALLBACK_SPOOF_MODEL_PASS scenarios=4')
