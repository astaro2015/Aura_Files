# Aura Files 1.3.13 — APK share package-dispatch fix

> **Superseded by 1.3.14:** реальный preflight 1.3.13 показал, что AndroidX FileProvider всё ещё не может открыть outgoing URI на тестовом OEM (`Missing android.support.FILE_PROVIDER_PATHS meta-data`). Package-level dispatch сохраняется, но provider заменён в 1.3.14.

Дата: 2026-09-16
Версия: `1.3.13` / `versionCode 143`

## Реальный симптом 1.3.12

После исправления FileProvider список приложений уже открывался, но при выборе Telegram появлялось «вложение не поддерживается», а MAX открывал пустое сообщение. Это показало, что URI generation уже проходит, а ошибка находится в dispatch выбранному приложению.

## Корень

1.3.10–1.3.12 сохраняли `ResolveInfo.activityInfo.name` и запускали `ACTION_SEND` через `setComponent(...)`. Для мессенджеров один package может экспортировать несколько share/activity entry points; query может вернуть trampoline/text handler вместо корректного file-share handler. Жёсткая привязка к внутренней Activity поэтому нестабильна.

## Исправление

- APK share targets теперь дедуплицируются по `packageName`, а не по `ComponentName`.
- После выбора приложение адресуется через `Intent.setPackage(target.packageName)`; внутренний file handler выбирает Android/сам recipient.
- MIME priority изменён на concrete-first: `application/vnd.android.package-archive` → `application/octet-stream` → `*/*`. OEM installer больше не может перехватить системный chooser, потому что Aura использует собственный package picker.
- Перед показом списка `verifyApkShareUriReadable()` открывает outgoing `content://` URI через `ContentResolver`, сверяет размер и читает первый байт. Нечитаемый provider URI теперь останавливается внутри Aura с явной ошибкой.
- `EXTRA_STREAM`, `ClipData`, `EXTRA_TITLE`, `FLAG_GRANT_READ_URI_PERMISSION` и явный `grantUriPermission(packageName, ...)` сохранены.

## Регрессия

`audit_harness/stage18h_apk_share_package_dispatch.py` запрещает возврат `setComponent(...)`, требует package-level dispatch и URI preflight.
