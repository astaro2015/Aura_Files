# Aura Files 1.3.11 — FileProvider OEM fix

> **ВАЖНО: этот fix оказался неполным.** Реальный тест 1.3.11 показал, что статические `FileProvider.getUriForFile(...)` продолжали требовать manifest meta-data. Полное исправление — 1.3.12, см. `AURA_1.3.12_FILEPROVIDER_URI_GENERATION_FIX_CHECKPOINT.md`.

Дата: 2026-09-16
Версия: `1.3.11` / `versionCode 141`

## Фактический симптом на устройстве
После успешной сборки и подписи единого APK на Xiaomi кнопка «Поделиться» показывала ошибку `Missing android.support.FILE_PROVIDER_PATHS meta-data`. Это доказало, что цепочка уже проходит signing и UI-кнопку и ломается непосредственно на `FileProvider.getUriForFile()`.

## Исправление
- Прямой provider `androidx.core.content.FileProvider` заменён на собственный `com.aurafiles.app.AuraFileProvider`.
- `AuraFileProvider` наследует `FileProvider(R.xml.file_paths)`, поэтому таблица разрешённых путей закреплена в самом provider и не зависит только от OEM lookup manifest meta-data.
- `<meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths"/>` оставлена в manifest как совместимый fallback.
- Authority остаётся `${applicationId}.fileprovider`; существующие вызовы `FileProvider.getUriForFile(..., "$packageName.fileprovider", ...)` менять не требуется.
- Explicit-target picker и URI grants из 1.3.10 сохранены.

## Регрессия
Добавлен `audit_harness/stage18f_fileprovider_oem_safety.py`, который запрещает прямую регистрацию `androidx.core.content.FileProvider`, требует `AuraFileProvider(R.xml.file_paths)` и проверяет manifest fallback.
