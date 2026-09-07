# Aura Files 1.3.1 checkpoint

Актуальная рабочая версия: **1.3.1 / versionCode 131**.

База: Aura Files 1.3.0 SPLIT/APKS FINAL.

Главное в 1.3.1:
- «Избранное» больше не URI bookmarks; это persistent encrypted `.AuraVault` в общей памяти;
- добавление означает только move, не copy;
- device-bound key: `ANDROID_ID -> HKDF-SHA256(info="AuraVault-v1") -> AES-256-GCM`;
- ключ/PIN/биометрия/файл ключа не используются;
- metadata и content зашифрованы; наружу торчат только UUID `.avf`;
- VaultActivity даёт открыть / поделиться / вернуть / удалить;
- после reinstall на том же устройстве восстановление рассчитано на тот же signing key и Android user;
- `.AuraVault` исключён из обычного browser/index;
- директории в vault в этой версии не поддерживаются.

Сохранять без регрессий:
- 1.3.0 SPLIT/APKS export/install;
- cloud OAuth/backends;
- 1.2.8 cloud Material3 theme fix;
- 1.2.7 Images paging/filters;
- корзину/transfer safety.

Перед продолжением читать:
1. `AURA_1.3.1_VAULT_FAVORITES_CHANGES_RU.md`
2. `AURA_1.3.0_SPLIT_APKS_TRANSFER_INSTALL_RU.md`
3. `AURA_1.2.9_APPS_PAGE_CHANGES_RU.md`

После изменений `SOURCE_SHA256SUMS.txt` пересчитывать последним шагом.
