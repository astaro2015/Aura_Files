# Aura Files 1.2.3 — исправления cloud OAuth

Дата: 2026-09-07

## Исправлено: Android Keystore AES-GCM

На реальном Android при сохранении Yandex OAuth Client Secret версия 1.2.2 показывала:
`Caller-provided IV not permitted`.

Причина: ключ Android Keystore создан с требованием randomized encryption, но код сам генерировал IV и передавал его в `Cipher.init(ENCRYPT_MODE, key, GCMParameterSpec(...))`.

Исправлено во всех затронутых хранилищах:
- `YandexOAuthSecretStore`;
- `CloudTokenStore`;
- `CredentialStore`.

Теперь при шифровании используется `cipher.init(ENCRYPT_MODE, key)`, IV генерирует провайдер Android Keystore, затем Aura сохраняет `cipher.iv` вместе с ciphertext. При расшифровке сохранённый IV по-прежнему передаётся через `GCMParameterSpec`.

Формат payload `IV + ciphertext` не изменён, поэтому корректно сохранённые старые записи остаются совместимыми.

## Исправлено: завершение Google AuthorizationClient

На некоторых устройствах/версиях Google Play services системное окно выбора/разрешения Google могло вернуться с `RESULT_CANCELED` либо без полезного Intent даже после действий пользователя. Версия 1.2.2 считала это отменой и не создавала Google Drive backend.

Новый порядок:
1. Aura пытается разобрать `AuthorizationResult` из returned Intent независимо от resultCode.
2. Если результата/токена нет, Aura обязательно делает повторный `AuthorizationClient.authorize()` без показа нового окна.
3. Если scope уже выдан — Google возвращает access token, после чего Aura проверяет `Drive about`, сохраняет профиль и открывает конкретный `google:<profileId>`.
4. Только если повторная проверка снова требует resolution, вход считается незавершённым.
5. В диагностике сохраняется фактический `resultCode`; `ApiException` Google Play services теперь показывает status code.

## Версия
- versionName: `1.2.3`
- versionCode: `123`
