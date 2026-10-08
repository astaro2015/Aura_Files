# Aura Files — audit checkpoint 17J: release R8 + finite Gradle retry

Дата: 2026-09-14
Версия: 1.3.5 / versionCode 135

## Внешний Windows release-gate

`BUILD_RELEASE_WINDOWS.bat` дошёл до `testDebugUnitTest` и затем упал на `minifyReleaseWithR8`.
Unit-test task завершился до R8 failure, то есть прежние 4 unit-test failure больше не остановили сборку.

R8 сообщил ровно три отсутствующих optional/desktop class families:

- `com.github.luben.zstd.ZstdInputStream` — optional Zstandard integration Apache Commons Compress;
- `javax.management.MBeanException`;
- `javax.management.ReflectionException`.

## Исправление R8

Aura 1.3.5 не объявляет поддержку `.zst` / `.tar.zst` и не вызывает Zstandard API. Поэтому добавление `zstd-jni` только ради неиспользуемого optional path было бы ненужным увеличением APK. В `proguard-rules.pro` добавлено:

```text
-dontwarn com.github.luben.zstd.**
```

Apache MINA SSHD 2.19.0 сам содержит комментарий `Android does not have these classes` и обращается к `MBeanException`/`ReflectionException` только внутри `if (!OsUtils.isAndroid())`. Поэтому для Android release добавлены точечные правила:

```text
-dontwarn javax.management.MBeanException
-dontwarn javax.management.ReflectionException
```

Это не отключает используемую SFTP-функциональность и не добавляет desktop JMX в Android.

## Исправление зацикленного builder

Предыдущий builder анализировал только последние 260 строк `build.log`. Длинный R8 stack trace вытеснял исходную строку `R8: Missing class`, поэтому deterministic failure ошибочно классифицировался как возможный network/download failure и Gradle запускался повторно до 12 раз.

Теперь:

1. После failed Gradle invocation анализируется полный `build.log` текущего запуска.
2. Source/Kotlin/Java/unit-test/R8/missing-artifact ошибки останавливают build после первой попытки.
3. Повтор разрешён только при явной transient network signature (`UnknownHost`, timeout, connection reset, failed GET/HEAD и т.п.).
4. Неизвестная ошибка тоже не повторяется автоматически — builder останавливается после одной попытки.
5. Если `testDebugUnitTest` успел завершиться до более позднего release failure, builder всё равно пытается записать `UNIT_TEST_SUMMARY.txt`.

## Следующий внешний gate

Запустить `BUILD_RELEASE_WINDOWS.bat` из BUILD_FIX5. Ожидается:

- unit tests clean;
- `minifyReleaseWithR8` проходит;
- `assembleRelease` проходит;
- signer SHA-1 совпадает с pinned Aura identity.
