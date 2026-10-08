# Aura Files 1.3.8

Продолжение исправления экспорта установленного SPLIT-приложения в один APK.

## Что изменено

После 1.3.7 выяснилось, что проблема глубже параметров ключа и v1/JAR signing: в Android runtime Aura использовала официальный `com.android.tools.build:apksig`, тогда как upstream AOSP предназначает эту библиотеку для host/build-tool сценариев, а не для исполнения внутри Android-приложения.

В 1.3.8:
- on-device backend заменён на Android port `MuntashirAkon/apksig-android 4.4.0`;
- host-only dependency `com.android.tools.build:apksig` удалён;
- JitPack ограничен только group `com.github.MuntashirAkon`;
- signing floor остаётся API 26;
- v1/JAR выключен, v2 и v3 включены;
- v4 явно выключен, чтобы не запускать ненужную генерацию отдельного `.idsig`;
- проверка подписи после создания APK сохранена;
- package/version validation, atomic publish и APKS fallback не изменены.

Новый audit stage18c не даёт случайно вернуть host-only signer или снова включить v4.
