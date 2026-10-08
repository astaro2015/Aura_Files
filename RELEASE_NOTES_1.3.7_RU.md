# Aura Files 1.3.7

Исправление экспорта установленного SPLIT-приложения в один APK.

## Что исправлено

На части устройств экспорт доходил до подписи объединённого APK и падал с `SignatureException: Failed to sign`. В 1.3.6 экспортный ключ уже был вынесен из AndroidKeyStore в обычный software RSA key, но реализация всё ещё принудительно включала legacy v1/JAR signing. Это противоречило исходному дизайну функции: Aura Files работает с Android 8.0+ (API 26), где достаточно современных APK Signature Scheme.

В 1.3.7:
- подпись Universal APK выполняется с signing floor API 26;
- v1/JAR signing отключён;
- v2 и v3 оставлены включёнными;
- `ApkVerifier` проверяет результат начиная с API 26;
- audit-harness запрещает случайно вернуть v1 в будущем.

APKS fallback, merge ARSCLib, проверка package/version, отдельный export key и все safety-инварианты 1.3.6 сохранены.
