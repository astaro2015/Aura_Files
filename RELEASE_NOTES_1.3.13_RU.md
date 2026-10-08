# Aura Files 1.3.13

## Исправлено: отправка готового APK в Telegram/MAX

- Исправлен следующий реальный сбой после 1.3.12: список получателей открывался, но Telegram писал «вложение не поддерживается», а MAX открывал пустое сообщение.
- Aura больше не запускает конкретную внутреннюю Activity мессенджера через `setComponent`. Получатели группируются по package, а выбранное приложение запускается через `setPackage`, чтобы сам Android/recipient выбрал правильный file-share handler.
- Перед показом списка Aura проверяет собственный `content://` URI: provider должен открыть APK, размер должен совпасть, поток должен быть непустым.
- MIME для APK теперь выбирается concrete-first: APK MIME → generic binary → wildcard.
- FileProvider URI fix 1.3.12, Universal APK merge/sign/verify 1.3.8 и APKS fallback сохранены.
