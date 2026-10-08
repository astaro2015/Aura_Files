# Aura Files 1.3.7 — Universal APK runtime signing fix

Дата: 2026-09-16
Версия: `1.3.7` / `versionCode 137`
База: `1.3.6_UNIVERSAL_APK_SIGN_FIX`.

## Runtime symptom

После merge Universal APK подпись могла завершаться `SignatureException: Failed to sign`. Software RSA export identity из hotfix 1.3.6 валидировалась обычным `SHA256withRSA`, поэтому повторная смена хранилища ключа не является корректным лечением.

## Root cause / inconsistency

Канонический design 1.3.6 задаёт v2+v3 без v1 для API 26+, но `ApkExportSigner` фактически содержал `.setV1SigningEnabled(true)`. Это принудительно запускало legacy JAR/PKCS#7 signing path внутри apksig на Android runtime.

## Fix

- `setMinSdkVersion(Build.VERSION_CODES.O)`;
- `setV1SigningEnabled(false)`;
- v2/v3 остаются включены;
- verifier получает `setMinCheckedPlatformVersion(Build.VERSION_CODES.O)`;
- stage18/stage18b закрепляют эти инварианты.

## Compatibility

Aura Files имеет `minSdk 26`, поэтому v2 доступен на всех устройствах, где сама Aura может работать. Fused APK остаётся device-derived; APKS сохраняется как fallback.
