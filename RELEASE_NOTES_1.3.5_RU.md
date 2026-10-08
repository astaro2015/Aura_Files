# Aura Files 1.3.5

Версия 1.3.5 — большой safety/release-hardening поверх 1.3.4. Пользовательская логика и знакомый интерфейс сохраняются; основная работа прошла под капотом вокруг сохранности файлов, сетевых ошибок, process kill, отмены операций, viewer/cache lifecycle и APK/APKS.

## Файловые операции и crash recovery

- Пакетное переименование получило durable journal в `noBackupFilesDir` и умеет безопасно продолжить/откатить незавершённые swap/cycle rename после process kill.
- Проверены циклы 2–7 файлов с аварией после каждого destructive шага, включая lost ACK и падения во время rollback.
- Локальные COPY/MOVE/REPLACE hardened через `.aura-part-*` / `.aura-backup-*`, identity/fingerprint reconciliation и conservative handling неоднозначного результата провайдера.
- Локальный REPLACE получил отдельный durable crash journal и startup recovery.
- После crash между batch rename и обновлением индекса Aura умеет reconciliate stale Room/index state.

## Рекурсия, symlink и корзина

- Восстановлены engine-level запреты копирования/перемещения папки внутрь себя или потомка.
- Добавлены depth/cycle/symlink guards в локальный transfer, StorageIndexer, FolderSize, DirectoryComparator, legacy recursive analysis/copy/ZIP paths.
- Корзина больше не удаляет страховочную копию при неопределённом результате удаления исходника.
- Metadata корзины сохраняется синхронно; orphan physical trash objects без metadata снова видимы и восстанавливаемы.
- Partial Empty Trash фиксирует уже подтверждённые удаления поэлементно.
- `.AuraTrash` и служебные рекурсивные пути защищены от symlink traversal.

## Vault / «Избранное»

- Restore из Vault выполняется через скрытый staging-файл и durable journal, а не напрямую в финальное имя.
- Для SAF destination permission сохраняется до появления внешнего plaintext, чтобы recovery был возможен после process restart.
- Неоднозначные/противоречивые состояния сохраняют возможные копии вместо рискованной очистки.
- Исправлен lifecycle временного plaintext: staging timestamp теперь означает время staging, а не старую дату исходного фото.
- Добавлен резерв свободного места для больших decrypt/preview операций.
- UI сообщает, сколько файлов уже подтверждённо перемещено в Vault, если пакетная операция завершилась частично.

## Облако и сеть

- Yandex Disk: bounded async-operation polling, cancellation-aware timeouts и reconciliation после lost ACK для rename/move/delete/mkdir.
- Google Drive: stale ID больше не считается валидным без подтверждённого parent membership; trashed objects не принимаются за живые; hardened duplicate-name/shortcut/mutation semantics.
- Legacy FTP/SMB и общий backend transfer hardened против destructive automatic retry, stale sessions, partial transfers и неоднозначного server ACK.
- Временные remote-open файлы пишутся в `.part`, проверяют cancellation/free space и становятся готовыми только после полного commit.

## Viewer / media / readers / cache

- EXIF orientation унифицирована между плитками/preview и полноэкранным просмотром.
- Битые image/PDF больше не оставляют бесконечный spinner без результата.
- Archive preview получает cleanup, free-space budget и безопасное завершение временных файлов.
- DjVu memory limit теперь учитывает реальный Java heap вместо опасного fixed 512 MiB whole-file allowance.
- Длинные reader/comic/image loops стали interrupt/cancellation-aware.
- EPUB cache создаётся через `.part` и не принимается за готовую книгу после оборванной загрузки.
- WebView reader запрещает JavaScript file-origin доступ к другим `file://` URL.

## APK / APKS

- APKS import/export ограничен 8 GiB payload ceiling и оставляет 128 MiB safety reserve.
- Import, extraction, hashing, export и PackageInstaller session writes проверяют cancellation.
- Обычный APK share создаётся через temporary file → fsync/verify → final rename.
- Экспорт `.apks` проверяет размеры исходных split APK и свободное место.
- Экспортированный installer доверяет системному install-result только при совпадении сохранённых `sessionId + случайный nonce`; spoofed внешние callback intents отвергаются.
- Trusted callback state исключён из cloud backup/device transfer.
- `ApkInspectorActivity` использует lifecycle-owned job и bounded/cancellable copy/hash.

## Dependencies / release hardening

