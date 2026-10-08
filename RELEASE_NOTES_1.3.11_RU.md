# Aura Files 1.3.11

Исправлена фактическая ошибка отправки готового APK на Xiaomi: `Missing android.support.FILE_PROVIDER_PATHS meta-data`.

- Aura больше не регистрирует `androidx.core.content.FileProvider` напрямую.
- Добавлен собственный `AuraFileProvider`, которому `R.xml.file_paths` передаётся через конструктор `FileProvider(...)`.
- Manifest meta-data сохранена как дополнительный fallback.
- Логика 1.3.10 с собственным списком приложений, explicit `ComponentName`, `grantUriPermission` и MIME fallback сохранена.
- Merge/sign/verify pipeline APKS -> единый APK не менялся.
