# Aura Files 1.3.5 — release audit checkpoint

Дата: 2026-09-14\\
Версия: `1.3.5` / `versionCode 135`

## Каноническая точка

Это продолжение 1.3.4 после многоэтапного deep audit. Источником истины по выполненным hardening-проходам являются `AUDIT_CHECKPOINT_*.md` и Git history.

Ключевые release commits текущего прохода включают:

- network/cloud 13C;
- recovered batch-rename journal 12C;
- viewer/cache/reader 14A–14C;
- UI/state 15;
- recovered local recursion 12A и trash transaction safety 12B;
- cross-feature destructive safety 16A–16D;
- APK/APKS release blocker recovery 17A;
- legacy checkpoint consistency 17B;
- dependency/license audit 17C;
- Android release surface 17D;
- aggregate regression/build gate 17E.

## Нельзя ломать

- Vault остаётся device-bound AVF2 chunked AES-GCM; AVF1 только backward read.
- «В Избранное» — MOVE; неоднозначный destructive result сохраняет возможный duplicate/backup вместо риска потери данных.
- `.AuraVault`/`.AuraTrash` не показываются normal browser/index/network-server paths.
- APKS = base + splits в одной PackageInstaller session; ordinary APK share остаётся.
- Cloud/remote destructive mutations не повторяются вслепую после lost ACK.
- Cancellation не превращается в destructive fallback.
- Recursive operations не следуют symlink и имеют bounded depth/cycle guards.

## Проверки перед этим checkpoint

Aggregate harness pass:

- batch rename model: 140 scenarios;
- production batch rename engine: 127 scenarios;
- trash recovery: 50;
- local transfer: 87;
- local replace crash: 17;
- Vault restore crash: 15;
- APKS callback spoof model: 4;
- release consistency/dependency/manifest guards: PASS.

## Последний внешний gate

Контейнер не смог скачать Gradle 9.5.0 (`UnknownHostException: services.gradle.org`) и не имеет Android SDK/cache. Полный Android build здесь не заявлен.

На финальном source candidate выполнить `BUILD_ON_CLEAN_WINDOWS.bat`. При failure продолжать с первого реального compiler/build error из `BUILD_OUTPUT/build.log` и `BUILD_OUTPUT/LAST_ERROR.txt`.
