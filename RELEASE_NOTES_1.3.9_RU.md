# Aura Files 1.3.9

Исправление кнопки «Поделиться» после успешного экспорта установленного SPLIT-приложения в один APK.

## Что изменено

В 1.3.7/1.3.8 готовый APK отправлялся через `ACTION_SEND` только с MIME `application/vnd.android.package-archive`. На некоторых прошивках, в частности Xiaomi/MIUI, системный chooser для такого MIME мог фактически не дать внешнего получателя: диалог «APK готов» закрывался, а пользователь оставался в Aura.

В 1.3.9:
- вынесена политика исходящего APK-share в `ApkShareIntentPolicy`;
- MIME-кандидаты идут в безопасном порядке: `application/vnd.android.package-archive`, затем `application/octet-stream`, затем `*/*`;
- Aura перед запуском chooser проверяет `PackageManager.queryIntentActivities(...)` и выбирает первый вариант, для которого существует внешний receiver;
- собственный package Aura не считается достаточным получателем;
- `FileProvider`, `ClipData` и `FLAG_GRANT_READ_URI_PERMISSION` сохранены;
- если внешнего приложения для отправки действительно нет, пользователь получает явное сообщение;
- merge, Android runtime signing и verification pipeline 1.3.8 не изменены.

Добавлен audit stage18d, который закрепляет MIME fallback и receiver check.
