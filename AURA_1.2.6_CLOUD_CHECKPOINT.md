# Aura Files 1.2.6 CLOUD — checkpoint

Дата: 2026-09-07

Текущая рабочая версия после унификации cloud UI и исправления расположения cloud-toolbar.

Ключевые правила для продолжения:

1. Яндекс.Диск и Google Drive открываются в однопанельном файловом UI Aura, но операции выполняются через `StorageBackend`.
2. Не показывать cloud-провайдерам локальные действия, для которых нет backend-реализации.
3. Верхний cloud-header: только `Назад` + название.
4. Во второй строке backend breadcrumbs справа: `Обновить`, явная кнопка `2 панели`, `Создать папку`.
5. `BackendWorkspaceActivity` обязан учитывать status/navigation bar safe areas Android.
6. Во время активной передачи действия, способные сменить экран/каталог или начать конфликтующую операцию, блокируются.
7. Яндекс delete остаётся удалением в корзину провайдера (`permanently=false`).
8. Dual-pane сохраняется для transfer между local/network/cloud backend'ами.
9. Photoslice и отдельный cloud-trash UI не добавлять без отдельной задачи.

Перед следующими изменениями читать также:
- `AURA_1.2.5_STANDARD_CLOUD_UI_CHANGES_RU.md`
- `AURA_1.2.4_AUTH_NETWORK_TRASH_FIXES_RU.md`
- `CLOUD_SETUP_1.2.4_RU.md`
