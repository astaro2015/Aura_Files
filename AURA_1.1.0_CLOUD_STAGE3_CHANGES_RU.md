# Aura Files 1.1.0 CLOUD — этап 3: YandexDiskStorageBackend

Дата: 2026-09-06
Статус: промежуточная cloud-ветка, не финальный релиз 1.1.0.

## База этапа

- входной пакет: `Aura_Files_1.1.0_CLOUD_STAGE2_source.zip`;
- SHA-256 входного пакета: `0cae67c6f15bfc8efed9adde0fffd5e2d4e2baebe0df194f58de218da49ef935`;
- OAuth/token foundation этапа 2 сохранён;
- app metadata намеренно остаётся `versionName 1.0.4 / versionCode 104` до готовности cloud-ветки.

## Что сделано

### 1. Полноценный `YandexDiskStorageBackend`

Добавлен backend официального Yandex Disk REST API, реализующий общий контракт Aura Files:

- `list`;
- `stat`;
- `openRead` / download;
- `openWrite` / upload;
- `mkdir`;
- `rename`;
- `move`;
- `delete`;
- `ping`.

Backend использует обычные пути Aura вида `/Folder/file.txt`; `disk:` из provider response наружу не протекает.

### 2. Общий transfer core, без отдельного cloud-копировщика

Яндекс подключён к существующей схеме `StorageBackend`/`BackendTransferCore`.

Сохраняется общий безопасный алгоритм файловой записи:

1. запись в `.aura-part-*`;
2. завершение upload;
3. rename временного объекта в конечное имя;
4. при REPLACE — существующий объект сначала уходит в `.aura-backup-*`, затем новая версия ставится на место;
5. backup удаляется после успешного commit.

Поэтому конфликтная политика, KEEP_BOTH, REPLACE, copy+delete move и cleanup работают одинаково для Local/SMB/FTP/SFTP/Yandex.

### 3. Size-aware upload без поломки старых backend'ов

В `StorageBackend` добавлен обратно совместимый overload:

`openWrite(path, replace, expectedSize)`

Старые backend'ы используют default implementation и ничего менять не обязаны.
`BackendTransferCore` передаёт известный положительный размер исходного файла.

Для Яндекса это позволяет использовать fixed-length HTTP streaming (`Content-Length` через `setFixedLengthStreamingMode`) вместо обязательного chunked upload, когда размер известен.
Если размер неизвестен, остаётся chunked streaming fallback.

### 4. REST resource API

`YandexDiskApiClient` расширен:

- metadata ресурса;
- paginated list (`limit=200`, `offset`);
- upload link;
- download link;
- mkdir;
- move/rename;
- delete в Корзину (`permanently=false`);
- polling асинхронной operation link.

Поддерживаются ответы 201/202/204 в тех операциях, где API может завершить действие синхронно или асинхронно.

### 5. Streaming binary layer

Добавлен `YandexBinaryTransfer` на `HttpURLConnection`:

- download возвращает поток и держит соединение до закрытия read handle;
- upload отдаёт `OutputStream` прямо transfer core, без обязательного временного локального файла;
- commit проверяет HTTP status;
- abort закрывает соединение;
- ссылки принимаются только `https://`;
- OAuth token не передаётся в pre-signed upload/download URL.

### 6. Ровно один refresh/retry после 401

Добавлены `YandexAccessTokenProvider` и `YandexAuthTokenProvider`.

Каждый REST вызов backend выполняется так:

1. получить текущий access token;
2. выполнить запрос;
3. только если официальный Disk API вернул HTTP 401 — принудительно refresh token;
4. повторить запрос ровно один раз.

403, 404, 409, 429, network и 5xx не маскируются повторным login/refresh.

### 7. Filesystem semantics

- корень нельзя удалить/переименовать/переместить;
- `stat` отсутствующего объекта возвращает `null`;
- `mkdir` существующей папки идемпотентен;
- rename/move без replace не затирают существующий объект;
- `delete(..., recursive=false)` не удаляет непустую папку;
- обычное delete отправляет объект в Корзину Яндекс.Диска, а не делает permanent delete.

### 8. Regression test

Добавлен `YandexDiskStorageBackendTest` с fake API и fake binary transport без реального аккаунта.

Проверено:

- HTTP 401 вызывает ровно один forced refresh и один retry;
- `mkdir/rename/move/delete`;
- non-recursive delete непустой папки отклоняется;
- общий `BackendTransferCore`: Local → Yandex → Local;
- размер исходника передаётся в Yandex binary writer;
- `.aura-part-*` после успешного commit/rename не остаётся.

## Изменённые/добавленные исходники

Изменены:
- `app/src/main/java/com/aurafiles/app/backend/StorageBackend.kt`
- `app/src/main/java/com/aurafiles/app/transfer/BackendTransferCore.kt`
- `app/src/main/java/com/aurafiles/app/cloud/yandex/YandexDiskApiClient.kt`
- `app/src/main/java/com/aurafiles/app/cloud/yandex/YandexModels.kt`

Добавлены:
- `app/src/main/java/com/aurafiles/app/backend/YandexDiskStorageBackend.kt`
- `app/src/main/java/com/aurafiles/app/cloud/yandex/YandexAccessTokenProvider.kt`
- `app/src/main/java/com/aurafiles/app/cloud/yandex/YandexBinaryTransfer.kt`
- `app/src/test/java/com/aurafiles/app/backend/YandexDiskStorageBackendTest.kt`

## Намеренно ещё НЕ сделано

- регистрация Яндекс.Диска в пользовательских панелях;
- Android UI «Войти через Яндекс» и device-code polling;
- UI списка cloud-аккаунтов / удаления аккаунта;
- отдельное представление Корзины Яндекс.Диска;
- Photoslice Android read-only adapter;
- Google AuthorizationClient / GoogleDriveStorageBackend;
- финальное повышение версии до 1.1.0.

## Следующий этап

Подключить сохранённые Yandex cloud-профили к пользовательскому UI:

1. экран/диалог добавления Яндекс.Диска;
2. device-code UI + polling/cancel;
3. создание/удаление cloud-профиля;
4. регистрация `YandexDiskStorageBackend` в `StorageBackendRegistry`;
5. показ Яндекс.Диска в универсальных панелях;
6. UI/regression tests без Photoslice.
