# Aura Files 1.3.12

Исправлена оставшаяся причина ошибки `Missing android.support.FILE_PROVIDER_PATHS meta-data` при отправке APK на Xiaomi/MIUI.

- Версия 1.3.11 добавила собственный `AuraFileProvider`, но старый статический `FileProvider.getUriForFile()` всё ещё повторно читал manifest meta-data. В 1.3.12 этот путь полностью исключён.
- Aura теперь сама формирует совместимый `content://` URI через `AuraFileProvider.uriForFile()` и канонически проверяет, что файл находится внутри одного из разрешённых `file_paths` roots.
- Заменены все старые статические FileProvider URI-вызовы в APK/APKS, Vault, архивном просмотре, backend-open, media и общих share-intent путях.
- Собственный target picker APK из 1.3.10 и Android runtime signer из 1.3.8 сохранены.
- Merge/sign/verify APKS → единый APK не изменён.
