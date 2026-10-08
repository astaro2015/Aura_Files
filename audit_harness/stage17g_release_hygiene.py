from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT).decode('utf-8', errors='replace')

tracked = [p for p in git('ls-files').splitlines() if p]
assert tracked, 'no tracked source files'

# Release source must not contain private signing material/local machine state.
forbidden_names = re.compile(r'(?i)(^|/)(keystore\.properties|local\.properties)$|\.(jks|keystore|p12|pfx|pem|key)$')
bad_names = [p for p in tracked if forbidden_names.search(p)]
assert not bad_names, f'forbidden tracked secret/local files: {bad_names}'

# Scan tracked current source for common concrete credential signatures. Variable names and
# documentation are allowed; actual secret-shaped values are not.
patterns = [
    re.compile(r'AIza[0-9A-Za-z_-]{30,}'),
    re.compile(r'ya29\.[0-9A-Za-z._-]+'),
    re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    re.compile(r'(?i)client_secret["\s=:]+[A-Za-z0-9_-]{16,}'),
    re.compile(r'(?i)access_token["\s=:]+[A-Za-z0-9._-]{20,}'),
]
violations = []
for rel in tracked:
    path = ROOT / rel
    try:
        data = path.read_text(encoding='utf-8')
    except (UnicodeDecodeError, OSError):
        continue
    # Documentation can mention example field names; only concrete token-shaped matches trip.
    for pat in patterns:
        if pat.search(data):
            violations.append((rel, pat.pattern))
assert not violations, f'credential-shaped tracked literals: {violations}'

# Bootstrap must verify the source manifest and ignore unlisted extras.
bootstrap = (ROOT / 'tools/bootstrap_windows.ps1').read_text(encoding='utf-8-sig')
for marker in (
    "SOURCE_SHA256SUMS.txt is missing. Refusing to stage an unverified/mixed source tree.",
    'Get-FileHash -LiteralPath $Source -Algorithm SHA256',
    'Extra files in the source folder were ignored.',
):
    assert marker in bootstrap, f'Windows staging integrity marker missing: {marker}'

# Release identity must already be final before checksum generation.
gradle = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
assert 'versionCode = 147' in gradle and 'versionName = "1.3.17"' in gradle

print(f'STAGE17G_RELEASE_HYGIENE_PASS tracked={len(tracked)}')
