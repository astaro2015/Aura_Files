# Aura Files — AUDIT CHECKPOINT 17D

Дата: 2026-09-14\\
Этап: Android manifest / FileProvider / backup / R8 / JNI / resource release surface

## Автоматическая проверка

Добавлен:

`audit_harness/stage17d_android_release_surface.py`

Результат:

```text
STAGE17D_ANDROID_RELEASE_SURFACE_PASS xml=14 components=21
git diff --check: PASS
```

## Manifest / exported surface

XML parser успешно разобрал AndroidManifest и все XML resources.

Намеренно `exported=true` только:

- `.MainActivity` — launcher;
- `.ui.SplitPackageInstallerActivity` — пользовательский VIEW/SEND entry point для `.apks`.

Все остальные Activity/Service/Provider явно `exported=false`.

`SplitPackageInstallerActivity` остаётся экспортированной по продуктовой необходимости, а spoofing системного `PackageInstaller` callback закрыт nonce/session validation этапом 17A.

## FileProvider

Проверено:

- `androidx.core.content.FileProvider` не экспортирован;
- `grantUriPermissions=true`;
- authority = `${applicationId}.fileprovider`;
- разрешены только ожидаемые project paths: внешний user storage и четыре управляемых cache-root (`shares`, `archive-preview`, `backend-open`, `vault-open`).

Широкий `external-path` является намеренным для файлового менеджера, которому нужно share/open произвольных выбранных пользователем файлов; доступ выдаётся через per-URI grant, а не экспортированный provider.

## Backup / device transfer

В обоих rulesets проверены exclusions для:

- credentials/network/cloud profile secrets;
- Yandex OAuth/token state;
- `.apks` trusted callback nonce/session prefs.

Durable destructive-operation journals хранятся через `noBackupFilesDir` и не требуют отдельных XML exclusions.

## R8 / JNI

Проверено соответствие:

- Kotlin `NativeFileTime.setModifiedNative(...)`;
- JNI symbol `Java_com_aurafiles_app_data_NativeFileTime_setModifiedNative`;
- ProGuard keep-rule для native methods `NativeFileTime`.

Это защищает JNI entry point от release minification rename/removal.

## High-impact permissions

Проверено, что release manifest permissions имеют фактические reachable product features:

- `MANAGE_EXTERNAL_STORAGE` — full-storage file manager / local services;
- `WRITE_SETTINGS` — назначение системного звука;
- `QUERY_ALL_PACKAGES` — страница установленных приложений/APK export;
- `REQUEST_INSTALL_PACKAGES` — APK/APKS installer.

Store-policy eligibility этих permissions не является технической build/security проверкой и зависит от канала публикации.
