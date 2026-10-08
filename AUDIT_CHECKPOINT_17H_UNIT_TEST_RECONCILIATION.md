# Aura Files — audit checkpoint 17H: unit-test reconciliation after Windows clean build

Дата: 2026-09-14
Версия: 1.3.5 / versionCode 135

## Фактический результат Windows build

`assembleDebug` проходит до APK. После этого Windows-проверка обнаружила 4 падения из 67 unit-тестов.
Разбор показал, что все четыре падения вызваны устаревшими test doubles / ожиданиями после уже сохранённого safety hardening, а не необходимостью отката production-поведения.

## GoogleDriveStorageBackendTest — 2 теста

После 13C production backend валидирует корень Google Drive через `file("root")`, проверяет, что он существует, не `trashed` и является папкой. Старый `FakeApi.file()` возвращал только элементы пользовательской map и вообще не моделировал Drive root. Поэтому оба теста падали до проверяемого ими поведения.

Исправление только в test double:
- `FakeApi.file("root")` возвращает синтетический Google Drive folder с id `root`;
- production `GoogleDriveStorageBackend` не ослабляется.

## CrossBackendTransferTest — 2 теста

После remote/local mutation hardening production cleanup уже зафиксированной REPLACE-транзакции намеренно НЕ повторяет destructive `delete(backup)` после ошибки. Потерянный ACK означает, что сервер/провайдер мог удалить backup, а путь уже мог быть занят другим объектом. Автоматический повтор delete был бы небезопасен.

Старые тесты ожидали 2 или 3 delete-попытки и удаление backup после transient error. Эти ожидания противоречили текущей safety policy.

Исправлены только тесты:
- ожидается ровно 1 cleanup delete attempt;
- новая версия файла остаётся финальной;
- `.aura-backup-*` сохраняется при подтверждённом наличии после ошибки;
- возвращается user-visible warning про оставшуюся служебную копию.

## Production code

В этом checkpoint production Kotlin/Java код приложения не изменён.

## Gate

Полный результат `67/67` должен быть подтверждён повторным `testDebugUnitTest` на Windows. Этот checkpoint не подменяет реальный Gradle test run статической проверкой.
