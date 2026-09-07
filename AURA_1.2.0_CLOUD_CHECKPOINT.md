# Aura Files 1.2.0 CLOUD — checkpoint

Последнее обновление: 2026-09-06

## База и версия
- Исходная стабильная база cloud-ветки: `Aura_Files_1.0.4_GITHUB_BASE_FIXED.zip`.
- Cloud-ветка восстановлена заново от 1.0.4 и развивалась по сохранённым этапам 1–4.
- После Stage 4 выполнен отдельный аудит и исправление фактических расхождений между отчётами и кодом.
- Текущая версия приложения: `versionName 1.2.0 / versionCode 120`.
- Текущий User-Agent cloud transport: `AuraFiles/1.2.0`.

## Cloud foundation — ГОТОВО
- `CloudProfile / CloudProvider`;
- `CloudProfileRepository`;
- AES-GCM + Android Keystore для секретов Яндекса;
- cloud metadata/token backup exclusions;
- transactional-ish save/delete с rollback токенов при ошибке metadata persistence;
- проверка `hasStoredTokens()` означает наличие реально расшифровываемого blob, а не только ключа Preferences.

## Яндекс.Диск — ГОТОВО
- device-code OAuth;
- polling `authorization_pending / slow_down / expiry / decline`;
- refresh-token + ротация refresh-token;
- Client ID закрепляется за конкретным авторизованным профилем;
- проверка аккаунта через официальный Disk API;
- `YandexDiskStorageBackend`;
- list/stat/read/write/mkdir/rename/move/delete/ping;
- normal delete отправляет объект в корзину провайдера, но отдельного экрана облачной корзины Aura нет;
- fixed-length upload при известном размере;
- один forced refresh + один retry после HTTP 401;
- coroutine polling асинхронных операций без `Thread.sleep`;
- регистрация backend в универсальных панелях и разделе «Сеть»;
- повторная авторизация того же uid обновляет существующий профиль вместо дубля.

## Google Drive — ГОТОВО
- Google Identity Services `AuthorizationClient`;
- нативный `PendingIntent` flow выбора аккаунта/выдачи разрешения;
- Aura не хранит Google refresh-token и не реализует browser OAuth;
- текущий access token выдаёт Google Play services;
- `clearToken()` после 401 и один повтор авторизации/REST-запроса;
- `GoogleDriveStorageBackend`;
- list/stat/read/write/mkdir/rename/move/delete/ping;
- Drive API `about/files/list/get/create/update`;
- resumable upload;
- при известном размере — fixed `Content-Length`;
- при неизвестном размере — app-private spool во временный файл с последующей fixed-length отправкой;
- Google Workspace export: Docs→DOCX, Sheets→XLSX, Slides→PPTX, Drawings→PDF;
- реальный `fileId` хранится в backend path существующего объекта;
- неоднозначные операции по имени при внешних дублях блокируются вместо выбора случайного объекта;
- compare/sync останавливается при одинаковых относительных именах вместо молчаливой потери одного объекта;
- аккаунт доступен в разделе «Сеть» и универсальных панелях;
- удаление профиля пытается отозвать Drive scope у Google, затем удаляет локальный metadata profile.

## Намеренно НЕ делаем
- Photoslice;
- отдельное представление корзины Яндекс.Диска;
- отдельное представление корзины Google Drive;
- хранение Google refresh-token в Aura;
- permanent-delete как обычное удаление облачного объекта.

## Аудит после Stage 4
Исправлены реальные ошибки предыдущего промежуточного состояния:
- Yandex `expectedSize` ранее не использовался и upload фактически оставался chunked;
- Yandex OAuth Client ID был глобальным, хотя refresh-token привязан к OAuth-приложению;
- `hasStoredTokens()` проверял наличие строки, а не возможность расшифровки;
- Yandex operation polling блокировал поток через `Thread.sleep`;
- промежуточный Google-код не был подключён в enum/factory;
- промежуточная проверка GIS `grantedScopes` не соответствовала актуальной сигнатуре `List<String>`;
- устранён `runBlocking` из Google authorization/token path;
- удаление Google профиля из главного экрана теперь тоже делает best-effort revoke;
- неизвестный размер Google upload больше не отправляется недокументированным chunked PUT;
- Windows `SOURCE_SHA256SUMS.txt` в предыдущем пакете мог устареть после позднего изменения документа; теперь manifest формируется последним.

## Проверки текущего дерева
- Kotlin/KTS PSI parser: 117 файлов, 0 syntax errors;
- Android XML parser: см. `PACKAGE_VERIFICATION_1.2.0_CLOUD_STAGE5_RU.txt`;
- Google core/GIS compile-smoke: OK;
- Local-like → Google transfer regression: OK;
- Google 401 → один invalidate/retry: OK;
- Google duplicate-name guard: OK;
- Yandex core compile-smoke: OK;
- Yandex fixed-size upload regression: OK;
- исходная 1.0.4 подтверждённо собиралась на Windows с Gradle 9.5.0 до `assembleDebug`;
- полный Android Gradle build именно изменённой 1.2.0 в контейнере не выполнялся из-за отсутствия Android SDK/Gradle distribution в среде.

## Следующий этап
Не добавлять новые cloud-функции до реального Windows/Android прогона этого полного пакета. Если сборка или реальный OAuth выявят ошибку — исправлять от этого ZIP целиком и после правок снова пересчитывать `SOURCE_SHA256SUMS.txt` последним шагом.
