# Aura Files 1.2.8 CLOUD — checkpoint

Дата: 2026-09-07

- `versionName = "1.2.8"`
- `versionCode = 128`
- package: `com.aurafiles.app`

Продолжает 1.2.7. Каталог изображений с фильтрами и постраничной Room-выборкой сохранён без изменений.

## Новое в 1.2.8

Исправлен theme/content-color backend workspace на тёмной теме: корневая рабочая область получает `background/onBackground` через Material3 `Surface`; активные заголовки и toolbar-actions больше не выглядят disabled. `Обновить / 2 панели / Создать` имеют единый нейтральный активный цвет. В корне Яндекс.Диска/Google Drive верхний заголовок больше не дублирует полное имя профиля из breadcrumb.

Подробности: `AURA_1.2.8_CLOUD_THEME_FIX_CHANGES_RU.md`.

Перед следующими изменениями также читать:
- `AURA_1.2.7_IMAGES_CATALOG_CHANGES_RU.md`
- `AURA_1.2.6_CLOUD_HEADER_FIX_CHANGES_RU.md`
- `AURA_1.2.5_STANDARD_CLOUD_UI_CHANGES_RU.md`

После изменений пересчитывать `SOURCE_SHA256SUMS.txt` последним шагом.
