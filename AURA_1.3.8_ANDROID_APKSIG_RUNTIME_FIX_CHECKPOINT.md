# Aura Files 1.3.8 — Android apksig runtime fix

Дата: 2026-09-16
Версия: `1.3.8` / `versionCode 138`
База: `1.3.7_UNIVERSAL_APK_SIGN_FIX`.

## Runtime symptom

После merge единый APK всё ещё мог падать на этапе подписи с `SignatureException: Failed to sign`, несмотря на software RSA identity и отключённый v1/JAR path.

## Уточнённая root cause

В 1.3.7 внутри Android-приложения использовался официальный Maven artifact `com.android.tools.build:apksig`. Upstream AOSP документирует `apksig` как библиотеку, предназначенную для использования **вне Android-устройств**. Поэтому сам выбор runtime backend был некорректным: host build-tool исполнялся внутри Aura на телефоне.

## Fix

- runtime dependency заменён на Android port `MuntashirAkon/apksig-android 4.4.0`;
- публичный API `com.android.apksig.ApkSigner/ApkVerifier` сохраняется, поэтому signer/export pipeline не переписан вокруг другого формата;
- JitPack включён только для group `com.github.MuntashirAkon`;
- host-only `com.android.tools.build:apksig` удалён;
- `setMinSdkVersion(Build.VERSION_CODES.O)` сохраняется;
- `setV1SigningEnabled(false)`;
- `setV2SigningEnabled(true)`;
- `setV3SigningEnabled(true)`;
- `setV4SigningEnabled(false)` — отдельный `.idsig` не создаётся и не участвует в single-APK export;
- verifier остаётся на floor API 26;
- stage18/stage18b/stage18c закрепляют backend и signing invariants.

## Compatibility

Aura Files имеет `minSdk 26`; v2 поддерживает весь этот диапазон, v3 добавляется для новых Android. APKS fallback, package/version verification, atomic publish и отдельная export identity сохранены.

## Verification boundary

Container audit проверяет исходный pipeline и release invariants. Финальное подтверждение именно Android runtime требует clean Windows build и запуска на устройстве на том же SPLIT/APKS сценарии, который воспроизводил `Failed to sign`.
