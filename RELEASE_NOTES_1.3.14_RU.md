# Aura Files 1.3.14

## Исправлено: `Missing android.support.FILE_PROVIDER_PATHS meta-data`

- После реального теста 1.3.13 найден фактический источник ошибки: AndroidX `FileProvider` продолжал участвовать уже не в создании, а в открытии outgoing `content://` URI.
- AndroidX FileProvider полностью удалён из этой цепочки. Aura теперь использует собственный read-only `ContentProvider`.
- `FILE_PROVIDER_PATHS` meta-data и `file_paths.xml` для отправки больше не нужны и удалены.
- Provider сам сообщает имя/размер файла, MIME и открывает APK только на чтение.
- Canonical root policy защищает от traversal, symlink escape и подмены root.
- Package-level отправка в Telegram/MAX из 1.3.13, `EXTRA_STREAM`, `ClipData`, URI grants и preflight сохранены.
