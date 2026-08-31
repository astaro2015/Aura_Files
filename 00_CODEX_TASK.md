# Aura Files 1.0 — сведения для сборки

Это полное и авторитетное дерево исходников стабильного релиза Aura Files 1.0.

- `versionName = "1.0"`
- `versionCode = 100`
- package: `com.aurafiles.app`
- OCR намеренно не включён

Проверка релиза:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-daemon
```

На чистой Windows можно запустить `BUILD_ON_CLEAN_WINDOWS.bat`. Сборщик сам подготовит переносимые JDK 17, Android SDK/NDK и Gradle, а APK положит в `BUILD_OUTPUT`.

Состав релиза описан в `AURA_1.0_CHANGES_RU.txt`, результаты локальной проверки — в `PACKAGE_VERIFICATION_1.0_RU.txt`.
