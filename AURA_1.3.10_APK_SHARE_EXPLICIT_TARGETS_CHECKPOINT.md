# Aura Files 1.3.10 — explicit APK share targets

Дата: 2026-09-16
Версия: `1.3.10` / `versionCode 140`
База: `1.3.9_APK_SHARE_CHOOSER_FIX`.

## Runtime symptom

На Xiaomi/MIUI после успешной сборки и подписи единого APK кнопка «Поделиться» в диалоге «APK готов» по-прежнему не показывала интерфейс отправки. В 1.3.9 MIME fallback и `queryIntentActivities()` уже находили внешний receiver, но системный `Intent.createChooser(...)` визуально не открывался.

## Root cause / compatibility boundary

Общая точка 1.3.7 и 1.3.9 — запуск системного chooser прямо из positive-button callback `AlertDialog`, пока исходное окно ещё закрывается. Кроме того, APK MIME может перехватываться OEM Package Installer и вести себя не как обычный share target. Поскольку OEM Resolver/Chooser после успешного `startActivity()` может не вернуть исключение, полагаться на него как на единственный UI-path ненадёжно.

## Fix

- после нажатия «Поделиться» диалог «APK готов» явно закрывается;
- следующий UI-шаг запускается через `window.decorView.post`, уже после dismiss;
- вместо OEM/system chooser Aura строит собственный список внешних `ACTION_SEND` activities через `queryIntentActivities`;
- список дедуплицируется по точному `ComponentName`, Aura исключает собственный package, disabled и non-exported activities;
- MIME приоритет изменён на `application/octet-stream` → `*/*` → `application/vnd.android.package-archive`, чтобы OEM Package Installer не перехватывал отправку раньше мессенджеров;
- пользователь выбирает приложение в диалоге Aura; затем запускается точный component через `setComponent(...)`;
- перед запуском выбранному package выдаётся `grantUriPermission(..., FLAG_GRANT_READ_URI_PERMISSION)`; `ClipData` и FileProvider URI также сохраняются;
- если подготовка URI, поиск receiver или запуск target падает, Aura показывает конкретное сообщение с причиной вместо молчаливого возврата;
- merge/sign/verify pipeline 1.3.8 не менялся.

## Verification

- `stage18e_apk_share_explicit_targets.py` был RED на 1.3.9 и PASS после реализации;
- `stage18d_apk_share_chooser.py` обновлён под explicit-target flow и продолжает контролировать MIME/read-grant инварианты;
- полный audit-harness обязателен перед упаковкой;
- финальная UX-проверка выполняется на реальном Xiaomi после clean Windows build.
