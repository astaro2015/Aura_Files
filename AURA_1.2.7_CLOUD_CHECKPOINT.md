# Aura Files 1.2.7 CLOUD — checkpoint

Дата: 2026-09-07

## База

Продолжает 1.2.6 без изменения cloud/OAuth архитектуры.

- `versionName = "1.2.7"`
- `versionCode = 127`
- package: `com.aurafiles.app`

## Новое в 1.2.7

Локальный раздел «Изображения» переведён на полноценный индексный каталог:

- общий поток по дате;
- фильтры Все / Камера / Снимки экрана / WhatsApp / Telegram / Загрузки / Другие;
- SQL-поиск и SQL-сортировка по всей выбранной коллекции;
- постраничная загрузка по 400 файлов без старого потолка 5000;
- `Показать ещё` отображает прогресс `загружено / всего`;
- `FileCategory.Images` исключена из фонового category warm-cache, чтобы большие фототеки не грузились в память заранее.

Подробности: `AURA_1.2.7_IMAGES_CATALOG_CHANGES_RU.md`.

## Cloud состояние, унаследованное от 1.2.6

- Яндекс.Диск и Google Drive открываются в стандартном визуальном языке Aura через `StorageBackend`.
- В cloud-header сверху только Назад + название; Обновить / 2 панели / Создать папку находятся во второй строке.
- `BackendWorkspaceActivity` учитывает status/navigation safe areas.
- Яндекс удаляет в корзину провайдера (`permanently=false`).
- Dual-pane остаётся отдельным межхранилищным transfer-режимом.
- Неподдерживаемые локальные действия не показываются в cloud UI.

## Проверки перед упаковкой

- Kotlin/KTS PSI parse всего дерева.
- Android XML parse.
- Java syntax check изменённого DAO.
- SQLite smoke-test новых image-filter/page/sort запросов.
- `SOURCE_SHA256SUMS.txt` пересчитать самым последним шагом.

Полный Gradle build в среде ChatGPT может не запускаться, если `services.gradle.org` недоступен. Финальную Android-компиляцию подтверждать Windows builder'ом.
