> **ИСТОРИЧЕСКИЙ ДОКУМЕНТ.** Cloud OAuth из этой версии заменён исправлением 1.2.2. Для Яндекса актуальный flow требует Client ID + Client Secret; для Google актуальна диагностика Android OAuth package/SHA-1 и обязательная проверка Drive API.

# Aura Files 1.2.1 CLOUD — checkpoint

Последнее обновление: 2026-09-06

## Версия
- `versionName 1.2.1 / versionCode 121`.
- База исправления: полный `Aura_Files_1.2.0_CLOUD_STAGE5_FINAL_source.zip`.
- 1.2.1 — bugfix cloud OAuth/открытия backend после реального теста на Android.

## Яндекс.Диск
- Device-code OAuth соответствует текущей схеме Яндекс OAuth: после ввода `user_code` Aura продолжает polling по `device_code`.
- Pending OAuth session (`clientId`, `device_code`, `user_code`, URL, interval, expiry) сохраняется через `SavedStateHandle`.
- При системном пересоздании Activity/процесса вход не начинается заново: восстанавливается тот же код и продолжается polling до expiry.
- После восстановления браузер не открывается повторно автоматически; пользователь остаётся в Aura и видит текущий код/статус.
- При успешной выдаче токена pending session очищается, backend регистрируется и сразу выбирается в облачной панели.
- Access/refresh tokens по-прежнему хранятся только в AES-GCM + Android Keystore; pending device-code живёт только как краткоживущее saved UI state.

## Google Drive
- Состояние незавершённого `AuthorizationClient` flow сохраняется через `SavedStateHandle`.
- Результат системного Google окна больше не отбрасывается после пересоздания Activity/ViewModel.
- После успешной авторизации Google backend регистрируется и атомарно выбирается в левой/верхней панели; локальная память остаётся во второй панели.
- Первый выданный access token передаётся backend через process-local memory cache, чтобы первое чтение Drive не инициировало лишний второй запрос авторизации.
- Google access/refresh tokens на диск Aura не записывает. Cache очищается при invalidate/revoke и исчезает вместе с процессом.
- При регистрации backend проверяется, что облачный backend действительно присутствует и выбран; молчаливый возврат к двум локальным панелям не считается успехом.

## Cloud backend
- Яндекс и Google используют общий `StorageBackend` / `BackendTransferCore`.
- Photoslice не входит в план.
- Отдельные UI-представления облачной корзины не входят в план.

## Проверки 1.2.1
- Яндекс OAuth request format сверён с актуальной документацией: `grant_type=device_code`, `code=<device_code>`.
- `SavedStateHandle`/`SavedStateViewModelFactory` сверены с AndroidX Lifecycle 2.10 API.
- Google core + GIS wrapper recompilation со строгими API-stubs: OK.
- Local-like → Google transfer smoke: OK (`completed=1`, `invalidations=1`, `expectedSize=12`).
- Google duplicate-name guard smoke: OK.
- Kotlin PSI syntax scan текущих исходников: OK.
- Полный Android Gradle build 1.2.1 должен быть подтверждён на Windows через `BUILD_ON_CLEAN_WINDOWS.bat`; исходная 1.0.4 ранее подтверждённо доходила до `assembleDebug`.

## Следующее действие
На Android проверить два сценария без изменения кода:
1. Яндекс: получить код → открыть сайт → подтвердить → вернуться в Aura; должен остаться тот же pending flow и затем открыться Яндекс.Диск.
2. Google: выбрать аккаунт/разрешить Drive → вернуться; левая/верхняя панель должна стать Google Drive, вторая — локальной.
