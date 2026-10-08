# Aura Files 1.3.10

Второе исправление «Поделиться» для готового единого APK на Xiaomi/MIUI/HyperOS.

## Что изменено

1.3.9 уже находила внешние приложения-получатели и делала MIME fallback, но на тестовом Xiaomi системный chooser после нажатия «Поделиться» всё равно не появлялся. Поэтому в 1.3.10 системный chooser больше не является обязательной частью этого сценария.

- окно «APK готов» сначала закрывается;
- показ следующего UI отложен через `window.decorView.post`;
- Aura сама получает список внешних `ACTION_SEND` activities и показывает их пользователю;
- выбранное приложение запускается явным `ComponentName` / `setComponent(...)`;
- `grantUriPermission`, FileProvider URI, `ClipData` и `FLAG_GRANT_READ_URI_PERMISSION` выдаются выбранному приложению напрямую;
- для обычной отправки бинарного APK сначала используется `application/octet-stream`, затем `*/*`, а специальный APK MIME оставлен последним fallback;
- ошибки подготовки/передачи теперь показываются с фактической причиной;
- Universal APK merge/sign/verify и APKS fallback не изменены.

Добавлен regression stage18e для защиты от возврата синхронного запуска share во время dismiss `AlertDialog` и от возврата зависимости от OEM chooser.
