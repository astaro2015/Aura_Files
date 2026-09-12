# Aura Files 1.3.4 — checkpoint

Актуальная точка продолжения после 1.3.3.

- versionName `1.3.4`, versionCode `134`.
- На HomeScreen плитка `FileCategory.Other` в навигационном `CategoryGrid` визуально и функционально заменена на `Все`.
- Это специальная Home-only action: она вызывает `onFullAccess`, а не `openCategory(FileCategory.Other)` и не зависит от наличия/актуальности индекса.
- При уже выданном MANAGE_EXTERNAL_STORAGE `activateFullAccess()` переключает root на `Environment.getExternalStorageDirectory()` и открывает обычный local browser. Без разрешения используется существующий системный flow выдачи полного доступа.
- В CleanupScreen `FileCategory.Other` остаётся аналитической категорией «Другие»: это не навигационный экран и не надо путать её с Home-action «Все».
- User-facing `Books` переименован в «Книги»; обратный mapping временно принимает и старый заголовок «Книги».
- Не возвращать удалённый SMB-list на страницу «Сеть»; 1.3.3 standard SMB browser сохраняется.
- Vault AVF2, APKS, cloud OAuth, Images paging и safety fixes 1.3.2/1.3.3 сохранять.

После любых изменений пересчитывать `SOURCE_SHA256SUMS.txt` последним шагом.
