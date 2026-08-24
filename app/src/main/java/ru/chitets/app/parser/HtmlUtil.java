package ru.chitets.app.parser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class HtmlUtil {
    private static final int MAX_BOOK_BYTES = 64 * 1024 * 1024;

    private HtmlUtil() {}

    public static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BOOK_BYTES) throw new IOException("Файл слишком большой для этой версии ридера");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    public static String decodeText(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException ignored) {
            return new String(bytes, Charset.forName("windows-1251"));
        }
    }

    public static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public static String stripDangerousHtml(String html) {
        if (html == null) return "";
        String safe = html.replaceAll("(?is)<script[^>]*>.*?</script\\s*>", "");
        safe = safe.replaceAll("(?is)<iframe[^>]*>.*?</iframe\\s*>", "");
        safe = safe.replaceAll("(?i)\\s+on[a-z]+\\s*=\\s*(['\"]).*?\\1", "");
        safe = safe.replaceAll("(?i)\\s+on[a-z]+\\s*=\\s*[^\\s>]+", "");
        safe = safe.replaceAll("(?i)javascript\\s*:", "");
        return safe;
    }

    public static String bodyOf(String html) {
        if (html == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?is)<body[^>]*>(.*?)</body\\s*>").matcher(html);
        return matcher.find() ? matcher.group(1) : html;
    }

    public static String wrap(String title, String author, String body) {
        return wrap(title, author, body, "");
    }

    public static String wrap(String title, String author, String body, String extraCss) {
        String safeBody = body == null ? "" : body;
        StringBuilder out = new StringBuilder(Math.max(2048, safeBody.length() + 2048));
        out.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=5\">")
                .append("<title>").append(escape(title)).append("</title>")
                .append("<style id=\"readerBase\">")
                .append("html{height:100%;}body{max-width:760px;margin:0 auto;box-sizing:border-box;overflow-wrap:anywhere;}")
                .append("p{margin:.62em 0;}h1,h2,h3{line-height:1.25;margin:1.25em 0 .55em;break-after:avoid;}")
                .append("img,svg{display:block;max-width:100%;height:auto;margin:1em auto;}blockquote{margin:1em 1.2em;opacity:.88;}")
                .append(".book-title{text-align:center;margin:2.2em 0}.book-author{text-align:center;opacity:.7;margin-top:-1.2em}")
                .append(".chapter{margin-bottom:2em}.verse{margin:.15em 0 0 1.5em}.notes{border-top:1px solid currentColor;margin-top:3em;opacity:.9}")
                .append("a{color:#9c4f2e;text-decoration:none}.page-break{break-before:column;}")
                .append("</style>");
        if (extraCss != null && !extraCss.trim().isEmpty()) {
            out.append("<style id=\"epubStyles\">").append(extraCss).append("</style>");
        }
        out.append("</head><body>");
        if (title != null && !title.trim().isEmpty()) {
            out.append("<h1 class=\"book-title\">").append(escape(title)).append("</h1>");
        }
        if (author != null && !author.trim().isEmpty()) {
            out.append("<div class=\"book-author\">").append(escape(author)).append("</div>");
        }
        out.append(safeBody).append("</body></html>");
        return out.toString();
    }

    public static String cleanTitle(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
