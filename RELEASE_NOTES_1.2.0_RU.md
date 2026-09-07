> **ИСТОРИЧЕСКИЙ ДОКУМЕНТ.** Cloud OAuth из этой версии заменён исправлением 1.2.2. Для Яндекса актуальный flow требует Client ID + Client Secret; для Google актуальна диагностика Android OAuth package/SHA-1 и обязательная проверка Drive API.

# Aura Files 1.2.0 — Яндекс.Диск + Google Drive

Дата: 2026-09-06

Главное изменение 1.2.0 — полноценные облачные backend'ы в универсальных панелях Aura Files.

## Яндекс.Диск
- официальный Yandex Disk REST backend;
- device-code OAuth без ручного ввода пользовательского токена;
- access/refresh tokens защищены AES-GCM + Android Keystore;
- автоматический refresh и ротация refresh-token;
- Client ID сохраняется вместе с профилем, которому он принадлежит;
- один принудительный refresh/retry после HTTP 401;
- просмотр, скачивание, загрузка, папки, переименование, перемещение и обычное удаление в корзину провайдера;
- fixed-length upload при известном размере;
- backend работает через общий `StorageBackend` / `BackendTransferCore`.

## Google Drive
- нативная авторизация через Google Identity Services `AuthorizationClient`;
- Aura не хранит пароль или Google refresh-token;
- Google Drive API интегрирован как полноценный `StorageBackend`;
- просмотр, скачивание, загрузка, папки, rename/move/delete;
- resumable upload с фиксированным `Content-Length`;
- неизвестный размер безопасно спуливается во временный app-private файл перед отправкой;
- Google Docs/Sheets/Slides/Drawings экспортируются как DOCX/XLSX/PPTX/PDF;
- реальные `fileId` защищают операции от путаницы при одинаковых именах;
- неоднозначная операция по одному имени останавливается вместо выбора случайного файла;
- после 401 локальный access-token cache очищается и выполняется один повтор;
- при удалении Google-профиля Aura пытается отозвать выданный ей Drive scope.

## Общий transfer core
- Local / SMB / FTP / SFTP / Яндекс.Диск / Google Drive используют единый backend transfer path;
- безопасная временная запись `.aura-part-*` и conflict handling сохранены;
- размер источника передаётся backend'у для корректного fixed-length cloud upload;
- compare/sync защищён от одинаковых относительных имён.

## Версия
- `versionName: 1.2.0`
- `versionCode: 120`

## Сборка Yandex OAuth
Для пользовательской сборки рекомендуется зарегистрировать OAuth-приложение Яндекса с правами Yandex Disk REST API и передать публичный Client ID:

```powershell
$env:AURA_YANDEX_CLIENT_ID="<client_id>"
.\gradlew.bat assembleRelease
```

Если build-time Client ID отсутствует, Aura позволяет ввести его при первом подключении Яндекс.Диска. Client Secret в приложении не хранится.

## Намеренно не входит
- Photoslice;
- отдельные экраны облачных корзин;
- OCR;
- хранение Google refresh-token в Aura.

Обычное удаление cloud-файла остаётся восстановимым через корзину самого провайдера.
