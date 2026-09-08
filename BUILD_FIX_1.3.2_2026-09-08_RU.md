# Aura Files 1.3.2 — compile fix 2026-09-08

Исправлена реальная ошибка Kotlin-компиляции, обнаруженная Windows Gradle build пользователя.

Ошибка:
- `LocalStorageBackend.kt:175` — обращение к `FileRepository.TRASH_FOLDER`;
- `SftpServer.kt:129` и `SftpServer.kt:156` — обращение к `FileRepository.TRASH_FOLDER`;
- `FileRepository` держит константу внутри `private companion object`, поэтому внешний доступ к `FileRepository.Companion` запрещён Kotlin-компилятором.

Исправление:
- добавлена module-internal константа `AURA_TRASH_FOLDER` на верхнем уровне `FileRepository.kt`;
- `LocalStorageBackend` и `SftpServer` используют её напрямую;
- `private companion object` `FileRepository` не раскрывался целиком;
- внутренний `TRASH_FOLDER` оставлен alias на тот же `AURA_TRASH_FOLDER`, поэтому имя `.AuraTrash` имеет один источник истины для затронутой логики.

Версия приложения не менялась: это чистый compile fix для 1.3.2 (`versionCode=132`).
