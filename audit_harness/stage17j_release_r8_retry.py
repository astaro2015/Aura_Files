from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
proguard = (ROOT / 'app/proguard-rules.pro').read_text(encoding='utf-8')
bootstrap = (ROOT / 'tools/bootstrap_windows.ps1').read_text(encoding='utf-8-sig')
archive = (ROOT / 'app/src/main/java/com/aurafiles/app/archive/ExtendedArchiveRepository.kt').read_text(encoding='utf-8')

# R8: suppress only optional/not-on-Android references that are absent by design.
for marker in (
    '-dontwarn com.github.luben.zstd.**',
    '-dontwarn javax.management.MBeanException',
    '-dontwarn javax.management.ReflectionException',
):
    assert marker in proguard, f'missing R8 rule: {marker}'

# Aura does not advertise/use Zstandard, so zstd-jni is intentionally not a runtime dependency.
assert '.zst' not in archive.lower(), 'Aura archive UI unexpectedly exposes Zstandard; dontwarn would no longer be sufficient'
gradle = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
assert 'zstd-jni' not in gradle, 'BUILD_FIX5 should not add unused native zstd-jni payload'

# Builder must not loop deterministic R8/source/test failures.
for marker in (
    'Get-Content -LiteralPath $LogPath -Raw',
    'Missing classes detected while running R8',
    'R8: Missing class',
    'com\\.android\\.tools\\.r8\\.CompilationFailedException',
    'Gradle found a deterministic source/test/R8/dependency error',
    '$TransientNetworkFailure',
    'Gradle failed without a recognized transient network error',
    'Report-UnitTestResults',
):
    assert marker in bootstrap, f'bootstrap missing retry/R8 safeguard: {marker}'

# Regression: the old tail-only classifier is what caused repeated identical attempts.
assert 'Get-Content -LiteralPath $LogPath -Tail 260' not in bootstrap

# Explicit network signals are the only path to another build attempt.
for marker in ('UnknownHostException', 'SocketTimeoutException', 'Could not GET', 'Could not HEAD'):
    assert marker in bootstrap, f'missing transient-network classifier: {marker}'

print('STAGE17J_RELEASE_R8_RETRY_PASS')
