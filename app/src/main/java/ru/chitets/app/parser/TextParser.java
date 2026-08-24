package ru.chitets.app.parser;

import java.io.IOException;
import java.io.InputStream;

import ru.chitets.app.model.ReaderDocument;

public final class TextParser {
    private TextParser() {}

    public static ReaderDocument parse(InputStream input, String fallbackTitle) throws IOException {
        String text = HtmlUtil.decodeText(HtmlUtil.readAll(input))
                .replace("\r\n", "\n").replace('\r', '\n');
        String title = fallbackTitle;
        for (String line : text.split("\n", 30)) {
            String candidate = line.trim();
            if (!candidate.isEmpty() && candidate.length() <= 160) {
                title = candidate;
                break;
            }
        }

        StringBuilder body = new StringBuilder(text.length() + 1024);
        StringBuilder paragraph = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                flushParagraph(body, paragraph);
            } else {
                if (paragraph.length() > 0) paragraph.append("<br>");
                paragraph.append(HtmlUtil.escape(line.trim()));
            }
        }
        flushParagraph(body, paragraph);
        return new ReaderDocument(title, "", HtmlUtil.wrap(title, "", body.toString()), "about:blank");
    }

    private static void flushParagraph(StringBuilder body, StringBuilder paragraph) {
        if (paragraph.length() == 0) return;
        body.append("<p>").append(paragraph).append("</p>");
        paragraph.setLength(0);
    }
}
