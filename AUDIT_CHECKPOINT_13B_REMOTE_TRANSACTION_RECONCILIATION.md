# AUDIT CHECKPOINT 13B — сетевые rename/delete и транзакционная сверка

Статус: завершено.

## Что проверено

- `BackendTransferCore` при MOVE/DELETE поверх сетевых backend'ов.
- Быстрый MOVE внутри одного backend.
- MOVE между backend'ами: сначала полная копия, затем удаление источника.
- REPLACE через безопасную схему `old -> .aura-backup-*`, `.aura-part-* -> final`, cleanup backup.
- Сценарий потери ответа сервера после фактически выполненного `rename/delete`.
- Сценарий, когда сервер точно отверг операцию.
- Сценарий, когда повторный `stat` сам недоступен и состояние нельзя доказать.

## Найденный класс дефектов

Удалённый сервер способен выполнить изменение, но соединение/ответ оборвётся до подтверждения клиенту. Нельзя трактовать такую ошибку как гарантированное «операция не произошла»: это может привести к повторному copy+delete, удалению страховочной копии или неверному rollback.

## Исправления

1. После сомнительного `MOVE/rename/delete` Aura повторно проверяет source/target.
2. Fast MOVE:
   - source отсутствует + target существует -> считаем committed;
   - source существует + target отсутствует -> безопасно разрешаем fallback copy+delete;
   - всё остальное -> ambiguous, разрушительный fallback запрещён.
3. После успешной cross-backend копии ошибка удаления источника больше не делает копию «неуспешной»:
   - если source исчез -> операция завершена с warning;
   - если source остался -> сохраняются обе копии и показывается warning;
   - если stat неизвестен -> destination сохраняется, пользователь получает warning.
4. DELETE с потерянным ACK считается успешным только если повторный stat подтверждает отсутствие объекта.
5. REPLACE:
   - ambiguous rename `old -> backup` не ведёт к дальнейшим разрушительным шагам;
   - ambiguous `temp -> final` не запускает cleanup страховочных объектов;
   - при точно не выполненном `temp -> final` Aura пытается вернуть backup на исходное имя;
   - если восстановление нельзя доказать, backup/temp не удаляются автоматически.
6. `PreserveTemporaryException` отдельно сообщает atomic-copy слою не удалять страховочный `.aura-part-*`/`.aura-dir-*` при неоднозначном исходе.
7. `CancellationException` по-прежнему никогда не превращается в fallback.

## Исполняемая проверка

Актуальный `BackendTransferCore.kt` скомпилирован отдельным Kotlin harness с fake `StorageBackend` и fault injection. Пройдено 11 сценариев:

1. fast MOVE: applied + ACK lost;
2. fast MOVE: rejected/no apply;
3. fast MOVE: applied + stat ambiguous;
4. cross-backend MOVE: source delete applied + ACK lost;
5. cross-backend MOVE: source delete rejected -> обе копии;
6. DELETE: applied + ACK lost;
7. DELETE: rejected;
8. REPLACE: `old -> backup` applied + ACK lost;
9. REPLACE: `temp -> final` applied + ACK lost;
10. REPLACE: `temp -> final` definitely rejected -> old restored;
11. REPLACE: final rename ambiguous -> страховочные объекты не очищаются.

Результат: `BACKEND_MUTATION_RECONCILE_PASS`.

`git diff --check`: PASS.

## Следующая точка

13C — облачные backend'ы и legacy network routes: Yandex async operations/timeouts, Google Drive duplicate-name/shortcut semantics, FTP legacy repository, SMB legacy reachability, cleanup временных объектов и stale sessions.
