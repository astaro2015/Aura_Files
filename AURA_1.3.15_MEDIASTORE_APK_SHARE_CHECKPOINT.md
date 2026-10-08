# Aura Files 1.3.15 — system MediaStore APK share handoff

Дата: 2026-09-16
Версия: `1.3.15` / `versionCode 145`

## Почему 1.3.14 не считается финальным решением

Повторная проверка показала, что 1.3.14 всё ещё оставляла мессенджер-зависимую цепочку: Aura сама выбирала target и передавала URI собственного provider. Кроме того, прежний MIME fallback был логически слабым: приложение с фильтром `*/*` могло быть найдено уже на APK MIME и получить нежелательный `application/vnd.android.package-archive`.

## Новый маршрут 1.3.15

- На Android 10+ готовый APK сначала копируется через `MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)` в `Download/Aura Files`; синтетический `VOLUME_EXTERNAL` для записи не используется.
- Пока идёт запись, выставлен `IS_PENDING=1`; после полной записи — `IS_PENDING=0`.
- Перед показом системного Sharesheet Aura повторно проверяет имя/размер, recipient-visible MIME, `openFileDescriptor()` и первые ZIP bytes `PK`.
- Messenger-facing MIME фиксирован как `application/octet-stream`.
- Для `ClipData` используется `newRawUri()`, поэтому MIME не переопределяется повторным `ContentResolver.getType()` при создании ClipDescription.
- Получателю передаются стандартные `ACTION_SEND + EXTRA_STREAM + ClipData + FLAG_GRANT_READ_URI_PERMISSION`; package/component не фиксируются.
- Если OEM MediaStore не может корректно опубликовать файл, Aura удаляет незавершённую запись и использует проверенный native `AuraFileProvider` как last-resort fallback.
- Если MediaStore сообщает APK MIME вместо generic binary или файл обрезан, такая публикация считается невалидной, удаляется и включается fallback.
- Coroutine cancellation не поглощается fallback-логикой.

## Регрессии

- `stage18m_apk_share_handoff_hardening.py`: структура финального handoff и запрет старых package/component/MIME путей.
- `stage18n_media_store_runtime_integration.py`: исполняемый runtime-model с 5 сценариями MediaStore/fallback и явной primary-volume коллекцией.
- Старые native-provider tests сохраняются для Android 8/9 и OEM fallback.
