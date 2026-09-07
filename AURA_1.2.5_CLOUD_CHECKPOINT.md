# Aura Files 1.2.5 CLOUD — checkpoint

Последнее обновление: 2026-09-07

## Версия
- versionName: `1.2.5`
- versionCode: `125`
- Основа: Aura Files 1.2.4 COMPILE FIX.

## Главное изменение 1.2.5
- Однопанельный Яндекс.Диск/Google Drive визуально приведён к стандартному файловому браузеру Aura.
- UI не переводит облако на `DocumentFile`/`Uri`: все реальные операции продолжают идти через `StorageBackend`.
- Облачный clipboard использует `BackendTransferCore`.
- Неподдерживаемые локальные действия в облаке намеренно скрыты.
- Удаление Яндекс.Диска описывается как перенос в корзину провайдера; backend использует `permanently=false`.
- Dual-pane остаётся отдельным режимом для межхранилищного transfer.

## Перед продолжением
1. `AURA_1.2.5_STANDARD_CLOUD_UI_CHANGES_RU.md`
2. `RELEASE_NOTES_1.2.5_RU.md`
3. `AURA_1.2.4_CLOUD_CHECKPOINT.md`
4. `CLOUD_SETUP_1.2.4_RU.md` — OAuth-настройка не изменилась.

После любых изменений пересчитывать `SOURCE_SHA256SUMS.txt` последним шагом.
