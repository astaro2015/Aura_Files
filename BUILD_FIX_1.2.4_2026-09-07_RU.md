# Aura Files 1.2.4 — исправление Kotlin-компиляции

Дата: 2026-09-07

После пользовательской Windows-сборки обнаружена семантическая ошибка Kotlin-компиляции в новом UI корзины.

Исправлено в `app/src/main/java/com/aurafiles/app/ui/AuraFileManagerApp.kt`:
- добавлен `import androidx.compose.runtime.saveable.rememberSaveable`;
- добавлен `import androidx.compose.material3.OutlinedButton`.

Почему прежняя проверка это пропустила:
- PSI syntax scan проверяет синтаксис Kotlin, но не разрешение имён и импортов;
- файл был синтаксически корректен, однако компилятор видел `Unresolved reference` на двух символах.

Дополнительная проверка после исправления:
- Kotlin/KTS PSI parse: 120 файлов, 0 syntax errors;
- проверены все использования `rememberSaveable` и `OutlinedButton` в source tree: отсутствующих импортов больше нет;
- Android XML parse: 0 ошибок;
- версия приложения оставлена `1.2.4` / `versionCode 124`, так как это только build-fix без изменения функциональности.

Полный Android Gradle build в текущем контейнере не заявляется пройденным: среда не может получить Gradle 9.5.0 с `services.gradle.org` из-за отсутствия сетевого доступа. Финальная проверка компиляции должна выполняться штатным `BUILD_ON_CLEAN_WINDOWS.bat` на Windows.
