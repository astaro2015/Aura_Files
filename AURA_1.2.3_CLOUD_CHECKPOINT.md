# Aura Files 1.2.3 CLOUD — checkpoint

Последнее обновление: 2026-09-07

## Текущая база
- Рабочая версия: `1.2.3` / versionCode `123`.
- База cloud-ветки: Aura Files 1.0.4, затем этапы Yandex/Google до 1.2.2.
- 1.2.3 исправляет ошибки, подтверждённые на реальном Android: Android Keystore IV и завершение Google AuthorizationClient.

## Облако
### Яндекс.Диск
- device-code OAuth;
- Client ID + Client Secret;
- Secret хранится только зашифрованно через Android Keystore;
- access/refresh token хранится в отдельном Keystore-backed cloud store;
- автоматический refresh;
- официальный Yandex Disk REST backend;
- обычное открытие — однопанельное;
- двухпанельный режим — отдельный transfer-инструмент.

### Google Drive
- Google Identity Services `AuthorizationClient`;
- Aura не хранит Google refresh token;
- после системного resolution результат проверяется повторным `authorize()` даже при странном/пустом Activity result;
- перед сохранением профиля выполняется реальный Drive `about`;
- официальный Drive REST backend, resumable upload, экспорт Google Workspace документов;
- обычное открытие — однопанельное.

## UI
- «Сеть» содержит четыре верхнеуровневых блока, четвёртый — отдельное «Облако»;
- локальная Корзина Aura — полноэкранный файловый браузер; удалённые папки и файлы можно просматривать;
- отдельные UI-корзины Yandex/Google и Photoslice не входят в план.

## Исправления 1.2.3
- устранён `Caller-provided IV not permitted` в Yandex OAuth secret store;
- тот же дефект устранён в cloud token store и общем network credential store;
- Google callback больше не считает `RESULT_CANCELED` окончательным доказательством отмены;
- при отсутствии корректного returned Intent выполняется silent re-check уже выданного Google scope;
- ошибки Google Play services показывают status code для диагностики.

## Проверки перед упаковкой
См. `PACKAGE_VERIFICATION_1.2.3_CLOUD_AUTH_FIX_RU.txt`.

## Правило продолжения
Продолжать только от полного 1.2.3 source ZIP. Не накладывать отдельные файлы поверх старой версии: Windows builder проверяет `SOURCE_SHA256SUMS.txt`.
