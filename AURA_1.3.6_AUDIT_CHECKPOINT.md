# Aura Files 1.3.6 — Universal APK export checkpoint

Версия: `1.3.6` / `versionCode 136`\\
База: полностью проверенная ветка Aura Files 1.3.5 + BUILD_FIX7.

## Что добавлено

- Для установленного SPLIT-приложения основное действие в `Очистка → Приложения` теперь создаёт один `.apk`.
- `UniversalApkMerger` адаптирует merge/sanitize логику APKEditor 1.4.9 и использует ARSCLib 1.4.0 без встраивания APKEditor CLI/smali/JCommand.
- После merge удаляются split-required markers и устаревшие signature entries, затем APK подписывается заново.
- `ApkExportSigner` использует отдельный `AndroidKeyStore` alias `aurafiles.apk.export.v1`; pinned release/debug signing key самой Aura не используется.
- Подпись v1/v2/v3 и `ApkVerifier` ориентируются на minSdk экспортируемого APK, а не на minSdk самой Aura.
- `UniversalApkExporter` fail-closed: проверяет размер/свободное место/неизменность split-файлов, package/version и подпись; результат публикуется atomic rename только после успешных проверок.
- APKS-путь сохранён отдельной кнопкой `APKS` как fallback.
- При невозможности merge автоматического молчаливого fallback нет: пользователь получает ошибку и сам выбирает APKS.

## Safety/release invariants

Все инварианты `AURA_1.3.5_AUDIT_CHECKPOINT.md` остаются обязательными. В частности, cancellation не превращается в destructive fallback, а сетевые retry по-прежнему ограничены явными transient ошибками вроде `UnknownHostException`.

## Новые зависимости

- `io.github.reandroid:ARSCLib:1.4.0`
- `com.android.tools.build:apksig:9.3.0`

Лицензии/attribution: `THIRD_PARTY_NOTICES.md`.

## Проверки

- `audit_harness/stage18_policy_harness.kt` — naming/UI policy.
- `audit_harness/stage18_universal_apk_export.py` — dependency/merge/sign/verify/UI/fallback invariants.
- Полный исторический `audit_harness/*.py` должен оставаться зелёным.
- Финальный Android gate: `BUILD_RELEASE_WINDOWS.bat` (`testDebugUnitTest` + `assembleRelease`, pinned Aura signer verification).
