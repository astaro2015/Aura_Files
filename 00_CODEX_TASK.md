# Aura Files 1.3.4 — сведения для продолжения

Это полное рабочее дерево Aura Files 1.3.4.

- `versionName = "1.3.4"`
- `versionCode = 134`
- package `com.aurafiles.app`
- 1.3.2: Vault AVF2 с AES-GCM chunks по 1 MiB; не возвращать whole-file GCM из 1.3.1 — он давал реальный OOM на ~256 MiB файле.
- AVF1 остаётся только для backward read.
- Новые image/video в Vault получают encrypted embedded thumbnail; VaultActivity должен показывать thumbnail и открывать файлы через обычные Aura viewer/player/reader/archive/APK/APKS flows после private temporary decrypt.
- `.AuraVault` скрыт и зарезервирован во всех локальных путях Aura, включая LocalStorageBackend и встроенные FTP/SFTP server paths/listings.
- «В избранное» означает только MOVE. При неоднозначном SAF delete предпочитать возможный duplicate потере единственной копии.
- Device-bound key не менять: `ANDROID_ID -> HKDF-SHA256(info="AuraVault-v1") -> AES-256-GCM`.
- 1.3.0 добавляет полный цикл SPLIT: экспорт installed base + splitSourceDirs в один Aura `.apks` и установка одной PackageInstaller session.
- На принимающем телефоне сохранять `Очистка → Приложения → Установить .APKS`.
- Не заменять split-share экспортом одного `base.apk`.
- Cloud/OAuth/backend architecture, Images paging и transfer/trash safety сохранять.

Перед продолжением читать:
1. `AURA_1.3.4_CLOUD_CHECKPOINT.md`
2. `AURA_1.3.4_ALL_STORAGE_BROWSER_RU.md`
3. `AURA_1.3.3_SMB_STANDARD_BROWSER_RU.md`
4. `AURA_1.3.2_CLOUD_CHECKPOINT.md`
5. `AURA_1.3.2_VAULT_STREAMING_PREVIEW_FIXES_RU.md`
6. `AURA_1.3.1_VAULT_FAVORITES_CHANGES_RU.md`
7. `AURA_1.3.0_SPLIT_APKS_TRANSFER_INSTALL_RU.md`

После любых изменений пересчитывать `SOURCE_SHA256SUMS.txt` самым последним шагом.
