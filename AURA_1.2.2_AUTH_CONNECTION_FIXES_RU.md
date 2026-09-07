> **ИСТОРИЧЕСКИЙ ДОКУМЕНТ.** Актуальная версия — 1.2.3; исправлены Android Keystore IV и завершение Google AuthorizationClient.

# Aura Files 1.2.2 — исправления cloud-подключений

## Исправлено

1. **Yandex Client Secret**
   - предыдущая версия запрашивала только Client ID;
   - добавлено поле Client Secret;
   - secret хранится AES-GCM/Android Keystore;
   - token/refresh запросы получают тот же client secret после перезапуска приложения.

2. **Cloud workspace handoff**
   - после успешного OAuth профиль не просто выбирается в текущем state;
   - Activity запускается заново с `EXTRA_INITIAL_BACKEND_ID`;
   - свежий workspace обязан открыть конкретный cloud backend и локальную память во второй панели.

3. **Google callback fallback**
   - результат Google обрабатывается даже после пересоздания Activity;
   - при `RESULT_OK` без пригодного Intent выполняется однократная non-interactive проверка grant.

4. **Google OAuth diagnostics**
   - `DEVELOPER_ERROR` теперь объясняет необходимость Android OAuth client;
   - в сообщении показываются package и SHA-1 установленного APK;
   - 403 Drive API сохраняет текст Google, чтобы не скрывать `API disabled`/scope policy.

5. **Silent local fallback удалён**
   - ошибки `factory.cloud()` собираются и показываются;
   - отсутствие requested backend больше не выглядит как успешное открытие двух Local-панелей.

6. **Backup safety**
   - `aura_yandex_oauth_secrets.xml` исключён из cloud backup и device transfer.
