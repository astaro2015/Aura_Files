package ru.chitets.app.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;

import ru.chitets.app.model.ReaderDocument;

public final class RtfParser {
    private RtfParser() {}

    public static ReaderDocument parse(InputStream input, String fallbackTitle) throws IOException {
        byte[] bytes = HtmlUtil.readAll(input);
        String rtf = new String(bytes, Charset.forName("ISO-8859-1"));
        int codePage = findCodePage(rtf);
        Charset charset;
        try { charset = Charset.forName("windows-" + codePage); }
        catch (Exception ignored) { charset = Charset.forName("windows-1251"); }

        StringBuilder text = new StringBuilder(rtf.length());
        Deque<State> stack = new ArrayDeque<>();
        State state = new State();
        int i = 0;
        while (i < rtf.length()) {
            char c = rtf.charAt(i++);
            if (c == '{') { stack.push(state.copy()); continue; }
            if (c == '}') { if (!stack.isEmpty()) state = stack.pop(); continue; }
            if (c != '\\') { if (!state.skip && c != '\n' && c != '\r') text.append(c); continue; }
            if (i >= rtf.length()) break;
            char n = rtf.charAt(i++);
            if (n == '\\' || n == '{' || n == '}') { if (!state.skip) text.append(n); continue; }
            if (n == '~') { if (!state.skip) text.append(' '); continue; }
            if (n == '-') { if (!state.skip) text.append('\u00ad'); continue; }
            if (n == '_') { if (!state.skip) text.append('\u2011'); continue; }
            if (n == '*') { state.skip = true; continue; }
            if (n == '\'') {
                if (i + 1 <= rtf.length() - 1) {
                    try {
                        int value = Integer.parseInt(rtf.substring(i, i + 2), 16);
                        if (!state.skip) text.append(new String(new byte[]{(byte)value}, charset));
                    } catch (Exception ignored) {}
                    i += 2;
                }
                continue;
            }
            if (!Character.isLetter(n)) continue;
            int start = i - 1;
            while (i < rtf.length() && Character.isLetter(rtf.charAt(i))) i++;
            String word = rtf.substring(start, i);
            boolean negative = false;
            if (i < rtf.length() && rtf.charAt(i) == '-') { negative = true; i++; }
            int numStart = i;
            while (i < rtf.length() && Character.isDigit(rtf.charAt(i))) i++;
            Integer param = null;
            if (i > numStart) {
                try { param = Integer.parseInt(rtf.substring(numStart, i)) * (negative ? -1 : 1); }
                catch (Exception ignored) {}
            }
            if (i < rtf.length() && rtf.charAt(i) == ' ') i++;

            if (isDestination(word)) { state.skip = true; continue; }
            if (state.skip) continue;
            switch (word) {
                case "par": case "line": text.append('\n'); break;
                case "tab": text.append('\t'); break;
                case "emdash": text.append('\u2014'); break;
                case "endash": text.append('\u2013'); break;
                case "bullet": text.append('\u2022'); break;
                case "lquote": case "rquote": text.append('\''); break;
                case "ldblquote": case "rdblquote": text.append('"'); break;
                case "u":
                    if (param != null) {
                        int value = param < 0 ? param + 65536 : param;
                        text.append((char)value);
                        // RTF Unicode control is followed by one fallback ANSI character by default.
                        if (i < rtf.length() && rtf.charAt(i) != '\\' && rtf.charAt(i) != '{' && rtf.charAt(i) != '}') i++;
                    }
                    break;
                default: break;
            }
        }
        String cleaned = text.toString().replace('\u0000', ' ').replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();
        return TextParser.parse(new java.io.ByteArrayInputStream(cleaned.getBytes(java.nio.charset.StandardCharsets.UTF_8)), fallbackTitle);
    }

    private static boolean isDestination(String word) {
        return word.equals("fonttbl") || word.equals("colortbl") || word.equals("stylesheet") || word.equals("info")
                || word.equals("pict") || word.equals("object") || word.equals("header") || word.equals("footer")
                || word.equals("headerl") || word.equals("headerr") || word.equals("footerl") || word.equals("footerr")
                || word.equals("listtable") || word.equals("listoverridetable") || word.equals("generator")
                || word.equals("datastore") || word.equals("themedata") || word.equals("colorschememapping");
    }

    private static int findCodePage(String rtf) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\\\ansicpg(\\d+)").matcher(rtf);
        if (m.find()) try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        return 1251;
    }

    private static final class State {
        boolean skip;
        State copy() { State s = new State(); s.skip = skip; return s; }
    }
}
