# Aura Files 1.1.0 CLOUD — этап 1

Дата: 2026-09-06
Статус: промежуточная cloud-ветка, не финальный релиз 1.1.0.

База:
- `Aura_Files_1.0.4_GITHUB_BASE_FIXED.zip`;
- SHA-256 исходного ZIP: `446e35368b9f8a859005d08f3ccda81c8f0ec9d1bac3ed52633fc2619afc1905`;
- app versionName/versionCode пока намеренно оставлены `1.0.4 / 104`, чтобы незавершённую cloud-ветку не выдавать за готовый релиз 1.1.0.

Выполнено:
- добавлена общая модель `CloudProfile` для Яндекс.Диска и Google Drive;
- provider сохраняется через стабильный storage key (`yandex_disk`, `google_drive`), а не только через имя enum;
- профиль отделяет несекретные данные аккаунта (`accountId`, `accountLabel`) от OAuth-секретов;
- добавлен `CloudProfileRepository` с сохранением/обновлением/удалением cloud-профилей;
- повреждённая отдельная запись cloud-профиля не ломает загрузку остальных записей;
- добавлена модель `CloudAuthTokens` с access token, optional refresh token, expiry и safety window;
- добавлен `CloudTokenStore` на AES/GCM/NoPadding с ключом в AndroidKeyStore;
- для каждого encrypted token blob используется случайный 12-byte IV и AAD, привязанный к ID cloud-профиля;
- OAuth-токены не записываются в обычный профиль и не должны попадать в логи/UI;
- обновление access token сохраняет существующий refresh token;
- `saveAuthenticated` откатывает новый token blob при ошибке сохранения метаданных и восстанавливает предыдущие токены, если профиль уже существовал;
- cloud profile/token SharedPreferences исключены из Android cloud backup и device transfer, поскольку AndroidKeyStore key device-bound;
- добавлены regression/smoke проверки нормализации профиля, стабильного provider key и срока действия access token.

Изменённые/добавленные файлы этапа:
- `app/src/main/java/com/aurafiles/app/cloud/CloudProfile.kt`
- `app/src/main/java/com/aurafiles/app/cloud/CloudAuthTokens.kt`
- `app/src/main/java/com/aurafiles/app/cloud/CloudTokenStore.kt`
- `app/src/main/java/com/aurafiles/app/cloud/CloudProfileRepository.kt`
- `app/src/test/java/com/aurafiles/app/cloud/CloudProfileTest.kt`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`

Намеренно ещё НЕ сделано на этом этапе:
- OAuth UI;
- Yandex device-code flow;
- Yandex refresh-token HTTP flow;
- Google AuthorizationClient;
- YandexDiskStorageBackend;
- GoogleDriveStorageBackend;
- регистрация cloud backends в универсальных панелях;
- cloud transfer regression tests.
