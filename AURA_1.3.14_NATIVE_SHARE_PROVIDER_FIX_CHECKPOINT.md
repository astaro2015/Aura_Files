# Aura Files 1.3.14 — native share provider fix

Дата: 2026-09-16
Версия: `1.3.14` / `versionCode 144`

## Реальный симптом 1.3.13

После добавления preflight Aura на реальном телефоне останавливала отправку сообщением `Aura не может прочитать подготовленный APK: Missing android.support.FILE_PROVIDER_PATHS meta-data`. Это доказало, что проблема не в chooser/Telegram/MAX: собственный outgoing URI создавался, но AndroidX `FileProvider` не мог обслужить его при `ContentResolver.openFileDescriptor()`.

## Корень

1.3.12 убрала статический `FileProvider.getUriForFile()`, а 1.3.11 добавила subclass `AuraFileProvider`. Но сам provider всё ещё наследовался от AndroidX `FileProvider`; значит фактическое чтение `content://` URI оставалось зависимым от его внутреннего `FILE_PROVIDER_PATHS` parser. На тестовом OEM это и было точкой отказа.

## Исправление

- `AuraFileProvider` теперь наследуется напрямую от `android.content.ContentProvider`.
- AndroidX `FileProvider` не импортируется и не используется в production source.
- Manifest provider остаётся `exported=false`, `grantUriPermissions=true`, но больше не содержит `android.support.FILE_PROVIDER_PATHS` meta-data.
- `file_paths.xml` удалён как ненужный runtime dependency.
- Provider сам реализует `query()` для `DISPLAY_NAME/SIZE`, `getType()` и read-only `openFile()`.
- URI generation и обратное разрешение используют общий `AuraFileProviderPathPolicy` с canonical path.
- Входящий route обязан совпадать с наиболее специфичным root; `../`, symlink escape и root-confusion отклоняются.
- 1.3.13 package-level `setPackage` dispatch и `verifyApkShareUriReadable()` сохранены. Поэтому реальный телефон проверяет именно provider до открытия мессенджера.

## Проверки

- `stage18i_native_share_provider.py`: запрещает AndroidX FileProvider и FILE_PROVIDER_PATHS.
- `stage18j_provider_route_harness.kt`: canonical route/traversal/symlink/root-confusion + 2000 fuzz cases.
- Provider + path policy отдельно компилируются Kotlin compiler против framework-signature stubs.
