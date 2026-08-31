AURA FILES 1.0 — СБОРКА НА ЧИСТОЙ WINDOWS
============================================================

Для обычной сборки Android Studio, Java, Gradle и Android SDK заранее
устанавливать НЕ НУЖНО.

КАК СОБРАТЬ
-----------
1. Распакуйте архив целиком в любую папку.
2. Дважды щёлкните BUILD_ON_CLEAN_WINDOWS.bat.
3. На вопрос о загрузке Android SDK и принятии его лицензий ответьте Y.
4. Скрипт сам скачает окружение и соберёт debug APK.
5. Готовый APK появится здесь:

   BUILD_OUTPUT\Aura_Files_1.0-debug.apk

ВАЖНО: ЗАГРУЗКИ ТЕПЕРЬ ВОЗОБНОВЛЯЮТСЯ
------------------------------------
Крупные ZIP-файлы JDK, Android command-line tools, Android NDK r28c
(~748 МБ) и Gradle 9.5.0 скачиваются через curl с продолжением.

Если Интернет оборвался:
- НЕ удаляйте C:\Users\Public\AuraBuildTools;
- просто снова запустите BUILD_ON_CLEAN_WINDOWS.bat;
- файл *.part будет продолжен с уже скачанного места;
- успешно скачанные компоненты повторно не скачиваются.

Для Android SDK Platform / Build Tools / Platform Tools sdkmanager запускается
повторно до 6 раз и использует уже установленные компоненты.
Gradle-сборка также автоматически повторяется до 4 раз, если оборвалась
загрузка зависимости.

Что ставится автоматически (портативно, без Android Studio):
- Eclipse Temurin JDK 17;
- Android Command-line Tools 15859902;
- Android SDK Platform 36;
- Android Build Tools 36.0.0;
- Android Platform Tools;
- Android NDK 28.2.13676358 (r28c), напрямую с dl.google.com;
- Gradle 9.5.0, напрямую с services.gradle.org;
- зависимости проекта из Google Maven / Maven Central.

Куда складывается окружение:
  C:\Users\Public\AuraBuildTools

Куда автоматически копируется исходник для сборки:
  C:\Users\Public\AuraBuild\Aura_Files_1.0

Это специально обходит проблемы NDK/Gradle с пробелами и кириллицей
в исходном пути.

Требования:
- Windows 10/11 x64;
- штатный Windows PowerShell 5.1;
- доступ в Интернет при первом запуске;
- желательно 8–10 ГБ свободного места.

ЛОГИ ПРИ ОШИБКЕ
---------------
BUILD_OUTPUT\setup.log       — установка и загрузки
BUILD_OUTPUT\build.log       — Gradle-сборка
BUILD_OUTPUT\LAST_ERROR.txt  — последняя ошибка крупным текстом

Системные JAVA_HOME/ANDROID_HOME вручную менять не требуется.
Администраторские права в штатном случае не требуются.

2026-08-29: добавлен Java-resource fix #2 для BouncyCastle META-INF/LICENSE.md.
