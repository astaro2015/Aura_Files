package ru.chitets.app.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ru.chitets.app.model.ReaderDocument;
import ru.chitets.app.model.TocEntry;

/** Lightweight DRM-free MOBI/PalmDOC/KF8 text extractor. */
public final class MobiParser {
    private MobiParser() {}

    public static ReaderDocument parse(InputStream input, String fallbackTitle) throws IOException {
        byte[] data = HtmlUtil.readAll(input);
        if (data.length < 100) throw new IOException("Слишком маленький MOBI/AZW файл");
        int records = u16(data, 76);
        if (records < 2 || records > 20000 || 78L + records * 8L > data.length) throw new IOException("Повреждён заголовок MOBI");
        int[] offsets = new int[records + 1];
        for (int i = 0; i < records; i++) offsets[i] = (int)u32(data, 78 + i * 8);
        offsets[records] = data.length;
        for (int i = 0; i < records; i++) if (offsets[i] < 0 || offsets[i] >= offsets[i + 1] || offsets[i + 1] > data.length) throw new IOException("Повреждена таблица записей MOBI");
        byte[] h = slice(data, offsets[0], offsets[1]);
        if (h.length < 16) throw new IOException("Нет PalmDOC-заголовка");
        int compression = u16(h, 0);
        int textRecords = u16(h, 8);
        int encryption = u16(h, 12);
        if (encryption != 0) throw new IOException("Защищённые DRM MOBI/AZW не поддерживаются");
        if (textRecords <= 0 || textRecords >= records) textRecords = Math.min(records - 1, 4096);

        int encoding = 65001;
        String title = fallbackTitle;
        String author = "";
        if (h.length >= 24 && ascii(h, 16, 4).equals("MOBI")) {
            if (h.length >= 32) encoding = (int)u32(h, 28);
            if (h.length >= 92) {
                int titleOffset = (int)u32(h, 84);
                int titleLength = (int)u32(h, 88);
                if (titleLength > 0 && titleLength < 8192 && titleOffset >= 0 && titleOffset + titleLength <= h.length) {
                    title = decode(slice(h, titleOffset, titleOffset + titleLength), encoding).trim();
                }
            }
            if (h.length >= 148) {
                long exthFlag = u32(h, 144);
                int mobiLen = (int)u32(h, 20);
                if ((exthFlag & 0x40) != 0 && mobiLen > 0) {
                    int exth = 16 + mobiLen;
                    if (exth + 12 <= h.length && ascii(h, exth, 4).equals("EXTH")) {
                        int exthLen = (int)u32(h, exth + 4);
                        int count = (int)u32(h, exth + 8);
                        int p = exth + 12;
                        for (int i = 0; i < count && i < 4096 && p + 8 <= h.length && p < exth + exthLen; i++) {
                            int type = (int)u32(h, p); int len = (int)u32(h, p + 4);
                            if (len < 8 || p + len > h.length) break;
                            String value = decode(slice(h, p + 8, p + len), 65001).trim();
                            if (type == 100 && author.isEmpty()) author = value;
                            if (type == 503 && !value.isEmpty()) title = value;
                            p += len;
                        }
                    }
                }
            }
        }
        if (title == null || title.trim().isEmpty()) title = fallbackTitle;

        HuffDecoder huffDecoder = null;
        if (compression == 17480) {
            int huffIndex = h.length >= 136 ? (int)u32(h, 128) : -1;
            int huffCount = h.length >= 136 ? (int)u32(h, 132) : 0;
            huffDecoder = HuffDecoder.create(data, offsets, records, huffIndex, huffCount);
        }

        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        for (int i = 1; i <= textRecords && i < records; i++) {
            byte[] record = slice(data, offsets[i], offsets[i + 1]);
            byte[] unpacked;
            if (compression == 1) unpacked = record;
            else if (compression == 2) unpacked = palmDoc(record);
            else if (compression == 17480) unpacked = huffDecoder.decompress(record);
            else throw new IOException("Неизвестное сжатие MOBI: " + compression);
            raw.write(unpacked, 0, unpacked.length);
            if (raw.size() > 64 * 1024 * 1024) throw new IOException("Текст MOBI слишком большой");
        }
        String text = decode(raw.toByteArray(), encoding).replace("\u0000", "");
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String body;
        if (lower.contains("<html") || lower.contains("<body") || lower.contains("<p") || lower.contains("<div")) {
            body = HtmlUtil.stripDangerousHtml(HtmlUtil.bodyOf(text));
            body = body.replaceAll("(?is)<mbp:pagebreak[^>]*>", "<hr class=\"page-break\">")
                    .replaceAll("(?is)</?mbp:[^>]*>", "");
        } else {
            StringBuilder b = new StringBuilder(text.length() + 1024);
            for (String p : text.replace("\r\n", "\n").replace('\r', '\n').split("\\n\\s*\\n")) {
                if (!p.trim().isEmpty()) b.append("<p>").append(HtmlUtil.escape(p.trim()).replace("\n", "<br>" )).append("</p>");
            }
            body = b.toString();
        }
        List<TocEntry> toc = buildToc(body);
        return new ReaderDocument(title, author, "", HtmlUtil.wrap(title, author, body), "about:blank", "", toc);
    }

