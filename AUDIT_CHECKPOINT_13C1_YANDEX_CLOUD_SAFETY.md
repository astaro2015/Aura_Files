# AUDIT CHECKPOINT 13C1 — Yandex Disk async/lost-ACK safety

Статус: завершено и зафиксировано.

## Проверено

- async server operations + polling;
- timeout/cancellation boundaries;
- direct UI mkdir/rename/move/delete при lost response after commit;
- duplicate-name prechecks;
- streaming upload/download (без whole-file RAM buffering);
- связь с `BackendTransferCore` и его `.aura-part-*` / backup policy;
- stale ViewModel operations: jobs отменяются через `viewModelScope`/`onCleared`, но блокирующий `HttpURLConnection` может завершить уже начатый socket call только по сетевому timeout.

## Найдено

1. Старый polling выглядел как `360 * 500 ms`, но каждый `operationState()` имел read timeout 60 секунд. При плохой сети реальный предел мог растянуться на часы.
2. Прямые команды панели `mkdir/rename/move/delete` обходят `BackendTransferCore`. Если Яндекс уже применил mutation, но ACK/HTTP response потерялся, backend раньше возвращал ошибку без reconciliation.
3. Для async Yandex operation текущие `source exists / target absent` сразу после сбоя не доказывают, что операция не будет завершена сервером позже. Автоматический destructive retry поэтому запрещён.

## Исправлено

- `operationState()` получил отдельный connect/read timeout 10 секунд.
- polling ограничен monotonic wall-clock deadline 180 секунд; cancellation проверяется между сетевыми вызовами.
- после сомнительного rename/move backend повторно проверяет source/target:
  - source исчез + target существует -> commit доказан, операция считается успешной;
  - иначе состояние объявляется неопределённым и mutation не повторяется автоматически.
- delete после lost ACK считается успешным только если повторный `stat` подтверждает отсутствие объекта; иначе показывается ambiguity и повторное удаление не выполняется.
- mkdir после lost ACK принимает уже появившуюся папку как committed и не создаёт вторую.
- `CancellationException` пробрасывается отдельно и не маскируется reconciliation.

## Передача больших файлов

`UrlConnectionYandexBinaryTransfer` пишет напрямую fixed-length или chunked stream (`1 MiB` chunk) и читает через `InputStream`; загрузки целого файла в RAM не найдено.

## Проверка

- `git diff --check`: PASS.
- Полный Gradle Android build в контейнере недоступен: wrapper пытается скачать Gradle 9.5.0 с `services.gradle.org`, DNS недоступен. Это не выдаётся за успешную сборку.
- Actual `YandexDiskStorageBackend.kt` собран `kotlinc` в отдельном stub/fault-injection harness (в harness только timeout уменьшен для быстрого исполнения).
- Сценарии: committed rename + lost ACK; committed delete + lost ACK; ambiguous lost ACK; polling timeout after visible commit; polling timeout before visible commit; mkdir committed + lost ACK.
- Результат: `YANDEX_13C_HARNESS_PASS`.

Следующая точка: 13C2 — Google Drive stale parent/file ID, trashed files, duplicate-name/shortcut semantics, direct mutation reconciliation, resumable transfer cancellation/cleanup.
