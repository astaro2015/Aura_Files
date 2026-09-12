# Aura Files 1.3.3 — checkpoint

Актуальная точка продолжения после 1.3.2.

- versionName `1.3.3`, versionCode `133`.
- SMB browsing на главной странице `Сеть` удалён: удалённые файлы открываются через стандартный `BackendWorkspaceActivity`/`SmbStorageBackend`.
- Сохранённый SMB с `smbShare` открывается сразу как `smb:<profile.id>` в single-pane.
- SMB без выбранной шары использует legacy `SmbRepository` только для discoverShares; после выбора шары профиль сохраняется, стандартный browser открывается автоматически, legacy session закрывается тихо.
- Не возвращать старый список `state.smbItems` в `FtpScreen`: это снова делает успешное подключение неочевидным.
- Vault AVF2, APKS, cloud OAuth, Images paging и safety fixes 1.3.2 сохранять.

После изменений пересчитывать `SOURCE_SHA256SUMS.txt` последним шагом.
