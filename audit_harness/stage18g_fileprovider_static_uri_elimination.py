#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
src = root / 'app' / 'src' / 'main' / 'java'
provider = src / 'com' / 'aurafiles' / 'app' / 'AuraFileProvider.kt'

errors = []
if not provider.exists():
    errors.append('AuraFileProvider.kt missing')
else:
    text = provider.read_text(encoding='utf-8')
    required = [
        'fun uriForFile(',
        'AuraFileProviderPathPolicy',
        'Uri.Builder()',
        '"${context.packageName}.fileprovider"',
    ]
    for token in required:
        if token not in text:
            errors.append(f'AuraFileProvider missing token: {token}')

for path in src.rglob('*.kt'):
    text = path.read_text(encoding='utf-8')
    if 'FileProvider.getUriForFile(' in text:
        errors.append(f'static FileProvider.getUriForFile remains: {path.relative_to(root)}')

policy = src / 'com' / 'aurafiles' / 'app' / 'AuraFileProviderPathPolicy.kt'
if not policy.exists():
    errors.append('AuraFileProviderPathPolicy.kt missing')

if errors:
    print('FAIL')
    for err in errors:
        print(' -', err)
    sys.exit(1)
print('PASS: no static FileProvider URI generation; Aura-owned URI path policy present')
