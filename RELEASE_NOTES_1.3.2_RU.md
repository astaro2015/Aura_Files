# Aura Files 1.3.2

Исправление защищённого «Избранного» после реального OOM при переносе большого файла.

- Новый AVF2: данные шифруются AES-256-GCM блоками по 1 MiB, поэтому размер файла больше не превращается в требование такого же объёма RAM на `doFinal()`.
- Старый AVF1 из 1.3.1 остаётся читаемым.
- Для новых image/video в Vault сохраняется отдельная зашифрованная mini-thumbnail; открытых JPG рядом с `.avf` нет.
- Внутри «Избранного» появились нормальные миниатюры и обычное поведение открытия: image/video/audio viewers, APK/APKS installer/inspector, reader/archive screens, PDF/text preview и «Открыть в…».
- `.AuraVault` закрыт также от локального backend и встроенных FTP/SFTP listings; служебное имя зарезервировано.
- Усилена логика move/restore, чтобы ошибки SAF provider не могли привести к удалению обеих копий.

Ключ Vault не менялся: `ANDROID_ID -> HKDF-SHA256(info="AuraVault-v1") -> AES-256-GCM`.
