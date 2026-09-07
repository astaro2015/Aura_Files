> **ИСТОРИЧЕСКИЙ ДОКУМЕНТ.** Cloud OAuth из этой версии заменён исправлением 1.2.2. Для Яндекса актуальный flow требует Client ID + Client Secret; для Google актуальна диагностика Android OAuth package/SHA-1 и обязательная проверка Drive API.

# Aura Files 1.2.1 — Cloud OAuth auth-fix

Исправления после реального теста 1.2.0 на Android.

## Яндекс
- Исправлен сброс device-code входа после перехода в браузер/возврата в приложение.
- Pending device-code flow сохраняется через Android `SavedStateHandle`.
- После Activity/process recreation polling продолжается с тем же `device_code`.
- Автоматическое открытие страницы Яндекс OAuth выполняется только один раз для нового кода.
- Повторный запуск Activity по исходному Intent больше не должен создавать второй device-code flow.
- После успешного входа backend регистрируется и выбирается в панели одним атомарным rebuild.

## Google Drive
- Исправлена потеря результата `AuthorizationClient` после пересоздания Activity/ViewModel.
- Флаг незавершённой Google-авторизации сохраняется в `SavedStateHandle`.
- Валидный ActivityResult обрабатывается даже после восстановления ViewModel.
- После входа Google Drive сразу выбирается в облачной панели, локальное хранилище — во второй.
- Добавлен process-only `GoogleAccessTokenMemoryCache`: первый полученный токен используется для немедленного открытия Drive без повторного auth round-trip; на диск токен не сохраняется.
- Cache очищается при 401/invalidate и revoke.

## Дополнительно
- Добавлена явная зависимость `lifecycle-viewmodel-savedstate:2.10.0`.
- One-shot флаги автозапуска подключения в Compose переведены на `rememberSaveable`.
- Версия поднята до 1.2.1 / 121.
