# Aura Files 1.3.6

Главное изменение 1.3.6 — возможность отправить установленное SPLIT-приложение как **один обычный APK**, а не только как `.apks`.

## Единый APK

В `Очистка → Приложения` у SPLIT-приложений основная кнопка теперь **«Поделиться APK…»**. Aura объединяет установленный `base.apk` со всеми доступными split APK, пересобирает resource table и manifest, убирает обязательность split-комплекта и создаёт один APK. Merge основан на актуальной логике APKEditor 1.4.9 и ARSCLib 1.4.0.

Полученный файл подписывается отдельным ключом `Aura APK Export`, который создаётся в Android Keystore. Исходную подпись разработчика восстановить нельзя, поэтому такой APK предназначен для чистой установки либо обновления APK, ранее экспортированного **той же установкой Aura**. Поверх версии из Google Play/другого источника с другой подписью он не обновится.

После сборки Aura сама проверяет криптографическую подпись, package name и versionCode. Частичный/непроверенный файл пользователю не отдаётся.

Кнопка **APKS** сохранена рядом как fallback. Старый `.apks`-экспорт и PackageInstaller session installer не удалены.

## Совместимость и ограничения

Aura объединяет только splits, установленные на текущем устройстве. Поэтому один APK содержит именно доступные здесь ABI/density/language/features; отсутствующие splits из Google Play восстановить невозможно. Для защищённых приложений и отдельных Play Asset Delivery сценариев merge может быть невозможен — используйте APKS fallback.

## Под капотом

- ARSCLib 1.4.0 для merge ресурсов/manifest;
- Android `apksig` 9.3.0 для v1/v2/v3 подписи и проверки;
- отдельный non-exportable Android Keystore key;
- XZ for Java и остальные зависимости safety-аудита 1.3.5 сохранены;
- `BUILD_ON_CLEAN_WINDOWS.bat` остаётся debug builder, `BUILD_RELEASE_WINDOWS.bat` — финальный `testDebugUnitTest + assembleRelease` gate.

## Hotfix: подпись Universal APK

Исправлен runtime-сбой `Failed to sign` при подписи объединённого APK на устройствах, где `apksig` не мог использовать provider-backed RSA private key из AndroidKeyStore. Экспортная подпись теперь использует отдельный software RSA key + X.509 certificate в `noBackupFilesDir`; ключ не связан с подписью Aura/Vault и не попадает в Android backup. Повреждённая/неполная export identity не регенерируется молча.
