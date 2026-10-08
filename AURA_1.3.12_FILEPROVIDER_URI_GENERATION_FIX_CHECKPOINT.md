# Aura Files 1.3.12 — FileProvider URI generation fix

Дата: 2026-09-16\\
Версия: `1.3.12` / `versionCode 142`

## Фактический симптом
На Xiaomi/MIUI версия 1.3.11 продолжала показывать `Missing android.support.FILE_PROVIDER_PATHS meta-data` при попытке поделиться уже готовым APK.

## Найденный корень
`AuraFileProvider : FileProvider(R.xml.file_paths)` был добавлен правильно, но вызывающий код продолжал использовать статический `FileProvider.getUriForFile(...)`.
AndroidX в этом статическом методе вызывает `getPathStrategy(..., ResourcesCompat.ID_NULL)` и снова требует `android.support.FILE_PROVIDER_PATHS` через `PackageManager`. Поэтому ресурс, переданный в конструктор `AuraFileProvider`, при генерации URI вообще не использовался.

## Исправление
- Добавлен `AuraFileProvider.uriForFile(context, file)` — Aura формирует `content://` URI сама, в формате, совместимом с `FileProvider.SimplePathStrategy`.
- Разрешённые root names полностью совпадают с `res/xml/file_paths.xml`: `shared_storage`, `temporary_shares`, `archive_preview`, `backend_open`, `vault_open`.
- Выбор root использует канонические пути и правило наиболее специфичного разрешённого корня; выход за разрешённые корни запрещён.
- Все production-вызовы `FileProvider.getUriForFile(...)` заменены на `AuraFileProvider.uriForFile(...)`.
- Входящие `content://` URI по-прежнему обслуживает `AuraFileProvider : FileProvider(R.xml.file_paths)`, поэтому на стороне provider manifest meta-data не требуется для построения path strategy.
- Manifest meta-data оставлена как дополнительный fallback, но outgoing URI generation от неё больше не зависит.

## Регрессия
- `stage18g_fileprovider_static_uri_elimination.py` запрещает статические вызовы AndroidX FileProvider URI generator во всём production Kotlin.
- `stage18g_policy_harness.kt` проверяет most-specific root, строгую границу root и отказ для неразрешённых путей.
