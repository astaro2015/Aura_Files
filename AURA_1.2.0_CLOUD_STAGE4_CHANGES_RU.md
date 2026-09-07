> **ИСТОРИЧЕСКИЙ ДОКУМЕНТ.** Cloud OAuth из этой версии заменён исправлением 1.2.2. Для Яндекса актуальный flow требует Client ID + Client Secret; для Google актуальна диагностика Android OAuth package/SHA-1 и обязательная проверка Drive API.

# Aura Files 1.2.0 CLOUD — этап 4: пользовательское подключение Яндекс.Диска

Дата: 2026-09-06

## Версия

По решению владельца проекта cloud-ветка переведена на новую minor-версию:

- `versionName = 1.2.0`;
- `versionCode = 120`;
- Yandex HTTP User-Agent: `AuraFiles/1.2.0`.

Облака считаются новой пользовательской функцией, поэтому 1.2.0 логичнее, чем patch-номер 1.1.4.

## Что сделано

### 1. Yandex OAuth configuration

Добавлен `YandexOAuthSettingsStore`:

- хранит публичный Yandex OAuth Client ID;
- генерирует один стабильный UUID `device_id` на установку Android;
- формирует имя устройства из manufacturer/model;
- поддерживает build-time Client ID через Gradle property/environment `AURA_YANDEX_CLIENT_ID`;
- если установка уже использовала вручную сохранённый Client ID, он имеет приоритет над новым build-time значением, чтобы существующие refresh tokens продолжали обновляться тем же OAuth-приложением;
- `aura_yandex_oauth.xml` исключён из Android Backup/device transfer, потому что `device_id` должен оставаться локальным для конкретной установки.

Client Secret в UI не запрашивается и в приложении не хранится. Device-code flow Яндекса допускает передачу `client_id` без client secret; права Диска задаются при регистрации OAuth-приложения.

### 2. Android device-code UI

В `BackendWorkspaceActivity` добавлен полноценный flow «Добавить Яндекс.Диск»:

1. если Client ID уже настроен — запрос device code начинается сразу;
2. если Client ID отсутствует — Aura предлагает указать его один раз;
3. после получения кода Aura автоматически копирует `user_code` в буфер обмена;
4. автоматически открывает `verification_url` в браузере;
5. показывает код и текущий статус ожидания;
6. polling соблюдает `interval` из ответа Яндекса;
7. `slow_down` увеличивает интервал;
8. cancel закрывает flow и отменяет coroutine;
9. expiry/deny/invalid_client/network возвращаются как понятное сообщение;
10. после подтверждения токен проверяется официальным Disk API и сохраняется Stage 1 secure token store.

Пользователь OAuth-токен вручную не вводит.

### 3. Повторный вход без дублей

`YandexAuthService.finishAuthorization()` теперь, если `profileId` не задан, ищет уже сохранённый Yandex-профиль с тем же `user.uid`.

Повторная авторизация того же аккаунта:

- сохраняет прежний profile ID;
- обновляет account label;
- заменяет OAuth tokens;
- не создаёт второй одинаковый Яндекс.Диск.

### 4. Автоматическая регистрация backend

`BackendFactory` получил cloud factory для Yandex.

`BackendWorkspaceViewModel.rebuildBackends()` теперь:

- читает `CloudProfileRepository`;
- выбирает сохранённые Yandex-профили с OAuth tokens;
- создаёт `YandexAuthTokenProvider`;
- создаёт `YandexDiskStorageBackend`;
- регистрирует его в общем `StorageBackendRegistry` рядом с Local / SMB / FTP / SFTP.

После успешной авторизации новый Яндекс.Диск автоматически открывается в одной панели, а локальное хранилище — в другой.

### 5. UI раздела «Сеть»

Добавлен `CloudProfilesCard`:

- отдельный блок «Облака»;
- кнопка «Добавить Яндекс.Диск»;
- сохранённые аккаунты с account label;
- открытие аккаунта в универсальных панелях;
- удаление профиля с OAuth tokens;
- удаление подключения не удаляет файлы из Яндекс.Диска.

Главный `FileManagerViewModel` обновляет список cloud-профилей при возвращении приложения на передний план, поэтому новый аккаунт появляется в разделе «Сеть» сразу после возврата из универсальных панелей.

### 6. Универсальные панели

В заголовке теперь заявлены:

`Local · SMB · FTP · SFTP · Яндекс.Диск`.

Кнопка `+` предлагает:

- Яндекс.Диск;
- SFTP.

Для запуска из основного раздела «Сеть» добавлен `EXTRA_ADD_YANDEX`, а для открытия существующего аккаунта используется `EXTRA_INITIAL_BACKEND_ID = yandex:<profileId>`.

## Безопасность

- access/refresh tokens остаются только в Stage 1 encrypted `CloudTokenStore`;
- Client Secret не требуется UI и не сохраняется;
- Client ID не является пользовательским секретом;
- `device_id` не переносится Android Backup на другое устройство;
- новые UI/wiring файлы не логируют tokens и не вызывают `printStackTrace`;
- повторный OAuth того же uid обновляет существующий профиль вместо создания копии.

## Проверки этапа

- Kotlin/KTS PSI parse: 107 файлов, 0 syntax errors;
- Android XML parse: 14 файлов, 0 errors;
- Yandex auth duplicate-account smoke: OK;
- security/logging scan новых cloud/UI файлов: token logging не найден;
- metadata scan: `versionName 1.2.0 / versionCode 120`, User-Agent `AuraFiles/1.2.0`;
- diff относительно Stage 3 проверен: посторонние подсистемы не изменялись.

Полный Android Gradle build в контейнере не стартовал: wrapper пытается получить отсутствующий локально `gradle-9.5.0-bin.zip`, а `services.gradle.org` недоступен (`UnknownHostException`). Это ограничение среды, не полученная ошибка компиляции исходников.

Контрольная проверка на Windows/CI:

```text
gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-daemon
```

## Намеренно ещё не сделано

- отдельное представление Корзины Яндекс.Диска;
- Photoslice Android read-only adapter;
- Google Identity Services / AuthorizationClient;
- GoogleDriveStorageBackend;
- Android instrumented end-to-end тест с реальным Yandex OAuth аккаунтом;
- готовый release APK с реально зарегистрированным Yandex OAuth Client ID (его следует передать через `AURA_YANDEX_CLIENT_ID` при сборке).
