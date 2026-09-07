# Aura Files 1.3.1 — сведения для продолжения

Это полное рабочее дерево Aura Files 1.3.1.

- `versionName = "1.3.1"`
- `versionCode = 131`
- package `com.aurafiles.app`
- Cloud/OAuth/backend architecture сохраняется.
- 1.3.0 добавляет полный цикл SPLIT: экспорт установленного base + splitSourceDirs в один Aura `.apks` и установку всего набора одной Android PackageInstaller session.
- На принимающем телефоне есть явный путь `Очистка → Приложения → Установить .APKS` через системный picker; не удалять его даже при наличии ACTION_VIEW association.
- Не заменять split-share экспортом одного `base.apk`: это намеренно запрещено.
- `.apks` Aura — device-derived transport container `aurafiles-installed-apks`, а не universal APK/AAB.
- Перед install сохранять SHA-256/package/version/minSdk/ABI preflight; финальная package/signature/split валидация остаётся за Android PackageInstaller.
- Не убирать REQUEST_INSTALL_PACKAGES / QUERY_ALL_PACKAGES без отдельного решения по функциональности.
- 1.2.8 Material3 cloud root Surface/contentColor fix сохранять.
- 1.2.7 Images catalog paging/filter logic сохранять.

Перед продолжением читать:
1. `AURA_1.3.1_CLOUD_CHECKPOINT.md`
2. `AURA_1.3.0_SPLIT_APKS_TRANSFER_INSTALL_RU.md`
3. `AURA_1.2.9_APPS_PAGE_CHANGES_RU.md`
4. `AURA_1.2.8_CLOUD_THEME_FIX_CHANGES_RU.md`
5. `AURA_1.2.7_IMAGES_CATALOG_CHANGES_RU.md`

После любых изменений пересчитывать `SOURCE_SHA256SUMS.txt` самым последним шагом.
