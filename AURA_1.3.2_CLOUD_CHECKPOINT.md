# Aura Files 1.3.2 checkpoint

Актуальная рабочая версия: **1.3.2 / versionCode 132**.

База: Aura Files 1.3.1 VAULT FAVORITES + 1.3.0 SPLIT/APKS FINAL.

Главное в 1.3.2:
- Vault format AVF2: AES-256-GCM payload chunks по 1 MiB вместо одной whole-file GCM message; устранён OOM на крупных файлах;
- AVF1 backward read compatibility сохранена;
- encrypted image/video thumbnails встроены в AVF2;
- VaultActivity показывает thumbnails и использует обычные Aura preview/player/reader/archive/APK/APKS flows через private temporary plaintext + FileProvider;
- расширено скрытие `.AuraVault` из Local backend / FTP / SFTP и зарезервированы vault paths;
- усилена no-data-loss логика move/restore при неоднозначном поведении SAF providers.

Сохранять без регрессий:
- device-bound key derivation: `ANDROID_ID -> HKDF-SHA256(info="AuraVault-v1") -> AES-256-GCM`;
- только MOVE в «Избранное» и «Вернуть» наружу;
- 1.3.0 SPLIT/APKS export/install;
- cloud OAuth/backends и unified cloud UI;
- 1.2.7 Images paging/filters;
- trash/transfer safety.

Перед продолжением читать:
1. `AURA_1.3.2_VAULT_STREAMING_PREVIEW_FIXES_RU.md`
2. `AURA_1.3.1_VAULT_FAVORITES_CHANGES_RU.md`
3. `AURA_1.3.0_SPLIT_APKS_TRANSFER_INSTALL_RU.md`

После любых изменений `SOURCE_SHA256SUMS.txt` пересчитывать последним шагом.
