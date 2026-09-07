# Aura Files 1.1.0 CLOUD — этап 2: Яндекс OAuth/API foundation

Дата: 2026-09-06
Статус: промежуточная cloud-ветка, не финальный релиз 1.1.0.

## База этапа

- входной пакет: `Aura_Files_1.1.0_CLOUD_STAGE1_source.zip`;
- Stage 1 уже содержит `CloudProfileRepository` и защищённый `CloudTokenStore`;
- app metadata намеренно остаётся `versionName 1.0.4 / versionCode 104`, пока cloud-ветка не готова целиком.

## Что сделано

### 1. Yandex device-code OAuth core

Добавлен официальный device-code сценарий Яндекс OAuth:

- `POST https://oauth.yandex.ru/device/code`;
- чтение `device_code`, `user_code`, `verification_url`, `interval`, `expires_in`;
- `POST https://oauth.yandex.ru/token` с `grant_type=device_code`;
- состояния polling: `Pending`, `SlowDown`, `Granted`;
- истечение device code контролируется локально по `expires_in`;
- UI не зашит в OAuth-клиент: задержку, отмену и отображение кода будет контролировать Android UI/coroutine layer.

Device-code core не смешан с Photoslice.

### 2. Refresh-token логика

Добавлен обмен refresh token через `grant_type=refresh_token`.

Важно:
- при refresh сохраняется не только новый access token, но и новый refresh token;
- если Яндекс не вернул новый refresh token, сохраняется предыдущий;
- refresh критическая секция сериализована в `YandexAuthService.accessToken()`, чтобы параллельные операции не пытались одновременно обновиться одним и тем же refresh token;
- refresh/access token остаются в `CloudTokenStore` из Stage 1 и не попадают в `CloudProfile`.

### 3. Проверка токена через официальный Disk REST API

Добавлен минимальный `YandexDiskApiClient` и вызов:

`GET https://cloud-api.yandex.net/v1/disk/`

Успешная OAuth-выдача сама по себе не завершает подключение. Перед сохранением нового аккаунта Aura Files проверяет токен реальным запросом информации о Диске.

Из ответа читаются:
- total/used/trash space;
- max file size;
- paid flag;
- revision;
- `user.uid`, `user.login`, `user.display_name` при наличии.

`user.uid` используется как стабильный `accountId`, а login/display name — как подпись аккаунта, если API их вернул.

### 4. Нормализованные ошибки

Добавлены категории:
- NETWORK;
- AUTH_PENDING;
- AUTH_DENIED;
- AUTH_EXPIRED;
- INVALID_CLIENT;
- UNAUTHORIZED;
- FORBIDDEN;
- NOT_FOUND;
- CONFLICT;
- RATE_LIMITED;
- SERVER;
- BAD_RESPONSE;
- OTHER.

Покрыты, в частности:
- `authorization_pending`;
- `slow_down`;
- `access_denied` / `authorization_declined`;
- `expired_token` / `invalid_grant`;
- `invalid_client` / `unauthorized_client`;
- HTTP 401 / 403 / 404 / 409 / 429 / 5xx.

Пользовательские сообщения отделены от provider code/HTTP status, чтобы UI не показывал сырые технические ошибки без пояснения.

### 5. HTTP transport

Добавлен небольшой HTTPS transport на `HttpURLConnection` без новой сетевой зависимости:
- connect/read timeout;
- form-urlencoded POST;
- JSON body передаётся отдельному parser layer;
- Authorization header и тело запроса не выводятся в `toString()`;
- response body также не выводится в `toString()`, поскольку OAuth-ответ содержит токены.

### 6. Защита от случайной утечки секретов в логах

Дополнительно усилен Stage 1:
- `CloudAuthTokens.toString()` редактирует access/refresh token;
- `YandexTokenResponse.toString()` редактирует access/refresh token;
- `YandexOAuthConfig.toString()` редактирует Client Secret;
- `YandexDeviceCode.toString()` редактирует `device_code`;
- `YandexHttpRequest.toString()` редактирует Authorization и не печатает body;
- `YandexHttpResponse.toString()` не печатает body.

Это защищает от случайного `Log.d(..., object.toString())` в будущих UI/backend слоях.

## Добавленные/изменённые исходники

Изменён:
- `app/src/main/java/com/aurafiles/app/cloud/CloudAuthTokens.kt`

Добавлены:
- `app/src/main/java/com/aurafiles/app/cloud/yandex/YandexModels.kt`
- `app/src/main/java/com/aurafiles/app/cloud/yandex/YandexApiException.kt`
- `app/src/main/java/com/aurafiles/app/cloud/yandex/YandexHttpTransport.kt`
- `app/src/main/java/com/aurafiles/app/cloud/yandex/YandexJson.kt`
- `app/src/main/java/com/aurafiles/app/cloud/yandex/YandexOAuthClient.kt`
- `app/src/main/java/com/aurafiles/app/cloud/yandex/YandexDiskApiClient.kt`
- `app/src/main/java/com/aurafiles/app/cloud/yandex/YandexAuthService.kt`
- `app/src/test/java/com/aurafiles/app/cloud/yandex/YandexAuthRulesTest.kt`

## Намеренно ещё НЕ сделано

- экран «Войти через Яндекс» и UI polling;
- хранение/выбор OAuth app configuration в UI;
- полноценный `YandexDiskStorageBackend`;
- list/stat/upload/download/mkdir/move/rename/delete через backend;
- Корзина как storage view;
- регистрация Яндекс.Диска в универсальных панелях;
- Photoslice Android adapter;
- Google Drive AuthorizationClient/backend;
- финальное повышение версии до 1.1.0.

## Следующий этап

Сделать `YandexDiskStorageBackend` поверх официального REST API и общего `StorageBackend`/transfer core, начиная с list/stat/read/write/mkdir/rename/move/delete. Photoslice по-прежнему держать отдельным read-only источником.
