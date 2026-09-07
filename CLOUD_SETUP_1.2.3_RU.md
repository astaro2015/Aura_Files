# Подключение облаков в Aura Files 1.2.3

## Яндекс.Диск
1. Создай OAuth-приложение Яндекса с правами Yandex Disk REST API на чтение и запись.
2. В Aura открой `Сеть` → `Облако` → `Добавить Яндекс.Диск`.
3. Введи Client ID и Client Secret OAuth-приложения.
4. Aura получит device-code, откроет страницу Яндекса и покажет/скопирует user-code.
5. Введи код на странице Яндекса и разреши доступ.
6. Вернись в Aura. Автоматический redirect обратно не требуется: Aura продолжает polling и сама получает token.

Client Secret и OAuth-токены не хранятся открытым текстом; они шифруются ключами Android Keystore.

## Google Drive
1. В Google Cloud включи Google Drive API.
2. Настрой OAuth consent screen / Data Access с нужным Drive scope; для Testing добавь используемый аккаунт в Test users.
3. Создай Android OAuth client для package `com.aurafiles.app` и SHA-1 сертификата установленной сборки.
4. SHA-1 debug-сборки можно получить `SHOW_GOOGLE_OAUTH_SHA1.bat`.
5. В Aura: `Сеть` → `Облако` → `Добавить Google Drive`, выбери аккаунт и разреши доступ.
6. Aura проверит разрешение реальным запросом Drive API и только после этого сохранит подключение.

В 1.2.3 Aura дополнительно перепроверяет уже выданное разрешение через `AuthorizationClient`, даже если системное окно вернуло пустой/необычный Activity result.
