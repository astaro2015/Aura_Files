from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
def read(name): return (ROOT / name).read_text(encoding='utf-8')

gradle = read('app/build.gradle.kts')
assert re.search(r'\bversionCode\s*=\s*147\b', gradle), 'versionCode must be 147'
assert 'versionName = "1.3.17"' in gradle, 'versionName must be 1.3.17'
required = {
    'README.md': ['Aura Files 1.3.17', 'AURA_1.3.15_MEDIASTORE_APK_SHARE_CHECKPOINT.md', 'MediaStore'],
    'RELEASE_NOTES_1.3.17_RU.md': ['Aura Files 1.3.17', '.AuraSafe', '32 768'],
    'RELEASE_NOTES_1.3.16_RU.md': ['Aura Files 1.3.16', '.AuraSafe', '.AuraVault'],
    '00_CODEX_TASK.md': ['Aura Files 1.3.15', 'versionCode = 145', 'BUILD_ON_CLEAN_WINDOWS.bat', 'MediaStore'],
    'CODEX_PACKAGE_INFO.txt': ['Aura Files 1.3.15', 'versionCode 145', 'AURA_1.3.15_MEDIASTORE_APK_SHARE_CHECKPOINT.md'],
    'WINDOWS_READY_PACKAGE_INFO.txt': ['Aura Files 1.3.15', 'versionCode 145', 'MediaStore Downloads'],
    'AURA_1.3.15_MEDIASTORE_APK_SHARE_CHECKPOINT.md': ['Aura Files 1.3.15', 'versionCode 145', 'MediaStore.Downloads', 'newRawUri'],
    'RELEASE_NOTES_1.3.15_RU.md': ['Aura Files 1.3.15', 'MediaStore', 'Telegram/MAX'],
}
for name, markers in required.items():
    data = read(name)
    for marker in markers:
        assert marker in data, f'{name} missing {marker!r}'
print('STAGE17F_RELEASE_IDENTITY_PASS version=1.3.17 code=147')