    private static List<TocEntry> buildToc(String body) {
        List<TocEntry> toc = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)<h([1-6])[^>]*>(.*?)</h\\1>").matcher(body);
        int n = 0;
        while (m.find() && toc.size() < 500) {
            String title = HtmlUtil.cleanTitle(m.group(2));
            if (!title.isEmpty()) toc.add(new TocEntry(title, "", Math.max(0, Integer.parseInt(m.group(1)) - 1)));
            n++;
        }
        return toc;
    }

    private static byte[] palmDoc(byte[] in) throws IOException {
        byte[] out = new byte[Math.max(4096, in.length * 3)];
        int size = 0;
        int i = 0;
        while (i < in.length) {
            int c = in[i++] & 0xff;
            if (c == 0) {
                out = ensure(out, size + 1); out[size++] = 0;
            } else if (c <= 8) {
                int n = Math.min(c, in.length - i);
                out = ensure(out, size + n);
                System.arraycopy(in, i, out, size, n); size += n; i += n;
            } else if (c <= 0x7f) {
                out = ensure(out, size + 1); out[size++] = (byte)c;
            } else if (c <= 0xbf) {
                if (i >= in.length) break;
                int pair = (c << 8) | (in[i++] & 0xff);
                int distance = (pair & 0x3fff) >>> 3;
                int length = (pair & 7) + 3;
                if (distance <= 0 || distance > size) continue;
                out = ensure(out, size + length);
                for (int j = 0; j < length; j++) out[size] = out[size++ - distance];
            } else {
                out = ensure(out, size + 2); out[size++] = ' '; out[size++] = (byte)(c ^ 0x80);
            }
            if (size > 8 * 1024 * 1024) throw new IOException("Повреждённая запись MOBI распаковалась слишком сильно");
        }
        return java.util.Arrays.copyOf(out, size);
    }

    private static byte[] ensure(byte[] b, int needed) {
        if (needed <= b.length) return b;
        int n = Math.max(needed, Math.min(16 * 1024 * 1024, b.length * 2));
        return java.util.Arrays.copyOf(b, n);
    }

    private static final class HuffDecoder {
        private final int[][] table1 = new int[256][3];
        private final long[][] table2 = new long[33][2];
        private final List<DictEntry> dictionary = new ArrayList<>();

        static HuffDecoder create(byte[] data, int[] offsets, int records, int huffIndex, int huffCount) throws IOException {
            if (huffIndex <= 0 || huffIndex >= records || huffCount < 2 || huffIndex + huffCount > records)
                throw new IOException("Повреждены HUFF/CDIC-таблицы MOBI");
            HuffDecoder d = new HuffDecoder();
            byte[] huff = slice(data, offsets[huffIndex], offsets[huffIndex + 1]);
            if (!ascii(huff, 0, 4).equals("HUFF") || huff.length < 20) throw new IOException("Нет HUFF-записи MOBI");
            int offset1 = (int)u32(huff, 8), offset2 = (int)u32(huff, 12);
            if (offset1 < 0 || offset1 + 256 * 4 > huff.length || offset2 < 0 || offset2 + 32 * 8 > huff.length)
                throw new IOException("Повреждена HUFF-таблица MOBI");
            for (int i = 0; i < 256; i++) {
                long x = u32(huff, offset1 + i * 4);
                d.table1[i][0] = (x & 0x80) != 0 ? 1 : 0;
                d.table1[i][1] = (int)(x & 0x1f);
                d.table1[i][2] = (int)(x >>> 8);
            }
            for (int i = 1; i <= 32; i++) {
                d.table2[i][0] = u32(huff, offset2 + (i - 1) * 8);
                d.table2[i][1] = u32(huff, offset2 + (i - 1) * 8 + 4);
            }
            for (int r = 1; r < huffCount; r++) {
                byte[] cdic = slice(data, offsets[huffIndex + r], offsets[huffIndex + r + 1]);
                if (!ascii(cdic, 0, 4).equals("CDIC") || cdic.length < 16) throw new IOException("Повреждена CDIC-запись MOBI");
                int headerLen = (int)u32(cdic, 4);
                int numEntries = (int)u32(cdic, 8);
                int codeLength = (int)u32(cdic, 12);
                if (headerLen < 16 || headerLen >= cdic.length || codeLength < 0 || codeLength > 16) throw new IOException("Некорректный CDIC-заголовок MOBI");
                int possible = 1 << codeLength;
                int n = Math.min(possible, Math.max(0, numEntries - d.dictionary.size()));
                byte[] buffer = slice(cdic, headerLen, cdic.length);
                for (int i = 0; i < n; i++) {
                    int offset = u16(buffer, i * 2);
                    if (offset < 0 || offset + 2 > buffer.length) throw new IOException("Повреждён CDIC-словарь MOBI");
                    int x = u16(buffer, offset);
                    int len = x & 0x7fff;
                    boolean decompressed = (x & 0x8000) != 0;
                    if (offset + 2 + len > buffer.length) throw new IOException("Повреждена CDIC-строка MOBI");
                    d.dictionary.add(new DictEntry(slice(buffer, offset + 2, offset + 2 + len), decompressed));
                }
            }
            if (d.dictionary.isEmpty()) throw new IOException("Пустой HUFF/CDIC-словарь MOBI");
            return d;
        }

        byte[] decompress(byte[] input) throws IOException { return decompress(input, 0); }

        private byte[] decompress(byte[] input, int depth) throws IOException {
            if (depth > 24) throw new IOException("Зацикленный HUFF/CDIC-словарь MOBI");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(4096, input.length * 2));
            int bitLength = input.length * 8;
            for (int pos = 0; pos < bitLength;) {
                long bits = read32Bits(input, pos);
                int top = (int)((bits >>> 24) & 0xff);
                int found = table1[top][0];
                int codeLength = table1[top][1];
                long value = table1[top][2] & 0xffffffffL;
                if (codeLength <= 0) throw new IOException("Некорректный HUFF-код MOBI");
                if (found == 0) {
                    while (codeLength <= 32 && unsignedTop(bits, codeLength) < table2[codeLength][0]) codeLength++;
                    if (codeLength > 32) throw new IOException("Не удалось декодировать HUFF MOBI");
                    value = table2[codeLength][1];
                }
                pos += codeLength;
                if (pos > bitLength) break;
                long codeLong = value - unsignedTop(bits, codeLength);
                if (codeLong < 0 || codeLong >= dictionary.size()) throw new IOException("HUFF-код вне словаря MOBI");
                int code = (int)codeLong;
                DictEntry e = dictionary.get(code);
                if (!e.decompressed) {
                    e.data = decompress(e.data, depth + 1);
                    e.decompressed = true;
                }
                out.write(e.data, 0, e.data.length);
                if (out.size() > 16 * 1024 * 1024) throw new IOException("HUFF-запись MOBI распаковалась слишком сильно");
            }
            return out.toByteArray();
        }

        private static long unsignedTop(long bits, int length) {
            if (length >= 32) return bits & 0xffffffffL;
            return (bits >>> (32 - length)) & ((1L << length) - 1L);
        }

        private static long read32Bits(byte[] bytes, int from) {
            int startByte = from >>> 3;
            int end = from + 32;
            int endByte = end >>> 3;
            long bits = 0;
            for (int i = startByte; i <= endByte; i++) bits = (bits << 8) | (i < bytes.length ? bytes[i] & 0xffL : 0L);
            int shift = 8 - (end & 7);
            return (bits >>> shift) & 0xffffffffL;
        }
    }

    private static final class DictEntry {
        byte[] data; boolean decompressed;
        DictEntry(byte[] data, boolean decompressed) { this.data = data; this.decompressed = decompressed; }
    }

    private static String decode(byte[] bytes, int encoding) {
        Charset cs;
        try { cs = encoding == 65001 ? StandardCharsets.UTF_8 : Charset.forName(encoding == 1252 ? "windows-1252" : "UTF-8"); }
        catch (Exception ignored) { cs = StandardCharsets.UTF_8; }
        return new String(bytes, cs);
    }
    private static int u16(byte[] b, int p) { if (p < 0 || p + 2 > b.length) return 0; return ((b[p]&255)<<8)|(b[p+1]&255); }
    private static long u32(byte[] b, int p) { if (p < 0 || p + 4 > b.length) return 0; return ((long)(b[p]&255)<<24)|((long)(b[p+1]&255)<<16)|((long)(b[p+2]&255)<<8)|(b[p+3]&255L); }
    private static String ascii(byte[] b, int p, int n) { if (p < 0 || p + n > b.length) return ""; return new String(b, p, n, StandardCharsets.ISO_8859_1); }
    private static byte[] slice(byte[] b, int s, int e) { return java.util.Arrays.copyOfRange(b, Math.max(0,s), Math.min(b.length,e)); }
}
