package ru.chitets.app.djvu;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Minimal safe parser for the DjVu IFF container. */
final class DjvuIff {
    static final class Chunk {
        final String id;
        final String formType;
        final int dataOffset;
        final int dataLength;
        final List<Chunk> children;
        private final byte[] source;

        Chunk(byte[] source, String id, String formType, int dataOffset, int dataLength, List<Chunk> children) {
            this.source = source;
            this.id = id;
            this.formType = formType;
            this.dataOffset = dataOffset;
            this.dataLength = dataLength;
            this.children = children == null ? Collections.emptyList() : children;
        }

        boolean isForm(String type) { return "FORM".equals(id) && type.equals(formType); }
        /** Absolute byte offset of this chunk header in the source file. */
        int headerOffset() { return dataOffset - (formType == null ? 8 : 12); }
        Chunk first(String leafId) {
            for (Chunk c : children) if (leafId.equals(c.id) && c.formType == null) return c;
            return null;
        }
        List<Chunk> all(String leafId) {
            List<Chunk> out = new ArrayList<>();
            for (Chunk c : children) if (leafId.equals(c.id) && c.formType == null) out.add(c);
            return out;
        }
        byte[] data() {
            byte[] out = new byte[dataLength];
            System.arraycopy(source, dataOffset, out, 0, dataLength);
            return out;
        }
        String textData() {
            int n = dataLength;
            while (n > 0 && source[dataOffset + n - 1] == 0) n--;
            return new String(source, dataOffset, n, StandardCharsets.ISO_8859_1).trim();
        }
    }

    static Chunk parse(byte[] bytes) throws DjvuException {
        if (bytes == null || bytes.length < 12) throw new DjvuException("DjVu/IFF: файл слишком короткий");
        int off = 0;
        if (bytes.length >= 4 && bytes[0] == 'A' && bytes[1] == 'T' && bytes[2] == '&' && bytes[3] == 'T') off = 4;
        Parsed p = parseChunk(bytes, off, bytes.length, 0);
        if (!"FORM".equals(p.chunk.id)) throw new DjvuException("DjVu/IFF: корневой FORM не найден");
        return p.chunk;
    }

    private static Parsed parseChunk(byte[] b, int off, int limit, int depth) throws DjvuException {
        if (depth > 64) throw new DjvuException("DjVu/IFF: слишком глубокая вложенность");
        if (off < 0 || off + 8 > limit || off + 8 > b.length) throw new DjvuException("DjVu/IFF: обрезанный заголовок чанка");
        String id = ascii4(b, off);
        long lenL = u32be(b, off + 4);
        if (lenL > Integer.MAX_VALUE) throw new DjvuException("DjVu/IFF: слишком большой чанк " + id);
        int len = (int) lenL;
        int payload = off + 8;
        long endL = (long) payload + len;
        if (endL > limit || endL > b.length) throw new DjvuException("DjVu/IFF: чанк " + id + " выходит за конец файла");
        int end = (int) endL;
        int consumed = 8 + len + (len & 1);
        if ("FORM".equals(id)) {
            if (len < 4) throw new DjvuException("DjVu/IFF: FORM короче 4 байт");
            String type = ascii4(b, payload);
            List<Chunk> kids = new ArrayList<>();
            int pos = payload + 4;
            while (pos + 8 <= end) {
                Parsed child = parseChunk(b, pos, end, depth + 1);
                kids.add(child.chunk);
                if (child.consumed <= 0) break;
                pos += child.consumed;
            }
            return new Parsed(new Chunk(b, id, type, payload + 4, Math.max(0, len - 4), kids), consumed);
        }
        return new Parsed(new Chunk(b, id, null, payload, len, null), consumed);
    }

    private static final class Parsed {
        final Chunk chunk; final int consumed;
        Parsed(Chunk c, int n) { chunk = c; consumed = n; }
    }

    static int u16be(byte[] b, int o) { return ((b[o] & 255) << 8) | (b[o + 1] & 255); }
    static int u16le(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8); }
    static long u32be(byte[] b, int o) {
        return ((long)(b[o] & 255) << 24) | ((long)(b[o + 1] & 255) << 16) | ((long)(b[o + 2] & 255) << 8) | (b[o + 3] & 255L);
    }
    static String ascii4(byte[] b, int o) {
        return new String(b, o, 4, StandardCharsets.ISO_8859_1);
    }

    private DjvuIff() {}
}
