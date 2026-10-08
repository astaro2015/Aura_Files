# Aura Files 1.3.9 — APK share chooser fix

Дата: 2026-09-16
Версия: `1.3.9` / `versionCode 139`
База: `1.3.8_ANDROID_APKSIG_RUNTIME_FIX`.

## Runtime symptom

На устройстве единый APK уже мог успешно собраться и подписаться, показывался диалог «APK готов», но после нажатия «Поделиться» системный список приложений не появлялся: пользователь оставался в Aura без отправки файла.

## Root cause / compatibility issue

Исходящий `ACTION_SEND` был жёстко привязан к MIME `application/vnd.android.package-archive` и сразу передавался в `Intent.createChooser(...)`. Такой MIME не гарантирует наличие внешнего SEND-handler на конкретной прошивке. Сам запуск chooser не является достаточной проверкой того, что есть пригодный внешний получатель.

## Fix

- добавлен `ApkShareIntentPolicy.mimeCandidates`;
- порядок: APK MIME → `application/octet-stream` → `*/*`;
- перед chooser выполняется `PackageManager.queryIntentActivities(..., MATCH_DEFAULT_ONLY)`;
- выбирается первый MIME, где найден хотя бы один receiver не из package самой Aura;
- `FileProvider` URI, `ClipData` и `FLAG_GRANT_READ_URI_PERMISSION` сохраняются для каждого кандидата;
- при отсутствии внешнего receiver Aura показывает «Не найдено приложение для отправки APK» вместо молчаливого возврата;
- universal merge/sign/verify pipeline 1.3.8 не менялся.

## Verification

- новый `audit_harness/stage18d_apk_share_chooser.py` сначала воспроизводил RED на 1.3.8 (не было policy/fallback), затем PASS после фикса;
- stage18 universal APK export остаётся обязательным;
- полный Android runtime UX должен быть финально подтверждён на устройстве после clean Windows build.
