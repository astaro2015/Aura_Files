# Aura Files — AUDIT CHECKPOINT 17F

Дата: 2026-09-14\\
Этап: release identity / version bump / canonical package metadata

## Версия кандидата

```text
versionName = 1.3.5
versionCode = 135
```

1.3.5 — hardening release поверх 1.3.4 без намеренного изменения основных продуктовых сценариев.

## Обновлены канонические документы

- `README.md`;
- `00_CODEX_TASK.md`;
- `CODEX_PACKAGE_INFO.txt`;
- `WINDOWS_READY_PACKAGE_INFO.txt`;
- новый `AURA_1.3.5_AUDIT_CHECKPOINT.md`;
- новые `RELEASE_NOTES_1.3.5_RU.md`.

Исторические документы `AURA_1.3.4_*`, `PACKAGE_VERIFICATION_1.3.4_*` и release notes предыдущих версий намеренно не переименовывались и не переписывались как будто они относятся к 1.3.5.

## Автоматическая проверка

Добавлен `audit_harness/stage17f_release_identity.py`.

Результат:

```text
STAGE17F_RELEASE_IDENTITY_PASS version=1.3.5 code=135
STAGE17B_LEGACY_CHECKPOINT_CONSISTENCY_PASS
STAGE17C_DEPENDENCY_RELEASE_GUARD_PASS
STAGE17D_ANDROID_RELEASE_SURFACE_PASS xml=14 components=21
git diff --check: PASS
```

## Build gate

Release identity не меняет ограничение из 17E: полный Android clean build остаётся обязательным внешним gate через `BUILD_ON_CLEAN_WINDOWS.bat`.
