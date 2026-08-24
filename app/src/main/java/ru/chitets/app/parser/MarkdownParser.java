package ru.chitets.app.parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.chitets.app.model.ReaderDocument;
import ru.chitets.app.model.TocEntry;

public final class MarkdownParser {
    private MarkdownParser() {}

    public static ReaderDocument parse(InputStream input, String fallbackTitle) throws IOException {
        String text = HtmlUtil.decodeText(HtmlUtil.readAll(input)).replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n", -1);
        String title = fallbackTitle;
        List<TocEntry> toc = new ArrayList<>();
        StringBuilder body = new StringBuilder(text.length() + 2048);
        boolean inCode = false;
        boolean inUl = false;
        boolean inOl = false;
        boolean inQuote = false;
        int headingIndex = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (inUl) { body.append("</ul>"); inUl = false; }
                if (inOl) { body.append("</ol>"); inOl = false; }
                if (inQuote) { body.append("</blockquote>"); inQuote = false; }
                if (!inCode) body.append("<pre><code>"); else body.append("</code></pre>");
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                body.append(HtmlUtil.escape(line)).append('\n');
                continue;
            }

            Matcher heading = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$").matcher(trimmed);
            if (heading.matches()) {
                closeLists(body, inUl, inOl, inQuote);
                inUl = inOl = inQuote = false;
                int level = heading.group(1).length();
                String raw = heading.group(2).trim();
                String clean = raw.replaceAll("[`*_~]", "").trim();
                String anchor = "md-" + (++headingIndex);
                if (title.equals(fallbackTitle) && level == 1 && !clean.isEmpty()) title = clean;
                toc.add(new TocEntry(clean, anchor, level - 1));
                body.append("<h").append(level).append(" id=\"").append(anchor).append("\">")
                        .append(inline(raw)).append("</h").append(level).append('>');
                continue;
            }
            if (trimmed.matches("^([-*_])(?:\\s*\\1){2,}$")) {
                closeLists(body, inUl, inOl, inQuote); inUl = inOl = inQuote = false;
                body.append("<hr>");
                continue;
            }
            Matcher ul = Pattern.compile("^[-+*]\\s+(.+)$").matcher(trimmed);
            Matcher ol = Pattern.compile("^\\d+[.)]\\s+(.+)$").matcher(trimmed);
            Matcher quote = Pattern.compile("^>\\s?(.*)$").matcher(trimmed);
            if (ul.matches()) {
                if (inOl) { body.append("</ol>"); inOl = false; }
                if (inQuote) { body.append("</blockquote>"); inQuote = false; }
                if (!inUl) { body.append("<ul>"); inUl = true; }
                body.append("<li>").append(inline(ul.group(1))).append("</li>");
                continue;
            }
            if (ol.matches()) {
                if (inUl) { body.append("</ul>"); inUl = false; }
                if (inQuote) { body.append("</blockquote>"); inQuote = false; }
                if (!inOl) { body.append("<ol>"); inOl = true; }
                body.append("<li>").append(inline(ol.group(1))).append("</li>");
                continue;
            }
            if (quote.matches()) {
                if (inUl) { body.append("</ul>"); inUl = false; }
                if (inOl) { body.append("</ol>"); inOl = false; }
                if (!inQuote) { body.append("<blockquote>"); inQuote = true; }
                body.append("<p>").append(inline(quote.group(1))).append("</p>");
                continue;
            }
            if (trimmed.isEmpty()) {
                closeLists(body, inUl, inOl, inQuote); inUl = inOl = inQuote = false;
            } else {
                closeLists(body, inUl, inOl, inQuote); inUl = inOl = inQuote = false;
                body.append("<p>").append(inline(trimmed)).append("</p>");
            }
        }
        if (inCode) body.append("</code></pre>");
        closeLists(body, inUl, inOl, inQuote);
        return new ReaderDocument(title, "", "", HtmlUtil.wrap(title, "", body.toString()), "about:blank", "", toc);
    }

    private static void closeLists(StringBuilder body, boolean ul, boolean ol, boolean quote) {
        if (ul) body.append("</ul>");
        if (ol) body.append("</ol>");
        if (quote) body.append("</blockquote>");
    }

    private static String inline(String raw) {
        String safe = HtmlUtil.escape(raw);
        // Links are processed after escaping, so both label and href stay HTML-safe.
        safe = safe.replaceAll("\\[([^\\]]+)\\]\\(((?:https?://|#)[^)\\s]+)\\)", "<a href=\"$2\">$1</a>");
        safe = safe.replaceAll("`([^`]+)`", "<code>$1</code>");
        safe = safe.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        safe = safe.replaceAll("__([^_]+)__", "<strong>$1</strong>");
        safe = safe.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<em>$1</em>");
        safe = safe.replaceAll("(?<!_)_([^_]+)_(?!_)", "<em>$1</em>");
        safe = safe.replaceAll("~~([^~]+)~~", "<del>$1</del>");
        return safe;
    }
}