- XZ for Java: `1.10 → 1.12` из-за исправленного upstream encoder bug на Java 9+.
- Bouncy Castle: `bcprov 1.85.2`, `bcpkix 1.85` (реально опубликованные Maven Central версии).
- `THIRD_PARTY_LICENSES.md` дополнен AndroidX, Commons Net/Compress, Bouncy Castle, XZ и явной лицензией SLF4J.
- Manifest/FileProvider/backup/R8/JNI/XML release surface проверен автоматическим harness.
- Все сохранённые fault/state/source harnesses повторно прогнаны на итоговом дереве перед version bump.

## Важное о финальной сборке

Контейнер аудита не имеет Android SDK/Gradle dependency cache и не может скачать Gradle 9.5.0 из-за `UnknownHostException: services.gradle.org`. Поэтому статические и stub-компиляционные проверки не объявляются полноценной Android-сборкой.

Для финального кандидата нужно запустить на Windows:

```text
BUILD_ON_CLEAN_WINDOWS.bat
```

Успешный clean build является последним обязательным release gate для APK.

## Build fix после первого clean-Windows прогона

Первый внешний build-gate выявил ошибочный dependency pin: `bcpkix-jdk18on:1.85.2` не опубликован в Maven Central. Исправлено без изменения функционального кода приложения:

- `bcprov-jdk18on` остаётся `1.85.2` (его использует SMBJ 0.15.0);
- `bcpkix-jdk18on` исправлен на реально опубликованный `1.85`;
- release dependency guard обновлён так, чтобы не допустить повторного несуществующего pin;
- Windows bootstrap теперь добавляет в `LAST_ERROR.txt` отдельный блок `COMPILER/BUILD ERROR HIGHLIGHTS`, чтобы следующие compiler failures не терялись за Gradle stack trace.


## BUILD_FIX3 — синхронизация unit-тестов с safety hardening

- Production-код не ослаблялся.
- Google Drive test double теперь моделирует обязательный `root` folder.
- REPLACE cleanup tests больше не требуют повторного destructive delete после ambiguous/lost-ACK ошибки: ожидается одна попытка, сохранение страховочной `.aura-backup-*` и warning.
- Повторный Windows `testDebugUnitTest` остаётся обязательным release gate.


## BUILD_FIX4 — стабильная подпись debug/release

- Добавлен `BUILD_RELEASE_WINDOWS.bat`.
- Release-сборка запускает `testDebugUnitTest` и `assembleRelease`.
- Release APK намеренно подписывается тем же сертификатом, что существующие debug APK Aura.
- Builder жёстко проверяет pinned SHA-1 до сборки и через `apksigner` после сборки.
- При потере/замене keystore автоматическая генерация нового ключа запрещена, чтобы случайно не сменить identity приложения и не потерять совместимость с существующим Vault.

## BUILD_FIX5 — R8 Android optional classes + защита от зацикливания builder

- Release gate дошёл до `testDebugUnitTest`; прежние 4 unit-test failure больше не остановили сборку.
- Для optional Zstandard path Commons Compress добавлен точечный R8 `dontwarn`; `.zst` Aura не поддерживает и `zstd-jni` в APK не добавляется.
- Для Apache MINA SSHD добавлены только Android-специфичные `dontwarn` для `javax.management.MBeanException` и `ReflectionException`; upstream 2.19.0 сам не выполняет эти ветки на Android.
- Gradle retry больше не повторяет deterministic source/test/R8/missing-artifact failures: полный log анализируется после первой неудачи.
- Автоповтор остаётся только для явно распознанных transient network/download errors.
- Unit-test summary сохраняется даже если тесты прошли, а более поздний release task (например R8) упал.

## BUILD_FIX6 — реальный release routing

- Исправлен PowerShell scope-баг, из-за которого `BUILD_RELEASE_WINDOWS.bat` запускался с `-BuildType Release`, но внутри build-функции режим терялся и фактически выполнялся `assembleDebug`.
- BuildType теперь нормализуется в script scope и явно передаётся в signing/build functions.
- Добавлены fail-closed проверки: Release не может молча превратиться в Debug.
- Builder теперь печатает фактический режим и точный список Gradle tasks до запуска.


### BUILD_FIX7 — PowerShell entrypoint

- Устранена ошибка Windows PowerShell 5.1, при которой `Release` ошибочно привязывался к `[switch]$SkipConsent`.
- Причина: двойной UTF-8 BOM перед верхним `param(...)` в `bootstrap_windows.ps1`.
- Bootstrap теперь чистый ASCII без BOM; официальный режим сборки передаётся через `AURA_BUILD_TYPE=Debug|Release`.
- Сохраняется fail-closed проверка Release-маршрута и одинаковая закреплённая подпись debug/release.
