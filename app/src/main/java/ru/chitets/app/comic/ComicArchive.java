package ru.chitets.app.comic;

import android.content.Context;
import android.net.Uri;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ComicArchive {
    private static final int MAX_PAGES = 5000;
    private static final long MAX_ARCHIVE_BYTES = 768L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_BYTES = 1024L * 1024L * 1024L;

    private ComicArchive() {}

    public static List<File> prepare(Context context, Uri uri, String format) throws Exception {
        File root = new File(context.getCacheDir(), "comic_books/" + Integer.toHexString(uri.toString().hashCode()));
        File pages = new File(root, "pages");
        File marker = new File(root, "ready-" + format.toLowerCase(Locale.ROOT));
        if (marker.isFile()) {
            List<File> ready = listPages(pages);
            if (!ready.isEmpty()) return ready;
        }
        deleteRecursive(root);
        if (!pages.mkdirs()) throw new IOException("Не удалось создать кэш комикса");
        File archive = new File(root, "book." + ("CBR".equals(format) ? "cbr" : "cbz"));
        copyUri(context, uri, archive);
        if ("CBR".equals(format)) extractRar(archive, pages); else extractZip(archive, pages);
        List<File> result = listPages(pages);
        if (result.isEmpty()) throw new IOException("В архиве не найдено изображений");
        try (FileOutputStream out = new FileOutputStream(marker)) { out.write('1'); }
        return result;
    }

    private static void extractZip(File archiveFile, File pagesDir) throws Exception {
        try (ZipFile zip = new ZipFile(archiveFile)) {
            List<? extends ZipEntry> all = Collections.list(zip.entries());
            List<ZipEntry> images = new ArrayList<>();
            for (ZipEntry entry : all) {
                if (!entry.isDirectory() && isImage(entry.getName())) images.add(entry);
            }
            images.sort((a, b) -> naturalCompare(a.getName(), b.getName()));
            if (images.size() > MAX_PAGES) throw new IOException("Слишком много страниц в CBZ");
            long total = 0;
            for (int i = 0; i < images.size(); i++) {
                ZipEntry entry = images.get(i);
                File target = new File(pagesDir, pageName(i, extension(entry.getName())));
                try (InputStream in = zip.getInputStream(entry)) {
                    total += copyLimited(in, target, MAX_EXTRACTED_BYTES - total);
                    if (total > MAX_EXTRACTED_BYTES) throw new IOException("Комикс слишком большой после распаковки");
                }
            }
        }
    }

    private static void extractRar(File archiveFile, File pagesDir) throws Exception {
        List<RarEntryData> entries = new ArrayList<>();
        try (Archive archive = new Archive(new FileInputStream(archiveFile))) {
            for (FileHeader header : archive.getFileHeaders()) {
                if (!header.isDirectory() && isImage(header.getFileName())) {
                    entries.add(new RarEntryData(header, header.getFileName()));
                }
            }
            entries.sort((a, b) -> naturalCompare(a.name, b.name));
            if (entries.size() > MAX_PAGES) throw new IOException("Слишком много страниц в CBR");
            long total = 0;
            for (int i = 0; i < entries.size(); i++) {
                RarEntryData entry = entries.get(i);
                File target = new File(pagesDir, pageName(i, extension(entry.name)));
                try (InputStream in = archive.getInputStream(entry.header)) {
                    total += copyLimited(in, target, MAX_EXTRACTED_BYTES - total);
                    if (total > MAX_EXTRACTED_BYTES) throw new IOException("Комикс слишком большой после распаковки");
                }
            }
        }
    }

    private static long copyLimited(InputStream in, File target, long remaining) throws IOException {
        if (remaining <= 0) throw new IOException("Превышен размер распакованного комикса");
        long written = 0;
        try (FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > remaining) throw new IOException("Превышен размер распакованного комикса");
                out.write(buffer, 0, read);
            }
        }
        return written;
    }

    private static void copyUri(Context context, Uri uri, File target) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Не удалось открыть архив");
            byte[] buffer = new byte[32768];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_ARCHIVE_BYTES) throw new IOException("Архив комикса слишком большой");
                out.write(buffer, 0, read);
            }
        }
    }

    private static List<File> listPages(File pagesDir) {
        File[] files = pagesDir.listFiles(File::isFile);
        List<File> pages = new ArrayList<>();
        if (files != null) Collections.addAll(pages, files);
        pages.sort(Comparator.comparing(File::getName));
        return pages;
    }

    private static String pageName(int index, String ext) {
        return String.format(Locale.ROOT, "%05d.%s", index, ext.isEmpty() ? "img" : ext);
    }

    private static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "img";
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.length() <= 5 ? ext : "img";
    }

    private static boolean isImage(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    private static int naturalCompare(String a, String b) {
        String x = a == null ? "" : a.toLowerCase(Locale.ROOT);
        String y = b == null ? "" : b.toLowerCase(Locale.ROOT);
        int i = 0, j = 0;
        while (i < x.length() && j < y.length()) {
            char cx = x.charAt(i), cy = y.charAt(j);
            if (Character.isDigit(cx) && Character.isDigit(cy)) {
                int si = i, sj = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) i++;
                while (j < y.length() && Character.isDigit(y.charAt(j))) j++;
                String nx = x.substring(si, i).replaceFirst("^0+(?!$)", "");
                String ny = y.substring(sj, j).replaceFirst("^0+(?!$)", "");
                if (nx.length() != ny.length()) return Integer.compare(nx.length(), ny.length());
                int cmp = nx.compareTo(ny);
                if (cmp != 0) return cmp;
            } else {
                if (cx != cy) return Character.compare(cx, cy);
                i++; j++;
            }
        }
        return Integer.compare(x.length(), y.length());
    }

    private static void deleteRecursive(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursive(child);
        file.delete();
    }

    private static final class RarEntryData {
        final FileHeader header;
        final String name;
        RarEntryData(FileHeader header, String name) { this.header = header; this.name = name; }
    }
}
