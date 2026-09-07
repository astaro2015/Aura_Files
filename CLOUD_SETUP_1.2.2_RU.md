> **ИСТОРИЧЕСКИЙ ДОКУМЕНТ.** Для текущей версии используй `CLOUD_SETUP_1.2.3_RU.md`; в 1.2.3 исправлены Android Keystore IV и завершение Google AuthorizationClient.

# Aura Files 1.2.2 — настройка облаков

## Яндекс.Диск

1. Откройте Яндекс OAuth и создайте приложение типа **«Для доступа к API или отладки»**.
2. Добавьте права **Yandex Disk REST API** на чтение и запись файлов.
3. После создания приложения откройте его свойства и скопируйте:
   - **Client ID**;
   - **Client Secret / пароль приложения**.
4. В Aura Files откройте **Сеть → Яндекс.Диск**.
5. Введите Client ID и Client Secret и нажмите **«Войти через Яндекс»**.
6. Aura получит `user_code`, скопирует его и откроет страницу Яндекс OAuth.
7. Введите код на странице и разрешите доступ. Затем **вернитесь в Aura**. Device-code OAuth не обязан перенаправлять браузер обратно в приложение: Aura продолжает опрашивать `/token` и сама завершает вход.
8. Успехом Aura считает только получение OAuth-токена, реальный запрос к Yandex Disk REST API и регистрацию `YandexDiskStorageBackend`.

Client Secret сохраняется на устройстве отдельно от профиля: AES-GCM, ключ — Android Keystore, AAD привязан к Client ID. Это хранилище исключено из Android Backup.

Если Яндекс пишет `invalid_client`, перепроверьте **оба** значения. Если получен `403`, проверьте права Yandex Disk REST API у OAuth-приложения.

## Google Drive

Для полноценного файлового менеджера Aura запрашивает scope `https://www.googleapis.com/auth/drive`. Одного окна выбора Google-аккаунта недостаточно — OAuth должен быть настроен для **конкретной подписи APK**.

1. Создайте или выберите проект в Google Cloud Console.
2. **Включите Google Drive API** для этого проекта.
3. Настройте Google Auth Platform / OAuth consent screen:
   - Branding / Audience;
   - в **Data Access** добавьте scope `https://www.googleapis.com/auth/drive`;
   - если приложение в режиме Testing, добавьте свой Google-аккаунт в **Test users**.
4. Создайте OAuth Client ID типа **Android**:
   - Package name: `com.aurafiles.app`;
   - SHA-1: отпечаток сертификата, которым подписан именно устанавливаемый APK.
5. Для debug APK SHA-1 можно узнать запустив `SHOW_GOOGLE_OAUTH_SHA1.bat` или команду `gradlew.bat signingReport`.
6. В Aura Files выберите **Google Drive** и подтвердите системное окно Google.
7. После разрешения Aura делает реальный запрос `Drive API /about`. Только после его успеха профиль сохраняется и workspace заново открывается с `google:<profileId>`.

Если Google вернёт `DEVELOPER_ERROR`, Aura показывает package name и SHA-1 установленного APK. Сверьте их с Android OAuth client в Google Cloud. Если Drive API не включён или scope не разрешён, Aura теперь показывает ошибку вместо двух локальных панелей.

### Важно про SHA-1

Debug и release APK могут быть подписаны разными сертификатами. Для каждого реально используемого сертификата нужен соответствующий Android OAuth client (package name одинаковый, SHA-1 разный).
