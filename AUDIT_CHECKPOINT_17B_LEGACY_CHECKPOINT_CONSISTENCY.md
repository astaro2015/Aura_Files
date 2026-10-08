# Aura Files — AUDIT CHECKPOINT 17B

Дата: 2026-09-14\\
Этап: release consistency / legacy checkpoint reconciliation

## Что проверено

После обнаруженных ранее расхождений между старыми checkpoint-файлами и фактическим source tree повторно сверены критические утверждения этапов 01–10, которые могли влиять на безопасность данных или release readiness.

Подтверждено по текущему коду:

- Vault сверяет фактически прочитанный plaintext-byte count с заявленным размером source и использует overflow-safe счётчик;
- Vault UI использует lifecycle-owned coroutine scope;
- BackendTransferCore отличает `CancellationException` от ordinary failure/fallback;
- NetworkProfile сериализует TLS-поле ровно один раз;
- `.AuraVault` и `.AuraTrash` скрыты из normal browser surfaces;
- усечённые category collections явно сообщают `Показано N из M`;
- APK/APKS security/cancellation/space safeguards, которые были потеряны из старых 05/05B/11, уже восстановлены этапом 17A.

## Найденное расхождение

Checkpoint 09/10 заявлял корректное сообщение при частично завершённом пакетном переносе в Vault, но текущий source tree снова показывал только ошибку последнего элемента. Уже подтверждённо перемещённые элементы при этом были физически учтены, но пользователь не видел масштаба частичного commit.

Исправлено в `FileManagerViewModel.toggleFavorites()`:

- при ошибке после одного или нескольких подтверждённых переносов UI сообщает `подтверждённо перемещено X из Y`;
- причина исходной ошибки сохраняется в том же сообщении;
- committed URI продолжают reconciliate UI/index state до показа результата.

## Исполняемая проверка

Добавлен:

`audit_harness/stage17b_legacy_checkpoint_consistency.py`

Проверяет перечисленные source-инварианты и восстановленное partial-Vault-progress поведение.

Результат:

```text
STAGE17B_LEGACY_CHECKPOINT_CONSISTENCY_PASS
```

Дополнительно:

```text
git diff --check: PASS
```

## Ограничение

Это source-consistency pass, а не полноценная Android/Gradle сборка. Полный clean build остаётся отдельной частью этапа 17.
