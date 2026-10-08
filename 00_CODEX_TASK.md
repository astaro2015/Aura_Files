# Aura Files 1.3.15 — сведения для продолжения

Это полное рабочее дерево Aura Files 1.3.15 поверх 1.3.14.

- `versionName = "1.3.15"`
- `versionCode = 145`
- Сборка на чистой Windows: `BUILD_ON_CLEAN_WINDOWS.bat` / release: `BUILD_RELEASE_WINDOWS.bat`.
- Канонический checkpoint: `AURA_1.3.15_MEDIASTORE_APK_SHARE_CHECKPOINT.md`.
- Release notes: `RELEASE_NOTES_1.3.15_RU.md`.
- Universal APK merge/sign/verify остаётся из 1.3.8.
- На Android 10+ APK share публикует системный MediaStore Downloads URI на `VOLUME_EXTERNAL_PRIMARY` и открывает Android Sharesheet с `application/octet-stream`.
- `ClipData.newRawUri` не даёт provider MIME повторно изменить тип вложения.
- Native `AuraFileProvider` остаётся fallback для Android 8/9 и OEM MediaStore failure; любой route проходит readability/MIME preflight.
