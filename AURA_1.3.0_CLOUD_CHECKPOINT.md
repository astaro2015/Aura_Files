# Aura Files 1.3.0 checkpoint

Актуальная рабочая версия: **1.3.0 / versionCode 130**.

База: Aura Files 1.2.9 INSTALLED APPS SHARE.

Главное в 1.3.0:
- `Очистка → Приложения` сохраняется;
- single APK по-прежнему экспортируется обычным `.apk` через системное «Поделиться…»;
- SPLIT теперь экспортируется целиком в один Aura `.apks`;
- `.apks` содержит base + все `splitSourceDirs` + manifest с SHA-256;
- Aura умеет открыть свой `.apks` и установить все APK одной PackageInstaller session;
- добавлены проверки целостности, package/version, signing info когда доступен, minSdk и native ABI;
- Android unknown-source permission запрашивается штатным системным экраном;
- обработаны системные install statuses и `STATUS_PENDING_USER_ACTION`;
- `.apks` относится к категории APK и открывается встроенным installer как локально, так и после скачивания cloud backend.
- На `Очистка → Приложения` есть **«Установить .APKS»** с системным `OpenDocument` picker — fallback для Telegram/провайдеров, которые не отдают корректный MIME при обычном тапе.
- Split installer принимает Aura custom MIME через ACTION_VIEW и ACTION_SEND.

Существующие cloud/OAuth/network/transfer/images/trash функции сохранять без регрессий.

Перед следующими изменениями читать:
1. `AURA_1.3.0_SPLIT_APKS_TRANSFER_INSTALL_RU.md`
2. `AURA_1.2.9_APPS_PAGE_CHANGES_RU.md`
3. `AURA_1.2.8_CLOUD_THEME_FIX_CHANGES_RU.md`
4. `AURA_1.2.7_IMAGES_CATALOG_CHANGES_RU.md`
5. `AURA_1.2.6_CLOUD_HEADER_FIX_CHANGES_RU.md`

После любых изменений пересчитывать `SOURCE_SHA256SUMS.txt` самым последним шагом.
