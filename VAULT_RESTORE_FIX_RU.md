# Aura Files 1.3.15: исправление возврата из Избранного

Дата: 2026-10-06. Основа: `Aura_Files_1.3.15_MEDIASTORE_APK_SHARE_FINAL_SOURCE.zip`.
Исходное дерево в `F:/AI work/Aura Files2` проверено: 483 SHA-256 записи, расхождений нет.

При нажатии «Вернуть» Android выдаёт доступ на URI вида
`content://provider/tree/<id>`. `DocumentFile.fromTreeUri()` затем возвращает
URI папки вида `content://provider/tree/<id>/document/<id>`.
Раньше Vault передавал второй URI в `takePersistableUriPermission()`.
Android ищет сохраняемое разрешение для точного URI, поэтому отвергал запрос.

`AuraVault` теперь восстанавливает URI выданного tree-разрешения через
`DocumentsContract.buildTreeDocumentUri()` и использует его для проверки,
сохранения и освобождения доступа. Журнал по-прежнему хранит URI самой папки:
это сохраняет точное место восстановления и совместимость существующих журналов.
Уже сохранённый доступ пользователя переиспользуется и не освобождается Vault.
Алгоритмы шифрования и удаления зашифрованного оригинала не изменены.

Проверки:

- Регрессионный тест компилирует реальные методы получения/освобождения
  разрешения из `AuraVault.kt` и подставляет только границу Android URI/grants.
  До исправления воспроизводится `SecurityException: No persistable permission
  grants found`; после исправления проходят 6 сценариев: выбранная папка,
  вложенная папка, tree URI, ранее сохранённый доступ, отказ и прямой file URI.
  Проверено освобождение собственного разрешения при восстановлении после
  нового запуска; чужое/ранее существовавшее разрешение сохраняется.
- `stage16d_vault_restore_model.py`: 15 сценариев аварийного восстановления
  и проверки сохранности копий прошли.
- `testDebugUnitTest`: 70 тестов, 0 failures, 0 errors.
- `assembleRelease`: APK собран с R8 и проверен `apksigner verify` (v2).
  SHA-1 сертификата совпал с исходным APK 1.3.15.
  Размер: 8 865 363 байта. SHA-256:
  `d5e2d3496493d3b3e895af2fe848b6a0a8c73c877dfbd48aee0be623b5f9fc4b`.
- `lintRelease`: 1 error, 147 warnings. Ошибка `NewApi` находится в
  неизменённом `ApkSharePublisher.kt:101`: `MediaStore.Downloads.getContentUri()`
  требует API 29 при minSdk 26. В исходном методе уже есть проверка API 29,
  но lint не выводит это ограничение из используемой проверки. Исправление
  Vault не меняет APK-шаринг. Общая команда Gradle завершилась с exit 1
  из-за lint, хотя `testDebugUnitTest` и `assembleRelease` завершились успешно.

Команда проверки: `gradlew.bat --offline --no-daemon --no-configuration-cache
--console=plain testDebugUnitTest lintRelease assembleRelease`.

Регрессионная команда: `python audit_harness/vault_restore_permission_regression.py`.
Существующая модель: `python -X utf8 audit_harness/stage16d_vault_restore_model.py`.
Регрессионный тест использует Windows JDK/Kotlin cache официального builder.
Это проверка JVM с заменой Android-границы, а не запуск на физическом телефоне.
Телефон для проверки системного выбора папки не был подключён.

APK оставляет версию `1.3.15` / versionCode `145` и application ID
`com.aurafiles.app`; исправленная локальная сборка получает отдельное имя
`Aura_Files_1.3.15-vault-restore-fix-release.apk`.
Она должна устанавливаться поверх существующей Aura с сохранением данных.
Сертификат должен совпадать с прежним: SHA-1
`902ff6170c61eb8cda08ee608e87127635514bb5`.

Источник контракта Android:
[UriGrantsManagerService.takePersistableUriPermission](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/uri/UriGrantsManagerService.java).
