# Подключение облаков — Aura Files 1.2.4

## Яндекс.Диск

1. В Yandex OAuth создай приложение.
2. Разреши Yandex Disk REST API на чтение и запись.
3. В Aura введи **Client ID** и **Client Secret** приложения.
4. Aura запросит device-code, скопирует user-code и откроет страницу Яндекса.
5. На странице введи код и разреши доступ.
6. Вернись в Aura. Автоматического redirect в приложение у device-code flow не требуется: Aura продолжает polling сама.

В 1.2.4 обмен `device_code -> token` передаёт Client ID/Secret через HTTP Basic Authorization. Secret не кладётся в form-body и не выводится в диагностических строках.

Если сеть/VPN/оператор кратковременно мешает OAuth во время браузерного подтверждения, Aura не сбрасывает вход. Она пробует оба официальных OAuth host (`oauth.yandex.com` и `.ru`) и повторяет NETWORK/SERVER ошибки до истечения device-code.

## Google Drive — почему Cx/Amaze входят «просто», а самосборная Aura требует один шаг

У опубликованных файловых менеджеров разработчик заранее зарегистрировал **свой** Android OAuth client для package и сертификата, которым он подписывает все APK. Поэтому пользователь этого не видит.

Aura сейчас собирается у тебя локально. Google идентифицирует Android-приложение парой:

- package: `com.aurafiles.app`
- SHA-1 сертификата установленной APK.

Без регистрации этой пары Google Play services возвращает `UNREGISTERED_ON_API_CONSOLE`. Код файлового менеджера не может честно обойти эту серверную проверку.

### Что сделано в Aura 1.2.4

`BUILD_ON_CLEAN_WINDOWS.bat` заранее создаёт или переиспользует стандартный debug key:

`%USERPROFILE%\.android\debug.keystore`

Android Gradle Plugin использует этот же key для `assembleDebug`. Он находится **вне распакованного source**, поэтому новая чистая папка Aura на том же Windows-профиле сохраняет тот же SHA-1.

Сборщик печатает package и SHA-1 в консоль. Их можно получить отдельно через:

`SHOW_GOOGLE_OAUTH_SHA1.bat`

Скрипт при необходимости сам создаст debug key и покажет SHA-1 ещё до сборки APK.

### Настройка Google Cloud — один раз для debug-сборок на этом ПК

В одном Google Cloud project:

1. APIs & Services / Library -> включить **Google Drive API**.
2. Google Auth Platform / OAuth consent screen:
   - настроить Branding/Audience;
   - если приложение в Testing, добавить свой Google-аккаунт в Test users;
   - в Data Access добавить scope `https://www.googleapis.com/auth/drive`.
3. Clients / Credentials -> Create OAuth client -> **Android**.
4. Package: `com.aurafiles.app`.
5. SHA-1: значение из `SHOW_GOOGLE_OAUTH_SHA1.bat` или из ошибки Aura.
6. Сохранить и подождать несколько минут.
7. В Aura снова выбрать `Сеть -> Облако -> Google Drive`.

Если позже подпишешь release APK другим ключом, для его SHA-1 нужен отдельный Android OAuth client.

## Почему не перенесён Google OAuth из Amaze/Laputa

Изучен открытый `TeamAmaze/LaputaCloudsLib`:

- его рабочая non-FOSS Google-реализация сама использует Google Play Services и Android OAuth registration;
- FOSS-вариант использует WebView OAuth, но его code-exchange в репозитории не закончен и это не подход, который стоит переносить в современный Android;
- архитектурную идею «auth отдельно, cloud filesystem отдельно» Aura использует, но GPL-код не копируется.

## Корзина Aura

Корзина локальная, не облачная. В 1.2.4 она имеет:

- список/плитку;
- сортировку и поиск;
- вход в удалённые папки;
- открытие фото/видео/книг/архивов/обычных файлов;
- долгое нажатие и множественное выделение на корне;
- нижнюю панель `Восстановить / Удалить / Очистить` по контексту.

Внутренняя папка `.AuraTrash` больше не показывается в обычном файловом браузере даже при включённом отображении скрытых файлов.
