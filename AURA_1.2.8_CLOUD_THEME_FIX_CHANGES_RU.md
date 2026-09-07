# Aura Files 1.2.8 — cloud theme/content color fix

Дата: 2026-09-07

## Причина

Однопанельный `BackendWorkspaceActivity` был построен на корневом `Column` непосредственно внутри `MaterialTheme`. `MaterialTheme` сам по себе не задаёт `LocalContentColor` для произвольного `Column`, поэтому `Text`/`Icon`, у которых цвет не был указан явно, могли получить дефолтный чёрный цвет. На светлой теме это было незаметно, на тёмной заголовок и часть кнопок выглядели отключёнными.

## Исправление

- рабочая область backend-экрана обёрнута в `Surface(background, onBackground)`;
- системные safe-area padding остаются внутри Surface, поэтому фон покрывает весь экран;
- заголовок и Back имеют явный `onBackground`;
- Refresh / 2 панели / Add имеют явный активный `onSurface`, а при `busy` — 38% alpha;
- `2 панели` больше не использует primary-синий только из-за того, что реализована через `TextButton`;
- в корне cloud backend верхний заголовок — имя провайдера, а descriptor/profile остаётся в breadcrumb-строке.

## Не менялось

OAuth, Яндекс.Диск API, Google Drive API, SMB/FTP/SFTP, операции копирования/перемещения/удаления и Images-каталог 1.2.7 не изменялись.

Версия: `1.2.8` / `versionCode 128`.
