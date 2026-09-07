# Aura Files 1.2.0 CLOUD — Stage 5: аудит + Google Drive

Дата: 2026-09-06

Stage 5 не просто добавляет Google Drive: перед интеграцией выполнена повторная ревизия Stage 1–4 и исправлены найденные фактические ошибки.

## Исправления Яндекс.Диска
- `expectedSize` теперь действительно используется: при известном размере upload получает fixed-length streaming.
- Yandex OAuth Client ID сохранён в конкретном `CloudProfile`, чтобы старый refresh-token не ломался после смены build-time/default Client ID.
- `hasStoredTokens()` возвращает true только для реально расшифровываемого token blob.
- удаление профиля и токенов имеет rollback токенов при ошибке сохранения metadata.
- ожидание серверных операций переведено с `Thread.sleep` на coroutine `delay`.

## Google Drive
- добавлен `StorageBackendKind.GOOGLE_DRIVE`;
- добавлен `CloudProvider.GOOGLE_DRIVE` в factory/registry/UI;
- используется Google Identity Services `AuthorizationClient`;
- приложение запрашивает Drive scope `https://www.googleapis.com/auth/drive`;
- системный `PendingIntent` используется для выбора аккаунта/разрешения;
- Google access token не сохраняется в `CloudTokenStore`;
- после HTTP 401 token cache очищается через `clearToken`, затем выполняется один повтор;
- при удалении профиля выполняется best-effort `revokeAccess` для сохранённого аккаунта.

## GoogleDriveStorageBackend
Реализованы:
- `list`;
- `stat`;
- `openRead`;
- `openWrite`;
- `mkdir`;
- `rename`;
- `move`;
- `delete` (в корзину Google Drive);
- `ping`.

Backend использует реальный Google `fileId` для существующих объектов. Это важно, потому что Google Drive допускает одинаковые provider names.

## Дубли имён
- список может отображать реальные внешние дубли Google Drive;
- каждый выбранный объект сохраняет собственный `fileId`-path;
- операция, которая пришла только с именем и оказалась неоднозначной, завершается понятной ошибкой;
- compare/sync не кладёт два одинаковых относительных имени в одну map silently, а останавливается с ошибкой.

## Upload/download
- resumable upload session создаётся через Drive API;
- известный размер передаётся как fixed `Content-Length`;
- при неизвестном размере поток сначала пишется во временный файл app cache, затем отправляется с измеренным размером;
- временный файл удаляется при commit/abort/close;
- Google Workspace документы экспортируются в распространённые форматы:
  - Docs → `.docx`;
  - Sheets → `.xlsx`;
  - Slides → `.pptx`;
  - Drawings → `.pdf`.

## UI
- Google Drive добавлен в карточку «Облака»;
- добавлен отдельный `+ Google Drive`;
- аккаунт после авторизации сразу регистрируется и открывается в универсальных панелях;
- повторное подключение того же аккаунта обновляет существующий профиль;
- удаление Google профиля одинаково обрабатывается и в основном экране, и в workspace.

## Дополнительная ревизия старого кода
На основе предупреждений из реальной Windows-сборки 1.0.4 вычищены несколько старых warnings: deprecated directional Compose icons, redundant safe-call/conversion/projection и часть deprecated Commons Compress iteration API.

## Не входит
- Photoslice;
- отдельные облачные экраны корзины;
- permanent delete по обычной кнопке удаления;
- хранение Google refresh-token приложением.
