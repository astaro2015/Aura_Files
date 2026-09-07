# Aura Files 1.3.0 — передача и установка SPLIT-приложений

Дата: 2026-09-07

## Что добавлено

В `Очистка → Приложения` для приложений с меткой `SPLIT · N APK` появилась кнопка **«Поделиться комплектом…»**.

Aura больше не отправляет один `base.apk`. Вместо этого она собирает все реально установленные части:

- `ApplicationInfo.sourceDir` — base APK;
- все `ApplicationInfo.splitSourceDirs` — split/config APK.

Результат — один файл `*.apks`, который можно передать через Telegram, почту, Quick Share и т. п.

## Формат Aura `.apks`

Это обычный ZIP-контейнер без повторного сжатия уже сжатых APK. Внутри:

- `base.apk`;
- все split APK;
- `META-INF/aura-apks.json`.

Служебный manifest хранит:

- package name;
- label;
- versionName/versionCode;
- данные исходного устройства;
- список APK-частей;
- размер каждой части;
- SHA-256 каждой части.

Формат имеет id `aurafiles-installed-apks` и version `1`.

Важно: это **не universal APK** и не исходный Android App Bundle. Aura сохраняет именно тот набор split APK, который был установлен на исходном устройстве.

## Открытие на другом телефоне

Aura 1.3.0 регистрирует обработчик `.apks` и MIME `application/vnd.aurafiles.apks+zip`.

Сценарий:

1. на телефоне A: `Очистка → Приложения → SPLIT → Поделиться комплектом…`;
2. отправить один `.apks` через Telegram/другое приложение;
3. на телефоне B можно открыть полученный `.apks` прямо из Telegram/файлового списка Aura;
4. если Android/Telegram не предложил Aura как обработчик, открыть `Очистка → Приложения → Установить .APKS` и выбрать файл через системный picker;
5. Aura показывает состав комплекта и предупреждения;
6. нажать **«Установить весь комплект»**;
7. при первом использовании разрешить Aura Files установку неизвестных приложений;
8. Android получает все APK одной `PackageInstaller.Session` и показывает штатное системное подтверждение.

`SplitPackageInstallerActivity` принимает Aura custom MIME как через `ACTION_VIEW`, так и через `ACTION_SEND`; это даёт дополнительный путь «Поделиться → Aura Files» там, где мессенджер сохраняет исходный MIME контейнера.

Пользователь не выбирает `base.apk` или отдельные splits вручную.

## Проверки до установки

Перед созданием Android install-session Aura:

- проверяет собственный manifest контейнера;
- не допускает path traversal в ZIP;
- проверяет число APK и суммарный размер;
- сверяет размер и SHA-256 каждой APK-части;
- читает `base.apk` через PackageManager;
- сверяет package name и versionCode;
- для split APK, которые PackageManager может разобрать отдельно, также сверяет package/version;
- когда signing info доступен для частей, сверяет SHA-256 сертификатов с base APK;
- проверяет minSdk;
- ищет `lib/<abi>/...` внутри APK и блокирует очевидно несовместимый ABI-набор;
- предупреждает о уже установленной той же/более новой версии.

Финальная проверка package/signature/split-согласованности остаётся за Android PackageInstaller.

## Установка

Используется штатный `PackageInstaller.SessionParams(MODE_FULL_INSTALL)`.

Все части записываются в одну install session через `Session.openWrite()`, затем вызываются `fsync()` и один `commit(IntentSender)`.

На Android 12+ явно устанавливается `USER_ACTION_REQUIRED`; на Android 13+ указывается `PACKAGE_SOURCE_LOCAL_FILE`.

Для targetSdk 35+ callback PendingIntent создаётся mutable, потому что Android PackageInstaller добавляет в него `EXTRA_STATUS`/`EXTRA_INTENT`.

Обрабатываются:

- `STATUS_PENDING_USER_ACTION`;
- `STATUS_SUCCESS`;
- BLOCKED / ABORTED / INVALID / CONFLICT / STORAGE / INCOMPATIBLE / generic failure.

## Ограничения

- Комплект device-derived: совместимость с другим телефоном не гарантируется.
- Данные приложения не экспортируются.
- OBB и отдельные Play Asset Delivery asset packs не экспортируются.
- Если на другом устройстве нужна другая ABI-конфигурация или отсутствующий dynamic feature, Aura не может создать его из воздуха.
- Понижение версии Android обычно блокирует.
- `.apks` от bundletool/SAI/других программ пока не заявлены как совместимые: Aura 1.3.0 гарантирует установку собственного формата `aurafiles-installed-apks`.

## Что ещё изменено

- Категория APK теперь распознаёт и `.apks`.
- Встроенный файловый браузер открывает `.apks` сразу через новый SPLIT installer.
- Облачный backend при скачивании `.apks` также передаёт его SPLIT installer Aura, а не внешнему generic ZIP-просмотрщику.
- На странице приложений во время подготовки большого `.apks` кнопка показывает `Готовим…` и блокирует повторный экспорт.
- На странице `Очистка → Приложения` есть явная кнопка **«Установить .APKS»** для выбора полученного файла через системный `OpenDocument`, поэтому установка не зависит от того, умеет ли Telegram правильно передать MIME/расширение во внешний обработчик.
