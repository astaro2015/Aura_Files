# Aura Files — FINAL RELEASE AUDIT CHECKPOINT 17

Дата: 2026-09-14\\
Кандидат: **Aura Files 1.3.5 / versionCode 135**

## Статус source-side аудита

Многоэтапный аудит исходников завершён до точки внешней Android clean-build проверки.

В текущей Git history сохранены отдельные checkpoints/commits для:

- 13C1–13C4: Yandex / Google Drive / legacy FTP-SMB / общий backend;
- recovered 12C: durable batch rename crash journal;
- 14A–14C: image/PDF preview, temporary cache lifecycle, reader memory/cancellation;
- 15: UI/state/concurrency;
- recovered 12A: local recursion/depth/cycle/symlink safety;
- 16A–16D: index reconciliation, local transfer transactions, local REPLACE crash journal, Vault Restore crash journal;
- recovered 12B: trash transaction safety;
- 17A: APK/APKS release blockers recovered;
- 17B: legacy checkpoint consistency sweep;
- 17C: dependency/security/license review;
- 17D: Manifest/FileProvider/backup/R8/JNI/XML surface;
- 17E: aggregate harness rerun + real Gradle build attempt;
- 17F: 1.3.5/135 release identity;
- 17G: package/credential hygiene before checksum generation.

## Итоговые fault/state проверки

На release tree повторно получены:

```text
batch rename model:                    140 PASS
production batch rename engine:        127 PASS
trash transaction/recovery:             50 PASS
local transfer transaction model:       87 PASS
local REPLACE crash recovery:            17 PASS
Vault Restore crash recovery:            15 PASS
APKS callback spoof model:                 4 PASS
legacy checkpoint consistency:          PASS
dependency release guard:               PASS
Android release surface:                PASS
release identity:                       PASS
```

## Safety policy, которая теперь закреплена кодом

- ambiguous destructive mutation сохраняет возможную страховочную копию/backup/temp вместо риска потерять обе версии;
- cancellation не превращается в destructive fallback;
- recursive destructive/copy operations bounded и не следуют symlink/junction;
- process kill между destructive шагами batch rename/local REPLACE/Vault Restore восстанавливается через durable journal;
- remote lost ACK reconciliate по фактическому source/target state;
- partial/cache/plaintext output не становится финальным файлом до доказанного commit;
- exported APKS installer не доверяет внешнему install-result без сохранённых sessionId + nonce.

## Release surface

- версия: `1.3.5` / `135`;
- direct security updates: XZ for Java `1.12`, Bouncy Castle 1.85 line (`bcprov 1.85.2` / `bcpkix 1.85`);
- third-party runtime inventory актуализирован;
- ровно два intentional exported Activity: launcher и APKS entry point;
- FileProvider `exported=false`, per-URI grants;
- sensitive prefs исключены из backup/device transfer;
- JNI symbol и R8 keep-rule согласованы;
- tracked source/история не содержат обнаруженных concrete secret/key signatures.

## Единственный незакрытый внешний gate

Полноценный Android clean build **не выполнен в контейнере**: Gradle wrapper останавливается на
`UnknownHostException: services.gradle.org`, Android SDK отсутствует, dependency cache пуст.

Это инфраструктурное ограничение, но оно означает, что APK нельзя называть полностью release-verified до запуска:

```text
BUILD_ON_CLEAN_WINDOWS.bat
```

Если clean-Windows build успешен — source candidate можно считать прошедшим последний release gate.
Если build падает — продолжать строго с первого compiler/build failure из `BUILD_OUTPUT/build.log` и `BUILD_OUTPUT/LAST_ERROR.txt`.

## Packaging rule

`SOURCE_SHA256SUMS.txt` пересчитывается **после этого checkpoint и всех source changes**. Clean source ZIP должен содержать только manifest-listed tracked files плюс сам checksum manifest; случайные extra files не являются частью release source.

## Post-gate correction: BUILD_FIX1

Первый реальный clean-Windows запуск дошёл до dependency resolution и подтвердил ошибку release-аудита: `org.bouncycastle:bcpkix-jdk18on:1.85.2` не существует в Maven Central. Это не runtime-дефект Aura, а ошибочный dependency pin в source candidate.

Исправлено без изменения функционального кода приложения:

- `bcprov-jdk18on:1.85.2` сохранён (runtime dependency SMBJ 0.15.0);
- `bcpkix-jdk18on` исправлен на опубликованный `1.85`;
- `stage17c_dependency_release_guard.py` теперь проверяет именно эту пару;
- Windows bootstrap сохраняет отдельные compiler/build highlights в `LAST_ERROR.txt`.

После этой коррекции обязательный clean-Windows APK gate нужно повторить.
