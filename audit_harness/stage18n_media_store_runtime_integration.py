#!/usr/bin/env python3
from pathlib import Path
import os, shutil, subprocess, tempfile, textwrap

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which('kotlinc')
KOTLIN = shutil.which('kotlin')
JAVAC = shutil.which('javac')
assert KOTLINC and KOTLIN and JAVAC, 'JDK/Kotlin compiler required'
kotlin_home = Path(KOTLINC).resolve().parents[1]
coroutines = kotlin_home / 'lib' / 'kotlinx-coroutines-core-jvm.jar'
assert coroutines.is_file(), f'missing {coroutines}'

with tempfile.TemporaryDirectory(prefix='aura-stage18n-') as td:
    td = Path(td)
    src = td / 'stubs'
    classes = td / 'java-classes'
    out = td / 'kotlin-classes'
    src.mkdir(); classes.mkdir(); out.mkdir()

    files = {
        'android/net/Uri.java': r'''
            package android.net;
            public final class Uri {
                private final String raw;
                private Uri(String raw) { this.raw = raw; }
                public static Uri parse(String raw) { return new Uri(raw); }
                @Override public String toString() { return raw; }
                @Override public boolean equals(Object o) { return o instanceof Uri && raw.equals(((Uri)o).raw); }
                @Override public int hashCode() { return raw.hashCode(); }
            }
        ''',
        'android/content/ContentValues.java': r'''
            package android.content;
            import java.util.HashMap;
            public class ContentValues {
                private final HashMap<String,Object> values = new HashMap<>();
                public ContentValues() {}
                public void put(String key, String value) { values.put(key, value); }
                public void put(String key, Integer value) { values.put(key, value); }
                public String getAsString(String key) { Object v=values.get(key); return v == null ? null : v.toString(); }
                public Integer getAsInteger(String key) { Object v=values.get(key); return v instanceof Number ? ((Number)v).intValue() : null; }
            }
        ''',
        'android/database/Cursor.java': r'''
            package android.database;
            public interface Cursor extends AutoCloseable {
                boolean moveToFirst();
                int getColumnIndex(String name);
                boolean isNull(int index);
                String getString(int index);
                long getLong(int index);
                void close();
            }
        ''',
        'android/os/ParcelFileDescriptor.java': r'''
            package android.os;
            public class ParcelFileDescriptor implements AutoCloseable {
                private final long size;
                public ParcelFileDescriptor(long size) { this.size = size; }
                public long getStatSize() { return size; }
                @Override public void close() {}
            }
        ''',
        'android/content/ContentResolver.java': r'''
            package android.content;
            import android.database.Cursor;
            import android.net.Uri;
            import android.os.ParcelFileDescriptor;
            import java.io.InputStream;
            import java.io.OutputStream;
            public abstract class ContentResolver {
                public abstract Uri insert(Uri uri, ContentValues values);
                public abstract OutputStream openOutputStream(Uri uri, String mode);
                public abstract int update(Uri uri, ContentValues values, String selection, String[] args);
                public abstract Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder);
                public abstract int delete(Uri uri, String selection, String[] args);
                public abstract ParcelFileDescriptor openFileDescriptor(Uri uri, String mode);
                public abstract InputStream openInputStream(Uri uri);
                public abstract String getType(Uri uri);
            }
        ''',
        'android/content/Context.java': r'''
            package android.content;
            public abstract class Context {
                public abstract ContentResolver getContentResolver();
            }
        ''',
        'android/os/Build.java': r'''
            package android.os;
            public final class Build {
                public static final class VERSION { public static int SDK_INT = 36; }
                public static final class VERSION_CODES { public static final int Q = 29; }
            }
        ''',
        'android/os/Environment.java': r'''
            package android.os;
            public final class Environment { public static final String DIRECTORY_DOWNLOADS = "Download"; }
        ''',
        'android/provider/OpenableColumns.java': r'''
            package android.provider;
            public interface OpenableColumns {
                String DISPLAY_NAME = "_display_name";
                String SIZE = "_size";
            }
        ''',
        'android/provider/MediaStore.java': r'''
            package android.provider;
            import android.net.Uri;
            public final class MediaStore {
                public static final String VOLUME_EXTERNAL_PRIMARY = "external_primary";
                public static final class MediaColumns {
                    public static final String DISPLAY_NAME = "_display_name";
                    public static final String MIME_TYPE = "mime_type";
                    public static final String RELATIVE_PATH = "relative_path";
                    public static final String IS_PENDING = "is_pending";
                }
                public static final class Downloads {
                    public static Uri getContentUri(String volumeName) { return Uri.parse("content://media/" + volumeName + "/downloads"); }
                }
            }
        ''',
    }
    java_paths=[]
    for rel, body in files.items():
        p=src/rel; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(textwrap.dedent(body), encoding='utf-8'); java_paths.append(str(p))
    subprocess.run([JAVAC, '-d', str(classes), *java_paths], check=True)
    stubs_jar = td/'android-stubs.jar'
    subprocess.run(['jar','cf',str(stubs_jar),'-C',str(classes),'.'], check=True)

    aura_stub = td/'AuraFileProvider.kt'
    aura_stub.write_text(textwrap.dedent(r'''
        package com.aurafiles.app
        import android.content.Context
        import android.net.Uri
        import java.io.File
        import java.net.URLEncoder
        object AuraFileProvider {
            fun uriForFile(context: Context, file: File): Uri =
                Uri.parse("content://aura/" + URLEncoder.encode(file.absolutePath, Charsets.UTF_8.name()))
        }
    '''), encoding='utf-8')

    harness = td/'MediaStoreRuntimeHarness.kt'
    harness.write_text(textwrap.dedent(r'''
        import android.content.ContentResolver
        import android.content.ContentValues
        import android.content.Context
        import android.database.Cursor
        import android.net.Uri
        import android.os.Build
        import android.os.ParcelFileDescriptor
        import android.provider.MediaStore
        import android.provider.OpenableColumns
        import com.aurafiles.app.tools.ApkSharePublisher
        import java.io.ByteArrayInputStream
        import java.io.ByteArrayOutputStream
        import java.io.File
        import java.io.InputStream
        import java.io.OutputStream
        import java.net.URLDecoder
        import java.nio.file.Files
        import kotlinx.coroutines.runBlocking

        private enum class Scenario { OK, INSERT_FAIL, WRONG_MIME, TRUNCATE }

        private class RowCursor(private val name: String?, private val size: Long?) : Cursor {
            private var moved = false
            override fun moveToFirst(): Boolean { moved = true; return true }
            override fun getColumnIndex(name: String): Int = when(name) {
                OpenableColumns.DISPLAY_NAME -> 0
                OpenableColumns.SIZE -> 1
                else -> -1
            }
            override fun isNull(index: Int): Boolean = when(index) { 0 -> name == null; 1 -> size == null; else -> true }
            override fun getString(index: Int): String = if(index == 0) name!! else error("bad string index")
            override fun getLong(index: Int): Long = if(index == 1) size!! else error("bad long index")
            override fun close() {}
        }

        private class FakeResolver(private val scenario: Scenario) : ContentResolver() {
            val mediaUri: Uri = Uri.parse("content://media/external_primary/downloads/42")
            var mediaName: String = ""
            var mediaMime: String = "application/octet-stream"
            var mediaBytes = ByteArray(0)
            var pending = true
            var insertCalls = 0
            var deleteCalls = 0
            var updateCalls = 0

            private fun fallbackFile(uri: Uri): File {
                val raw = uri.toString().removePrefix("content://aura/")
                return File(URLDecoder.decode(raw, Charsets.UTF_8.name()))
            }
            private fun isFallback(uri: Uri) = uri.toString().startsWith("content://aura/")

            override fun insert(uri: Uri, values: ContentValues): Uri? {
                insertCalls++
                if (scenario == Scenario.INSERT_FAIL) throw IllegalStateException("simulated MediaStore insert failure")
                check(uri == MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
                mediaName = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)!!
                mediaMime = values.getAsString(MediaStore.MediaColumns.MIME_TYPE)!!
                pending = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1
                return mediaUri
            }

            override fun openOutputStream(uri: Uri, mode: String): OutputStream? {
                check(uri == mediaUri && mode == "w")
                return object : ByteArrayOutputStream() {
                    override fun close() {
                        super.close()
                        val full = toByteArray()
                        mediaBytes = if (scenario == Scenario.TRUNCATE && full.size > 2) full.copyOf(full.size / 2) else full
                    }
                }
            }

            override fun update(uri: Uri, values: ContentValues, selection: String?, args: Array<out String>?): Int {
                check(uri == mediaUri)
                updateCalls++
                if (values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0) pending = false
                return 1
            }

            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sortOrder: String?): Cursor? =
                if (isFallback(uri)) {
                    val f = fallbackFile(uri)
                    RowCursor(f.name, f.length())
                } else {
                    check(uri == mediaUri)
                    RowCursor(mediaName, mediaBytes.size.toLong())
                }

            override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
                if (uri == mediaUri) { deleteCalls++; mediaBytes = ByteArray(0); return 1 }
                return 0
            }

            override fun openFileDescriptor(uri: Uri, mode: String): ParcelFileDescriptor? {
                check(mode == "r")
                val size = if (isFallback(uri)) fallbackFile(uri).length() else mediaBytes.size.toLong()
                return ParcelFileDescriptor(size)
            }

            override fun openInputStream(uri: Uri): InputStream? {
                val bytes = if (isFallback(uri)) fallbackFile(uri).readBytes() else mediaBytes
                return ByteArrayInputStream(bytes)
            }

            override fun getType(uri: Uri): String? = when {
                isFallback(uri) -> "application/octet-stream"
                scenario == Scenario.WRONG_MIME -> "application/vnd.android.package-archive"
                else -> mediaMime
            }
        }

        private class FakeContext(private val resolver: FakeResolver) : Context() {
            override fun getContentResolver(): ContentResolver = resolver
        }

        private fun apkFile(dir: File): File = File(dir, "installed-Test App-1.0.apk").apply {
            writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4) + ByteArray(8192) { (it % 251).toByte() })
        }

        fun main() = runBlocking {
            val temp = Files.createTempDirectory("aura-mediastore-runtime").toFile()
            try {
                val source = apkFile(temp)

                Build.VERSION.SDK_INT = 36
                run {
                    val r = FakeResolver(Scenario.OK)
                    val out = ApkSharePublisher.publish(FakeContext(r), source)
                    check(out.savedToDownloads)
                    check(out.uri == r.mediaUri)
                    check(out.displayName == source.name)
                    check(out.size == source.length())
                    check(r.mediaBytes.contentEquals(source.readBytes()))
                    check(!r.pending && r.updateCalls == 1 && r.deleteCalls == 0)
                    check(r.mediaMime == "application/octet-stream")
                }

                // OEM MediaStore insert failure: verified native Aura provider must take over.
                run {
                    val r = FakeResolver(Scenario.INSERT_FAIL)
                    val out = ApkSharePublisher.publish(FakeContext(r), source)
                    check(!out.savedToDownloads)
                    check(out.uri.toString().startsWith("content://aura/"))
                    check(r.insertCalls == 1)
                }

                // Recipient-visible MIME changed by an OEM: reject/delete MediaStore item, then fallback.
                run {
                    val r = FakeResolver(Scenario.WRONG_MIME)
                    val out = ApkSharePublisher.publish(FakeContext(r), source)
                    check(!out.savedToDownloads)
                    check(out.uri.toString().startsWith("content://aura/"))
                    check(r.deleteCalls == 1) { "bad MediaStore item was not deleted" }
                }

                // Underlying MediaStore write silently truncates: size preflight must catch/delete/fallback.
                run {
                    val r = FakeResolver(Scenario.TRUNCATE)
                    val out = ApkSharePublisher.publish(FakeContext(r), source)
                    check(!out.savedToDownloads)
                    check(r.deleteCalls == 1)
                }

                // Android 8/9 never touches MediaStore and uses the preflighted native provider directly.
                Build.VERSION.SDK_INT = 28
                run {
                    val r = FakeResolver(Scenario.OK)
                    val out = ApkSharePublisher.publish(FakeContext(r), source)
                    check(!out.savedToDownloads)
                    check(r.insertCalls == 0)
                    check(out.uri.toString().startsWith("content://aura/"))
                }

                println("STAGE18N_MEDIASTORE_RUNTIME_INTEGRATION_PASS scenarios=5")
            } finally {
                temp.deleteRecursively()
            }
        }
    '''), encoding='utf-8')

    cp = os.pathsep.join([str(stubs_jar), str(coroutines)])
    sources = [
        str(ROOT/'app/src/main/java/com/aurafiles/app/tools/ApkShareDeliveryPolicy.kt'),
        str(ROOT/'app/src/main/java/com/aurafiles/app/tools/ApkSharePublisher.kt'),
        str(aura_stub), str(harness),
    ]
    subprocess.run([KOTLINC, '-classpath', cp, *sources, '-d', str(out)], check=True)
    run_cp = os.pathsep.join([str(out), str(stubs_jar), str(coroutines)])
    subprocess.run([KOTLIN, '-classpath', run_cp, 'MediaStoreRuntimeHarnessKt'], check=True)
