package ru.chitets.app.parser;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import ru.chitets.app.model.ReaderDocument;
import ru.chitets.app.store.LibraryStore;

public final class BookLoader {
    private BookLoader() {}

    public static ReaderDocument load(Context context, Uri uri) throws Exception {
        String fileName = resolveName(context, uri);
        String mime = context.getContentResolver().getType(uri);
        String format = LibraryStore.inferFormat(fileName, mime);
        String fallbackTitle = stripExtension(fileName);
        File cacheRoot = new File(context.getCacheDir(), "reader_books/" + Integer.toHexString(uri.toString().hashCode()));
        if (!cacheRoot.exists() && !cacheRoot.mkdirs()) throw new IOException("Не удалось создать кэш книги");

        switch (format) {
            case "EPUB": {
                File epub = new File(cacheRoot, "book.epub");
                if (!epub.isFile() || epub.length() == 0) copyToFile(open(context, uri), epub);
                return EpubParser.parse(epub, new File(cacheRoot, "epub"), fallbackTitle);
            }
            case "FB2":
                try (InputStream input = open(context, uri)) { return Fb2Parser.parse(input, new File(cacheRoot, "fb2"), fallbackTitle); }
            case "FB2.ZIP":
            case "ZIP":
                try (InputStream input = open(context, uri)) { return parseArchive(input, cacheRoot, fallbackTitle); }
            case "HTML":
                try (InputStream input = open(context, uri)) { return parseHtml(input, fallbackTitle); }
            case "TXT":
                try (InputStream input = open(context, uri)) { return TextParser.parse(input, fallbackTitle); }
            case "MD":
                try (InputStream input = open(context, uri)) { return MarkdownParser.parse(input, fallbackTitle); }
            case "RTF":
                try (InputStream input = open(context, uri)) { return RtfParser.parse(input, fallbackTitle); }
            case "DOCX":
                try (InputStream input = open(context, uri)) { return DocxParser.parse(input, fallbackTitle); }
            case "MOBI": case "AZW": case "AZW3": case "PRC":
                try (InputStream input = open(context, uri)) { return MobiParser.parse(input, fallbackTitle); }
            case "DJVU":
                return DjvuTextParser.parse(open(context, uri), fallbackTitle);
            case "PDF": {
                File pdfCache = new File(cacheRoot, "pdf-reflow");
                ReaderDocument document;
                try (InputStream input = open(context, uri)) {
                    document = PdfReflowParser.parse(input, pdfCache, fallbackTitle);
                }
                // Materialize table/formula/layout crops from the original page. Failure is intentionally
                // non-fatal: PdfReflowParser embeds a text fallback for every hybrid block.
                PdfHybridRenderer.renderMissing(context, uri, pdfCache, document.html);
                return document;
            }
            default:
                throw new IOException("Формат " + format + " пока не поддерживается этим экраном");
        }
    }

    private static ReaderDocument parseArchive(InputStream input, File cacheRoot, String fallbackTitle) throws Exception {
        ArchiveItem best = null;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry; int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 3000) throw new IOException("Слишком много файлов внутри ZIP");
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) continue;
                String format = LibraryStore.inferFormat(name, null);
                int priority = archivePriority(format);
                if (priority >= 1000 || (best != null && priority >= best.priority)) continue;
                byte[] bytes = readZipEntry(zip, 64 * 1024 * 1024);
                best = new ArchiveItem(name, format, priority, bytes);
                if (priority == 10) break; // EPUB is our highest-priority book entry.
            }
        }
        if (best == null) throw new IOException("В ZIP не найдено поддерживаемых книг");
        ArchiveItem item = best;
        String title = stripExtension(new File(item.name).getName());
        ByteArrayInputStream in = new ByteArrayInputStream(item.bytes);
        switch (item.format) {
            case "FB2": return Fb2Parser.parse(in, new File(cacheRoot, "fb2-zip"), title);
            case "TXT": return TextParser.parse(in, title);
            case "HTML": return parseHtml(in, title);
            case "MD": return MarkdownParser.parse(in, title);
            case "RTF": return RtfParser.parse(in, title);
            case "DOCX": return DocxParser.parse(in, title);
            case "MOBI": case "AZW": case "AZW3": case "PRC": return MobiParser.parse(in, title);
            case "EPUB": {
                File epub = new File(cacheRoot, "nested.epub");
                try (FileOutputStream out = new FileOutputStream(epub)) { out.write(item.bytes); }
                return EpubParser.parse(epub, new File(cacheRoot, "nested-epub"), title);
            }
            default: throw new IOException("Формат внутри ZIP пока не поддерживается: " + item.format);
        }
    }

    private static int archivePriority(String format) {
        switch (format) {
            case "EPUB": return 10;
            case "FB2": return 20;
            case "MOBI": case "AZW": case "AZW3": case "PRC": return 30;
            case "DOCX": return 40;
            case "RTF": return 50;
            case "MD": return 60;
            case "HTML": return 70;
            case "TXT": return 80;
            default: return 1000;
        }
    }

    private static byte[] readZipEntry(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384]; int total = 0, read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("Файл внутри ZIP слишком большой");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static ReaderDocument parseHtml(InputStream input, String fallbackTitle) throws IOException {
        String raw = HtmlUtil.decodeText(HtmlUtil.readAll(input));
        String body = HtmlUtil.stripDangerousHtml(HtmlUtil.bodyOf(raw));
        String title = extractHtmlTitle(raw, fallbackTitle);
        return new ReaderDocument(title, "", HtmlUtil.wrap(title, "", body), "about:blank");
    }

    private static InputStream open(Context context, Uri uri) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Не удалось открыть файл");
        return input;
    }

    private static void copyToFile(InputStream input, File target) throws IOException {
        try (InputStream source = input; FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16384]; long total = 0; int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > 256L * 1024L * 1024L) throw new IOException("Файл слишком большой");
                output.write(buffer, 0, read);
            }
        }
    }

    private static String resolveName(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "Книга" : last;
    }

    private static String stripExtension(String name) {
        if (name == null) return "Книга";
        return name.replaceFirst("(?i)\\.fb2\\.zip$", "")
                .replaceFirst("(?i)\\.(epub|fb2|txt|html?|zip|md|markdown|rtf|docx|mobi|azw3?|prc|djvu|djv|pdf)$", "")
                .replace('_', ' ').trim();
    }

    private static String extractHtmlTitle(String html, String fallback) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?is)<title[^>]*>(.*?)</title\\s*>").matcher(html);
        if (matcher.find()) {
            String title = HtmlUtil.cleanTitle(matcher.group(1));
            if (!title.isEmpty()) return title;
        }
        return fallback;
    }

    private static final class ArchiveItem {
        final String name, format; final int priority; final byte[] bytes;
        ArchiveItem(String name, String format, int priority, byte[] bytes) { this.name = name; this.format = format; this.priority = priority; this.bytes = bytes; }
    }
}
