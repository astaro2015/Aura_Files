> **ИСТОРИЧЕСКИЙ ДОКУМЕНТ.** Актуальная версия — 1.2.3; исправлены Android Keystore IV и завершение Google AuthorizationClient.

# Aura Files 1.2.2 CLOUD — checkpoint

Последнее обновление: 2026-09-07

## Версия
- `versionName 1.2.2 / versionCode 122`.
- База исправления: полный `Aura_Files_1.2.1_CLOUD_AUTH_FIX_source.zip`.
- 1.2.2 исправляет фактические проблемы подключения, обнаруженные при ручном тесте на Android.

## Яндекс.Диск
- Исправлена ключевая ошибка предыдущих версий: Aura просила только Client ID, хотя для обмена `device_code` на token Yandex OAuth может требовать Client Secret приложения.
- UI теперь принимает **Client ID + Client Secret / пароль приложения**.
- Client Secret не хранится открыто: отдельное AES-GCM хранилище, ключ Android Keystore, AAD привязан к client ID.
- Хранилище secret исключено из Android Backup/device transfer.
- Pending device-code flow по-прежнему переживает пересоздание Activity через SavedStateHandle.
- После успешной выдачи token профиль сохраняется, backend регистрируется, затем workspace запускается заново уже с `yandex:<profileId>`.

## Google Drive
- После системного окна Google результат разбирается через `getAuthorizationResultFromIntent`.
- Если result code успешный, но Intent не содержит пригодного AuthorizationResult, Aura один раз повторно проверяет уже выданный scope без запуска второго окна.
- После успешного `about()` профиль сохраняется, backend регистрируется, затем workspace запускается заново с `google:<profileId>`.
- Если Google возвращает `DEVELOPER_ERROR`, Aura показывает package `com.aurafiles.app` и SHA-1 сертификата установленного APK.
- Ошибки Drive API 403 показывают provider message, чтобы было видно выключенный API/неправильный consent scope.
- Google token по-прежнему не хранится на диске Aura.

## Диагностика вместо тихого fallback
- Ошибки регистрации cloud backend больше не проглатываются.
- Если сохранённый backend не зарегистрирован, Aura показывает явное сообщение, а не молча оставляет две Local-панели.
- `openBackendFromNetwork()` больше не делает silent return для отсутствующего backend.


## Интерфейс облаков и корзины
- В «Сеть» облака вынесены в отдельный **4-й верхнеуровневый пункт «Облако»**.
- Обычный вход в сохранённый Яндекс.Диск/Google Drive открывает **однопанельный** файловый браузер, визуально ближе к основному Aura.
- Двухпанельный `BackendWorkspace` остаётся отдельным инструментом для копирования/перемещения между хранилищами и включается командой «Двухпанельный режим».
- В однопанельном cloud-браузере короткий тап по папке открывает её, по файлу — временно скачивает файл в app-private cache и открывает штатным механизмом Android/Aura.
- Выделение в однопанельном cloud-браузере начинается долгим нажатием; постоянные чекбоксы убраны.
- Локальная корзина больше не является только диалогом метаданных: содержимое реальной `.AuraTrash` можно просматривать как дерево папок, открывать вложенные папки и файлы.
- Для корневых объектов корзины сохранены действия «Восстановить» и «Удалить навсегда», для всей корзины — «Очистить».

## Google Cloud setup
- Для локально собранного APK нужен Android OAuth client в Google Cloud, привязанный к package `com.aurafiles.app` и SHA-1 сертификата этой сборки.
- В проекте должен быть включён Google Drive API и настроен OAuth consent/Data Access для Drive scope.
- Инструкция: `CLOUD_SETUP_1.2.2_RU.md`.
- Debug SHA-1 helper: `SHOW_GOOGLE_OAUTH_SHA1.bat`.

## Что не входит
- Photoslice.
- Отдельные UI-представления облачной корзины.

## Проверки, выполненные в рабочей среде
- Yandex settings + encrypted secret store отдельно скомпилированы `kotlinc` со строгими Android API-stubs: OK.
- Google GIS wrapper отдельно скомпилирован `kotlinc` со строгими API-stubs: OK.
- Yandex token-exchange smoke подтвердил фактическую форму запроса: `client_id`, `client_secret`, `grant_type=device_code`, `code=device_code`: OK.
- Все Kotlin/KTS-файлы текущего дерева проверены Kotlin PSI/compiler front-end: 120 файлов, 0 syntax/parse ошибок.
- Все Android XML текущего дерева проверены XML parser: 14 файлов, 0 ошибок.
- Полный Gradle build в этой среде не запускается из-за отсутствия доступа к `services.gradle.org`; финальный Android compile должен быть подтверждён на Windows.
