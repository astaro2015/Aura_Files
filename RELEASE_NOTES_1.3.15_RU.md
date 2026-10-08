# Aura Files 1.3.15

## Передача единого APK

После повторной проверки цепочка отправки APK переработана ещё раз. На Android 10+ Aura больше не отдаёт Telegram/MAX файл напрямую из собственного cache provider: APK сначала публикуется системным MediaStore на `VOLUME_EXTERNAL_PRIMARY` в `Загрузки/Aura Files`, после чего открывается обычный Android Sharesheet.

Для передачи используется `application/octet-stream`, `EXTRA_STREAM`, raw `ClipData` и временное read-разрешение. Aura не фиксирует ни пакет, ни внутреннюю Activity получателя. Перед открытием Sharesheet проверяются размер, MIME и фактическая читаемость опубликованного APK.

Если конкретная прошивка ломает MediaStore-публикацию, остаётся проверенный native ContentProvider fallback. Незавершённые/невалидные MediaStore записи удаляются автоматически.
