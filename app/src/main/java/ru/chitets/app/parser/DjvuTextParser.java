package ru.chitets.app.parser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

import ru.chitets.app.djvu.DjvuDocument;
import ru.chitets.app.djvu.DjvuException;
import ru.chitets.app.model.ReaderDocument;

/** Builds a normal Chitets reflow document from DjVu TXTa/TXTz hidden text. */
public final class DjvuTextParser {
    private static final int MAX_BOOK_BYTES = 512 * 1024 * 1024;

    private DjvuTextParser() {}

    public static ReaderDocument parse(InputStream input, String fallbackTitle) throws Exception {
        byte[] bytes = readAll(input);
        DjvuDocument document = new DjvuDocument(bytes);
        if (!document.hasTextLayer()) {
            throw new IOException("В этом DjVu нет встроенного текстового/OCR-слоя. Доступен оригинальный постраничный режим.");
        }

        String title = fallbackTitle == null || fallbackTitle.trim().isEmpty() ? "DjVu" : fallbackTitle.trim();
        StringBuilder body = new StringBuilder(64 * 1024);
        int decodedPages = 0;
        int failedPages = 0;
        for (int page = 0; page < document.pageCount(); page++) {
            String raw = "";
            boolean failed = false;
            try {
                raw = document.pageText(page);
            } catch (DjvuException error) {
                failed = true;
                failedPages++;
            }
            if (raw != null && !raw.trim().isEmpty()) decodedPages++;
            appendPage(body, page, raw, failed);
        }
        if (decodedPages == 0) {
            throw new IOException(failedPages > 0
                    ? "Текстовый слой DjVu найден, но прочитать его не удалось. Открой оригинал."
                    : "Текстовый слой DjVu пуст. Открой оригинал.");
        }

        String css = ".djvu-reflow-page{margin:0 0 2.2em}.djvu-page-marker{font-size:.72em;opacity:.45;text-align:center;"
                + "border-bottom:1px solid currentColor;padding:.4em 0;margin:1.6em 0 1.2em}"
                + ".djvu-no-text{font-size:.82em;opacity:.48;text-align:center;font-style:italic;margin:1.5em 0}"
                + ".djvu-reflow-page p{orphans:2;widows:2}";
        String html = HtmlUtil.wrap(title, "", body.toString(), css);
        return new ReaderDocument(title, "", "", html, "about:blank", "", Collections.emptyList());
    }

    private static void appendPage(StringBuilder out, int pageIndex, String raw, boolean failed) {
        int page = pageIndex + 1;
        out.append("<section class=\"chapter djvu-reflow-page\" id=\"djvu-page-").append(page).append("\">")
                .append("<div class=\"djvu-page-marker\">Страница ").append(page).append("</div>");
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            out.append("<div class=\"djvu-no-text\">")
                    .append(failed ? "Не удалось прочитать текстовый слой этой страницы" : "На этой странице нет встроенного текста")
                    .append("</div>");
        } else {
            String[] paragraphs = normalized.split("\\n\\s*\\n+");
            for (String paragraph : paragraphs) {
                String clean = joinOcrLines(paragraph);
                if (!clean.isEmpty()) out.append("<p>").append(HtmlUtil.escape(clean)).append("</p>");
            }
        }
        out.append("</section>");
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        // DjVu hidden text uses LF for line, VT for column, GS for region and US for paragraph.
        String text = raw.replace("\r\n", "\n").replace('\r', '\n')
                .replace("\u000b", "\n\n")
                .replace("\u001d", "\n\n")
                .replace("\u001f", "\n\n")
                .replace("\u000c", "\n\n")
                .replace("\u00ad", "");
        text = text.replaceAll("[\\t ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return text;
    }

    private static String joinOcrLines(String paragraph) {
        String[] lines = paragraph.split("\\n+");
        StringBuilder out = new StringBuilder(paragraph.length());
        for (String line : lines) {
            String part = line.replaceAll("\\s+", " ").trim();
            if (part.isEmpty()) continue;
            if (out.length() == 0) {
                out.append(part);
                continue;
            }
            int n = out.length();
            char last = out.charAt(n - 1);
            char first = part.charAt(0);
            // OCR commonly breaks a word at a line boundary with a hyphen.
            if ((last == '-' || last == '\u2010') && Character.isLetter(first)) {
                out.setLength(n - 1);
                out.append(part);
            } else {
                out.append(' ').append(part);
            }
        }
        return out.toString().trim();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream(4 * 1024 * 1024)) {
            byte[] buffer = new byte[128 * 1024];
            int total = 0, read;
            while ((read = in.read(buffer)) != -1) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_BOOK_BYTES) throw new IOException("DjVu больше 512 МБ; текстовый режим пока не загружает такие файлы целиком");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
