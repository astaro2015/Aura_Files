# Aura Files 1.2.4

Исправления облачной авторизации, Windows signing и интерфейса Корзины.

- Яндекс device-code OAuth больше не обрывается из-за кратковременной NETWORK/SERVER ошибки во время подтверждения в браузере.
- OAuth Яндекса использует `oauth.yandex.com` с fallback на `.ru`; один 5xx также допускает попытку второго официального host.
- Обмен `device_code -> token` и refresh передают Client ID/Secret через HTTP Basic Authorization; Secret отсутствует в form-body и редактируется в диагностике.
- Сохранённый Yandex Client Secret можно повторно использовать после ошибки без нового ввода.
- Google `UNREGISTERED_ON_API_CONSOLE` теперь показывает, что требуется Android OAuth client для package + SHA-1 установленной APK.
- Windows builder заранее создаёт/переиспользует стандартный `%USERPROFILE%\.android\debug.keystore`, поэтому чистые debug-сборки на одном Windows-профиле сохраняют один Google OAuth SHA-1; параметры подключения сохраняются в `BUILD_OUTPUT\GOOGLE_OAUTH_SETUP.txt`.
- `SHOW_GOOGLE_OAUTH_SHA1.bat` может создать debug key и вывести SHA-1 отдельно.
- Сверка с TeamAmaze/LaputaCloudsLib: архитектурные идеи учтены, GPL-код не копировался; рабочая ветка Laputa также зависит от Google Play Services и Android OAuth registration.
- «Облако» — отдельный 4-й пункт в разделе «Сеть»; обычное облако открывается однопанельно, dual-pane остаётся отдельным transfer-инструментом.
- Корзина Aura: список/плитка, сортировка, поиск, массовое выделение, нижние действия Restore/Delete/Empty и нормальное открытие файлов/удалённых папок.
- Внутренняя `.AuraTrash` скрыта из обычного файлового списка даже при включённом показе скрытых файлов.

Windows staging fix:
- исправлен parser SOURCE_SHA256SUMS.txt: принимает стандартные `SHA256  relative/path` и старые `SHA256  ./relative/path`;
- `.github/...` и `.gitignore` больше не считаются ошибкой; абсолютные пути и `..` по-прежнему запрещены;
- устранён сбой `Invalid SOURCE_SHA256SUMS.txt line: ... .github/workflows/android.yml`.
