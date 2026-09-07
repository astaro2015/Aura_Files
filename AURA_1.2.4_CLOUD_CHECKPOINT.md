# Aura Files 1.2.4 CLOUD — checkpoint

Последнее обновление: 2026-09-07

## Версия
- versionName: `1.2.4`
- versionCode: `124`
- Основа: полный source Aura Files 1.2.3.

## Cloud
- Яндекс.Диск: официальный device-code OAuth + Client ID/Secret, защищённое хранение Secret/token через Android Keystore, полноценный StorageBackend.
- Google Drive: Google Identity Services `AuthorizationClient` + полноценный StorageBackend.
- Отдельные UI-корзины облаков и Photoslice не входят в план.
- «Облако» — отдельный 4-й пункт внутри «Сеть».
- Обычный cloud browsing — однопанельный; dual-pane остаётся отдельным инструментом переноса.

## Что уточнено после проверки Cx/Amaze/Laputa
- Cx File Explorer закрыт; официальный исходный код недоступен.
- TeamAmaze/LaputaCloudsLib изучен как архитектурный reference, без копирования GPL-кода.
- Его рабочая Google-ветка также использует Google Play Services и не обходит Android OAuth package+SHA-1 registration.
- WebView OAuth из FOSS flavor Laputa неполон и не выбран для Aura.
- Поэтому Aura сохраняет современный `AuthorizationClient`, но делает самосборку предсказуемой через стабильный debug signing key на Windows.

## Исправления 1.2.4
- Yandex polling переживает NETWORK/SERVER ошибки и не сбрасывает подтверждённый device-code.
- Yandex OAuth использует `oauth.yandex.com` с fallback на `.ru`; один 5xx также допускает попытку второго официального host.
- Yandex `/token` и refresh используют HTTP Basic `client_id:client_secret`; Secret отсутствует в form-body.
- Google `UNREGISTERED_ON_API_CONSOLE` показывает package/SHA-1 и точную причину.
- Windows builder гарантирует постоянный `%USERPROFILE%\.android\debug.keystore` для debug APK на одном профиле, печатает SHA-1 и пишет `BUILD_OUTPUT\GOOGLE_OAUTH_SETUP.txt`.
- Корзина получила list/grid, сортировку, поиск, выделение и нижнюю панель Restore/Delete/Empty.
- `.AuraTrash` скрыта из обычного браузера Aura даже при show hidden.

## Проверки перед упаковкой
- PSI syntax scan всего Kotlin/KTS дерева.
- Android XML parse.
- Yandex OAuth smoke из текущих исходников: Basic auth, Secret redaction, `.com` primary.
- Google `AuthorizationClient` wrapper — отдельная `kotlinc`-компиляция со строгими API-stubs.
- SOURCE_SHA256SUMS генерировать только самым последним шагом.
