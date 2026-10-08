# Aura Files — AUDIT CHECKPOINT 17C

Дата: 2026-09-14\\
Этап: release dependency / security / license review

## Проверено

Сверены прямые runtime-зависимости `app/build.gradle.kts`, их версии, известные актуальные security advisories для archive/network/crypto стека и полнота `THIRD_PARTY_LICENSES.md`.

## Изменения

### XZ for Java

Обновлено:

```text
org.tukaani:xz:1.10 -> 1.12
```

Причина: upstream XZ for Java сообщает о significant encoder bug в 1.10/1.11 на Java 9+; исправление выпущено в 1.12. Aura создаёт XZ/TAR.XZ архивы, поэтому это применимый release-risk, а не косметическое обновление.

Upstream: https://tukaani.org/xz/java.html

### Bouncy Castle

Обновлено:

```text
bcprov-jdk18on:1.85 -> 1.85.2
bcpkix-jdk18on:1.85 (оставлен на реально опубликованной версии)
```

Причины:

- SMBJ 0.15.0 сам публикуется с runtime `bcprov-jdk18on:1.85.2`;
- для `bcpkix-jdk18on` Maven Central публикует `1.85`, но не `1.85.2`; попытка pin `1.85.2` делает dependency graph неразрешимым;
- линия >=1.84 уже содержит fixes для известных CVE-2026-0636 / CVE-2025-14813, которые security scanners могут показывать для более старых транзитивных BC-зависимостей.

Upstream: https://www.bouncycastle.org/download/bouncy-castle-java/

### Junrar

Оставлено `8.1.0`.

Проверенные 2026 advisories по path traversal / RarVM infinite CPU loop были исправлены начиная с 7.5.8, 7.5.10 и 7.6.1 соответственно; 8.1.0 находится выше всех этих patched versions. Переход на более новый patch без подтверждённой release необходимости не делался в конце аудита.

Upstream advisories: https://github.com/junrar/junrar/security/advisories

### JCIFS

Оставлено `org.codelibs:jcifs:2.1.40`.

3.x существует, но upstream migration guide указывает на package/API migration. В данном проекте JCIFS используется узко для enumeration SMB shares, а browsing/transfers идут через SMBJ. Большую API migration без полного Gradle build в финальном hardening-pass не выполнять.

Security warnings, привязанные к старым транзитивным Bouncy Castle версиям, перекрываются прямым project pin на актуальную линию 1.85 (`bcprov` 1.85.2 / `bcpkix` 1.85).

## License inventory

`THIRD_PARTY_LICENSES.md` дополнен runtime-компонентами, которые были объявлены в Gradle, но отсутствовали в project-level inventory:

- AndroidX / Jetpack / Compose;
- Apache Commons Net;
- Apache Commons Compress;
- Bouncy Castle;
- XZ for Java;
- явная MIT-лицензия SLF4J.

Это особенно важно, поскольку Android packaging исключает конфликтующие `META-INF/LICENSE*`/`NOTICE*` файлы отдельных JVM JAR и project-level notice остаётся канонической inventory для source distribution.

## Автоматическая проверка

Добавлен:

`audit_harness/stage17c_dependency_release_guard.py`

Он фиксирует выбранные security pins и проверяет наличие notice для каждого прямого runtime dependency family.

Результат:

```text
STAGE17C_DEPENDENCY_RELEASE_GUARD_PASS
git diff --check: PASS
```

## Ограничение

Dependency resolution/Gradle build будет проверяться отдельно в release build-attempt. Этот checkpoint не выдаёт статический review за успешную Android dependency resolution.
