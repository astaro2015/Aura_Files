# Aura Files — audit checkpoint 17I: pinned release signing identity

Дата: 2026-09-14
Версия: 1.3.5 / versionCode 135

## Цель

Не допустить смены Android application identity при переходе с debug APK на release APK. Это критично для существующего `.AuraVault`, обновления APK поверх установленной Aura и Google OAuth identity.

## Зафиксированная identity

- package: `com.aurafiles.app`
- keystore на Windows-машине пользователя: `%USERPROFILE%\.android\debug.keystore`
- alias: `androiddebugkey`
- pinned SHA-1: `90:2F:F6:17:0C:61:EB:8C:DA:08:EE:60:8E:87:12:76:35:51:4B:B5`

Приватный keystore в source package НЕ включается. Его необходимо отдельно резервировать.

## Изменения builder

Добавлен `BUILD_RELEASE_WINDOWS.bat`. Release-flow:

1. Отказывается автоматически создавать новый signing key, если существующий ключ потерян.
2. Через `keytool` проверяет, что keystore имеет ровно pinned SHA-1.
3. Передаёт этот же keystore в существующий `externalRelease` signingConfig через `AURA_KEYSTORE_*`.
4. Выполняет `clean testDebugUnitTest assembleRelease`.
5. Суммирует XML unit-test results в `BUILD_OUTPUT/UNIT_TEST_SUMMARY.txt`.
6. Проверяет готовый APK через `apksigner verify --print-certs`.
7. Повторно сверяет signer SHA-1 уже у `BUILD_OUTPUT/Aura_Files_1.3.5-release.apk`.
8. При любом расхождении сертификата build завершается ошибкой и release APK не считается валидным.

Старый `BUILD_ON_CLEAN_WINDOWS.bat` сохранён как debug-flow.

## Что намеренно не делалось

- signing key не копируется в source ZIP;
- новый release keystore не генерируется;
- production Kotlin/Java функциональность Aura не менялась;
- Google OAuth SHA-1 не меняется: release использует тот же сертификат.

## Обязательная внешняя проверка

На Windows запустить `BUILD_RELEASE_WINDOWS.bat`. Успешный gate должен одновременно показать:

- unit tests без failures/errors;
- `assembleRelease` успешен;
- `apksigner` подтвердил pinned SHA-1;
- создан `Aura_Files_1.3.5-release.apk`.
