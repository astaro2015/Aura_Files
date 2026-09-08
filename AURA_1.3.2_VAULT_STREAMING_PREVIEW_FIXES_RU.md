# Aura Files 1.3.2 — Vault streaming / preview fixes

## Что исправлено

1. Устранён OOM при переносе крупных файлов в защищённое «Избранное».
   - В 1.3.1 payload шифровался одной AES-GCM операцией. На части Android-провайдеров `doFinal()` мог запросить буфер размером примерно со весь файл + 16-byte GCM tag. На реальном 256 MiB файле это проявлялось как попытка выделить 268435472 bytes.
   - Новый формат AVF2 шифрует данные независимыми authenticated chunks по 1 MiB. Каждый chunk имеет собственный random 96-bit IV и GCM tag; индекс и plain length включаются в AAD.
   - Добавлен authenticated zero-length end record, поэтому обрезание/перестановка блоков обнаруживается.
   - AVF1 из 1.3.1 остаётся читаемым; новые записи создаются только как AVF2.

2. Миниатюры внутри защищённого «Избранного».
   - Перед удалением исходника Aura безопасно создаёт маленькую thumbnail для image/video, не декодируя исходное изображение целиком.
   - Thumbnail шифруется отдельной AES-GCM записью внутри AVF2 и не лежит рядом открытым JPEG.
   - В VaultActivity видимые image/video теперь показывают миниатюру; если создать thumbnail не удалось, остаётся обычная type icon.
   - Старые AVF1 читаются как раньше, но embedded thumbnail у них отсутствует.

3. Открытие/просмотр из Vault приведены к обычной логике Aura.
   - При тапе файл временно расшифровывается только в private cache Aura и превращается во временный FileProvider URI.
   - Image/video/audio/APK/APKS идут через те же enhanced viewers/installers, что и обычные файлы.
   - PDF/text и остальные поддерживаемые форматы используют стандартный FilePreviewDialog; доступно «Открыть в другом приложении».
   - Reader formats и browseable archives также открываются штатными внутренними экранами Aura.
   - Share по-прежнему отдаёт только временную plaintext-копию через FileProvider.

4. Дополнительный safety-аудит Vault.
   - `.AuraVault` исключён из обычного browser/index, LocalStorageBackend, встроенных FTP/SFTP listings и прямого разрешения reserved path.
   - Имя `.AuraVault` зарезервировано для create/rename/path operations Aura.
   - Повторное помещение vault-entry в Vault запрещено.
   - При неудавшемся удалении исходника: если исходник точно существует — encrypted copy откатывается; если provider сообщил неоднозначное состояние — encrypted copy сохраняется, чтобы исключить потерю единственной копии.
   - Restore не удаляет уже восстановленный plaintext при неоднозначной ошибке удаления vault-copy: предпочтение отдаётся возможному duplicate, а не data loss.

## Проверки

- Kotlin/KTS PSI syntax: 125 files, 0 errors.
- Android XML parse: 14 files, 0 errors.
- `AuraVault.kt` semantic compile выполнен отдельно против Android/API stubs: success.
- AVF2 stress test: 314572923 plaintext bytes при `-Xmx64m` — encrypt/decrypt round-trip success, без whole-file allocation.
- AVF2 integrity test: round-trip, wrong device key rejection, ciphertext corruption rejection, truncation rejection и encrypted thumbnail authentication — success.
- Полный Gradle build в текущей среде не стартует: wrapper не может скачать Gradle 9.5 с `services.gradle.org` (UnknownHostException), то есть до компиляции проекта не доходит.
